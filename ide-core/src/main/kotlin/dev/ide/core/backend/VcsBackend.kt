package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.VcsPlugin
import dev.ide.platform.log.Log
import dev.ide.ui.backend.UiForgePullRequest
import dev.ide.ui.backend.UiForgeRepo
import dev.ide.ui.backend.UiVcsAccount
import dev.ide.ui.backend.UiVcsActivity
import dev.ide.ui.backend.UiVcsBranch
import dev.ide.ui.backend.UiVcsChange
import dev.ide.ui.backend.UiVcsCommit
import dev.ide.ui.backend.UiVcsCommitDetail
import dev.ide.ui.backend.UiVcsDiff
import dev.ide.ui.backend.UiVcsIdentity
import dev.ide.ui.backend.UiVcsRemote
import dev.ide.ui.backend.UiVcsResult
import dev.ide.ui.backend.UiVcsSignIn
import dev.ide.ui.backend.UiVcsStash
import dev.ide.ui.backend.UiVcsStatus
import dev.ide.ui.backend.VcsService
import dev.ide.vcs.AccountStore
import dev.ide.vcs.DeviceAuthPoll
import dev.ide.vcs.ForgeRepo
import dev.ide.vcs.VcsAccount
import dev.ide.vcs.VcsAuthException
import dev.ide.vcs.VcsAuthor
import dev.ide.vcs.VcsBranch
import dev.ide.vcs.VcsChange
import dev.ide.vcs.VcsChangeArea
import dev.ide.vcs.VcsChangeKind
import dev.ide.vcs.VcsCommit
import dev.ide.vcs.VcsCredentials
import dev.ide.vcs.VcsException
import dev.ide.vcs.VcsMergeResult
import dev.ide.vcs.VcsOperation
import dev.ide.vcs.VCS_PROVIDER_EP
import dev.ide.vcs.VcsProgress
import dev.ide.vcs.VcsProvider
import dev.ide.vcs.VcsRepository
import dev.ide.vcs.VcsStatus
import dev.ide.vcs.impl.FileAccountStore
import dev.ide.vcs.impl.GitHubClient
import dev.ide.vcs.impl.GitProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * [VcsService] over the Git engine (vcs-impl). Holds the open project's repository, keeps the working-tree
 * snapshot fresh, and adapts every engine type to the neutral UI DTOs.
 *
 * Repository access is serialized behind one mutex: JGit's commands are not safe for concurrent use on the
 * same repository, and the UI can easily fire a refresh while a push is still in flight.
 */
internal class VcsBackend(private val ctx: BackendContext) : VcsService {

    private val log = Log.logger("ide.vcs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _status = MutableStateFlow(UiVcsStatus())
    private val _activity = MutableStateFlow(UiVcsActivity())
    private val _accounts = MutableStateFlow<List<UiVcsAccount>>(emptyList())
    private val _signIn = MutableStateFlow<UiVcsSignIn>(UiVcsSignIn.Idle)

    override val status: StateFlow<UiVcsStatus> = _status.asStateFlow()
    override val activity: StateFlow<UiVcsActivity> = _activity.asStateFlow()
    override val accounts: StateFlow<List<UiVcsAccount>> = _accounts.asStateFlow()
    override val signIn: StateFlow<UiVcsSignIn> = _signIn.asStateFlow()

    /** Holds the Git user config, the account list, and the encrypted token store, outside any project. */
    private val configDir: Path? by lazy {
        val root = ctx.manager?.sharedRoot ?: ctx.servicesOrNull?.workspaceRoot?.resolve(".platform")
        root?.resolve(VCS_DIR)?.also { runCatching { Files.createDirectories(it) } }
    }

    /**
     * The provider that owns the open checkout. A plugin-contributed [VcsProvider] on [VCS_PROVIDER_EP] wins;
     * otherwise the built-in Git provider is built here, because it needs the config directory resolved above
     * and that is not known until a project manager exists.
     */
    private val provider: VcsProvider? by lazy {
        ctx.manager?.env?.platform?.extensions?.extensions(VCS_PROVIDER_EP)?.firstOrNull()
            ?: configDir?.let { GitProvider(it) }
    }
    private val store: AccountStore? by lazy { configDir?.let { FileAccountStore(it) } }

    /** The user's own OAuth client id wins; otherwise the one this build ships (empty by default). */
    private val forge: GitHubClient by lazy {
        val configured = ctx.manager?.preference(VcsPlugin.PREF_CLIENT_ID)?.trim().orEmpty()
        GitHubClient(clientId = configured.ifBlank { GitHubClient.DEFAULT_CLIENT_ID })
    }

    /** The repository for [openRoot], opened lazily and closed when the project changes. */
    private var repository: VcsRepository? = null
    private var openRoot: Path? = null

    /** The in-flight browser sign-in poll, so [cancelSignIn] can stop it. */
    private var signInJob: Job? = null

    init {
        // A project swap invalidates the cached repository; a file-system epoch bump means something changed
        // on disk, which is exactly when the working-tree snapshot goes stale.
        scope.launch {
            ctx.projectEpoch.collect {
                lock.withLock { closeRepository() }
                refresh()
            }
        }
        scope.launch { ctx.fileSystemEpoch.drop(1).collect { refresh() } }
        scope.launch { reloadAccounts() }
    }

    override fun supported(): Boolean = provider != null

    override fun underVersionControl(): Boolean = _status.value.present

    // ---- working copy --------------------------------------------------------------------------

    override suspend fun refresh() {
        _status.value = withContext(Dispatchers.IO) {
            lock.withLock {
                val repo = repositoryOrNull() ?: return@withLock UiVcsStatus(present = false)
                runCatching { repo.status().toUi() }.getOrElse { e ->
                    log.warn("Could not read the repository status", e)
                    UiVcsStatus(present = true, error = e.userMessage())
                }
            }
        }
    }

    override suspend fun initRepository(): UiVcsResult = command {
        val root = ctx.servicesOrNull?.workspaceRoot ?: throw VcsException(NO_PROJECT)
        val git = provider ?: throw VcsException(NO_ENGINE)
        withContext(Dispatchers.IO) {
            lock.withLock {
                closeRepository()
                git.init(root).use { repo ->
                    // A fresh repository starts with the IDE's own outputs excluded, which is what a user
                    // expects from "put this project under version control".
                    repo.ignore(DEFAULT_IGNORES)
                }
            }
        }
        ctx.bumpFileSystemEpoch()
        UiVcsResult.ok("Repository created")
    }

    override suspend fun stage(paths: List<String>): UiVcsResult =
        command { withRepository { it.stage(paths) }; UiVcsResult.Ok }

    override suspend fun unstage(paths: List<String>): UiVcsResult =
        command { withRepository { it.unstage(paths) }; UiVcsResult.Ok }

    override suspend fun discard(paths: List<String>): UiVcsResult = command {
        withRepository { it.discard(paths) }
        ctx.bumpFileSystemEpoch()
        UiVcsResult.Ok
    }

    override suspend fun markResolved(paths: List<String>): UiVcsResult =
        command { withRepository { it.markResolved(paths) }; UiVcsResult.Ok }

    override suspend fun commit(message: String, amend: Boolean): UiVcsResult = command {
        val commit = withRepository { repo -> repo.commit(message, repo.identity() ?: configuredIdentity(), amend) }
        UiVcsResult.ok("Committed ${commit.shortId}")
    }

    override suspend fun addDefaultIgnores(): UiVcsResult = command {
        withRepository { it.ignore(DEFAULT_IGNORES) }
        ctx.bumpFileSystemEpoch()
        UiVcsResult.ok("Updated .gitignore")
    }

    // ---- branches ------------------------------------------------------------------------------

    override suspend fun branches(includeRemote: Boolean): List<UiVcsBranch> =
        read { repo -> repo.branches(includeRemote).map { it.toUi() } }.orEmpty()

    override suspend fun createBranch(name: String, startPoint: String?, checkout: Boolean): UiVcsResult = command {
        val branch = withRepository { it.createBranch(name.trim(), startPoint, checkout) }
        if (checkout) ctx.bumpFileSystemEpoch()
        UiVcsResult.ok("Created ${branch.name}")
    }

    override suspend fun checkoutBranch(name: String): UiVcsResult = command {
        withRepository { it.checkout(name) }
        ctx.bumpFileSystemEpoch()
        UiVcsResult.ok("Switched to $name")
    }

    override suspend fun deleteBranch(name: String, force: Boolean): UiVcsResult =
        command { withRepository { it.deleteBranch(name, force) }; UiVcsResult.ok("Deleted $name") }

    override suspend fun renameBranch(from: String, to: String): UiVcsResult =
        command { withRepository { it.renameBranch(from, to.trim()) }; UiVcsResult.ok("Renamed to ${to.trim()}") }

    override suspend fun mergeBranch(name: String): UiVcsResult = command {
        val merge = withRepository { it.merge(name) }
        ctx.bumpFileSystemEpoch()
        UiVcsResult(
            ok = merge.status != VcsMergeResult.Status.FAILED && merge.status != VcsMergeResult.Status.ABORTED,
            message = merge.message,
            conflicts = merge.conflicts,
        )
    }

    override suspend fun abortMerge(): UiVcsResult = command {
        withRepository { it.abortMerge() }
        ctx.bumpFileSystemEpoch()
        UiVcsResult.ok("Merge aborted")
    }

    // ---- history -------------------------------------------------------------------------------

    override suspend fun log(limit: Int, skip: Int, path: String?): List<UiVcsCommit> =
        read { repo -> repo.log(limit, skip, path).map { it.toUi() } }.orEmpty()

    override suspend fun commitDetail(id: String): UiVcsCommitDetail? = read { repo ->
        val detail = repo.commitDetail(id)
        UiVcsCommitDetail(
            commit = detail.commit.toUi(),
            files = detail.changes.map { it.toUi() },
            insertions = detail.insertions,
            deletions = detail.deletions,
        )
    }

    override suspend fun diff(path: String, staged: Boolean, commitId: String?): UiVcsDiff? = read { repo ->
        val diff = repo.diff(path, staged, commitId)
        UiVcsDiff(diff.path, diff.text, diff.binary, diff.insertions, diff.deletions)
    }

    // ---- stash ---------------------------------------------------------------------------------

    override suspend fun stashes(): List<UiVcsStash> =
        read { repo -> repo.stashes().map { UiVcsStash(it.index, it.message, it.timeMs, ageLabel(it.timeMs)) } }.orEmpty()

    override suspend fun stashPush(message: String, includeUntracked: Boolean): UiVcsResult = command {
        val stashed = withRepository { it.stashPush(message, includeUntracked) }
        ctx.bumpFileSystemEpoch()
        UiVcsResult.ok(if (stashed) "Changes stashed" else "There was nothing to stash")
    }

    override suspend fun stashApply(index: Int, drop: Boolean): UiVcsResult = command {
        withRepository { it.stashApply(index, drop) }
        ctx.bumpFileSystemEpoch()
        UiVcsResult.ok("Stash applied")
    }

    override suspend fun stashDrop(index: Int): UiVcsResult =
        command { withRepository { it.stashDrop(index) }; UiVcsResult.ok("Stash dropped") }

    // ---- remotes and sync ----------------------------------------------------------------------

    override suspend fun remotes(): List<UiVcsRemote> =
        read { repo -> repo.remotes().map { UiVcsRemote(it.name, it.fetchUrl) } }.orEmpty()

    override suspend fun addRemote(name: String, url: String): UiVcsResult =
        command { withRepository { it.addRemote(name.trim(), url.trim()) }; UiVcsResult.ok("Remote ${name.trim()} added") }

    override suspend fun removeRemote(name: String): UiVcsResult =
        command { withRepository { it.removeRemote(name) }; UiVcsResult.ok("Remote $name removed") }

    override suspend fun fetch(): UiVcsResult = busy("Fetching") {
        command {
            withRepository { repo -> repo.fetch(auth = credentialsFor(repo), progress = progressSink()) }
            UiVcsResult.ok("Up to date with the remote")
        }
    }

    override suspend fun pull(): UiVcsResult = busy("Pulling") {
        command {
            val sync = withRepository { repo -> repo.pull(auth = credentialsFor(repo), progress = progressSink()) }
            ctx.bumpFileSystemEpoch()
            UiVcsResult(
                ok = sync.ok,
                message = sync.message.ifBlank { if (sync.ok) "Pulled" else "The pull did not complete" },
                conflicts = sync.merge?.conflicts.orEmpty(),
            )
        }
    }

    override suspend fun push(force: Boolean): UiVcsResult = busy("Pushing") {
        command {
            val sync = withRepository { repo ->
                repo.push(force = force, auth = credentialsFor(repo), progress = progressSink())
            }
            if (!sync.ok) throw VcsException(sync.message.ifBlank { "The remote rejected the push" })
            UiVcsResult.ok("Pushed")
        }
    }

    // ---- identity ------------------------------------------------------------------------------

    override suspend fun identity(): UiVcsIdentity {
        val author = read { it.identity() } ?: configuredIdentity()
        return UiVcsIdentity(author?.name.orEmpty(), author?.email.orEmpty())
    }

    override suspend fun setIdentity(name: String, email: String): UiVcsResult = command {
        ctx.manager?.setPreference(VcsPlugin.PREF_USER_NAME, name.trim())
        ctx.manager?.setPreference(VcsPlugin.PREF_USER_EMAIL, email.trim())
        read { it.setIdentity(VcsAuthor(name.trim(), email.trim())) }
        UiVcsResult.ok("Identity saved")
    }

    // ---- accounts ------------------------------------------------------------------------------

    override fun deviceAuthSupported(): Boolean = forge.deviceAuthSupported

    override suspend fun startSignIn() {
        if (signInJob?.isActive == true) return
        val accounts = store ?: run { _signIn.value = UiVcsSignIn.Failed(NO_ENGINE); return }
        _signIn.value = UiVcsSignIn.Starting
        signInJob = scope.launch {
            try {
                val grant = withContext(Dispatchers.IO) { forge.startDeviceAuth() }
                _signIn.value = UiVcsSignIn.AwaitingUser(grant.userCode, grant.verificationUri, grant.expiresInSeconds)

                var interval = grant.intervalSeconds.coerceAtLeast(1)
                val deadline = System.currentTimeMillis() + grant.expiresInSeconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    when (val poll = withContext(Dispatchers.IO) { forge.pollDeviceAuth(grant.deviceCode) }) {
                        DeviceAuthPoll.Pending -> Unit
                        is DeviceAuthPoll.SlowDown -> interval = poll.intervalSeconds.coerceAtLeast(interval + 1)
                        is DeviceAuthPoll.Failed -> {
                            _signIn.value = UiVcsSignIn.Failed(poll.message)
                            return@launch
                        }

                        is DeviceAuthPoll.Authorized -> {
                            val account = withContext(Dispatchers.IO) {
                                accounts.add(forge.verifyToken(poll.token).copy(kind = VcsAccount.Kind.OAUTH), poll.token)
                            }
                            reloadAccounts()
                            _signIn.value = UiVcsSignIn.Done(account.toUi(active = true))
                            return@launch
                        }
                    }
                }
                _signIn.value = UiVcsSignIn.Failed("The sign-in code expired. Start again.")
            } catch (e: Exception) {
                log.warn("GitHub sign-in failed", e)
                _signIn.value = UiVcsSignIn.Failed(e.userMessage())
            }
        }
    }

    override fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        _signIn.value = UiVcsSignIn.Idle
    }

    override suspend fun signInWithToken(token: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(NO_ENGINE)
        if (token.isBlank()) throw VcsException("Paste a personal access token")
        val account = withContext(Dispatchers.IO) {
            accounts.add(forge.verifyToken(token.trim()).copy(kind = VcsAccount.Kind.TOKEN), token.trim())
        }
        reloadAccounts()
        _signIn.value = UiVcsSignIn.Done(account.toUi(active = true))
        UiVcsResult.ok("Signed in as ${account.login}")
    }

    override suspend fun signOut(accountId: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(NO_ENGINE)
        withContext(Dispatchers.IO) { accounts.remove(accountId) }
        reloadAccounts()
        _signIn.value = UiVcsSignIn.Idle
        UiVcsResult.ok("Signed out")
    }

    override suspend fun setActiveAccount(accountId: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(NO_ENGINE)
        withContext(Dispatchers.IO) { accounts.setActive(accountId) }
        reloadAccounts()
        UiVcsResult.Ok
    }

    override suspend fun credentialHosts(): List<String> {
        val accounts = store ?: return emptyList()
        return withContext(Dispatchers.IO) { accounts.credentialHosts() }
    }

    override suspend fun saveHostCredentials(host: String, username: String, password: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(NO_ENGINE)
        if (host.isBlank() || username.isBlank()) throw VcsException("Enter the host and your username")
        withContext(Dispatchers.IO) { accounts.saveHostCredentials(host.trim(), username.trim(), password) }
        UiVcsResult.ok("Saved credentials for ${host.trim()}")
    }

    override suspend fun clearHostCredentials(host: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(NO_ENGINE)
        withContext(Dispatchers.IO) { accounts.clearHostCredentials(host) }
        UiVcsResult.ok("Removed credentials for $host")
    }

    // ---- forge ---------------------------------------------------------------------------------

    override suspend fun forgeRepositories(query: String, page: Int): List<UiForgeRepo> {
        val token = activeToken() ?: return emptyList()
        return runCatching {
            withContext(Dispatchers.IO) { forge.repositories(token, query, page) }.map { it.toUi() }
        }.getOrElse { e ->
            log.warn("Could not list repositories", e)
            emptyList()
        }
    }

    override suspend fun cloneRepository(url: String, directoryName: String): UiVcsResult = busy("Cloning") {
        command {
            val git = provider ?: throw VcsException(NO_ENGINE)
            val manager = ctx.manager ?: throw VcsException(NO_PROJECT)
            val name = directoryName.trim().ifBlank { url.trim().substringAfterLast('/').removeSuffix(".git") }
            if (name.isBlank()) throw VcsException("Enter a folder name for the clone")
            val target = manager.projectsRoot.resolve(name)
            val auth = store?.credentialsFor(url) ?: VcsCredentials.Anonymous
            withContext(Dispatchers.IO) {
                git.clone(url.trim(), target, auth = auth, progress = progressSink()).close()
            }
            UiVcsResult(true, "Cloned into $name", path = target.toString())
        }
    }

    override suspend fun publishToForge(name: String, description: String, private: Boolean): UiVcsResult =
        busy("Publishing") {
            command {
                val token = activeToken() ?: return@command UiVcsResult(false, SIGN_IN_FIRST, authRequired = true)
                val created = withContext(Dispatchers.IO) {
                    forge.createRepository(token, name.trim(), description.trim(), private)
                }
                withRepository { repo ->
                    repo.addRemote(VcsRepository.DEFAULT_REMOTE, created.cloneUrl)
                    val sync = repo.push(auth = credentialsFor(repo), progress = progressSink())
                    if (!sync.ok) throw VcsException(sync.message.ifBlank { "The remote rejected the push" })
                }
                UiVcsResult.ok("Published to ${created.fullName}")
            }
        }

    override suspend fun pullRequests(): List<UiForgePullRequest> {
        val token = activeToken() ?: return emptyList()
        val slug = originSlug() ?: return emptyList()
        return runCatching {
            withContext(Dispatchers.IO) { forge.pullRequests(token, slug.first, slug.second) }.map {
                UiForgePullRequest(
                    number = it.number,
                    title = it.title,
                    author = it.author,
                    headBranch = it.headBranch,
                    baseBranch = it.baseBranch,
                    webUrl = it.webUrl,
                    draft = it.draft,
                    updatedMs = it.updatedMs,
                    updatedLabel = ageLabel(it.updatedMs),
                )
            }
        }.getOrElse { e ->
            log.warn("Could not list pull requests", e)
            emptyList()
        }
    }

    override suspend fun createPullRequest(title: String, body: String, base: String): UiVcsResult = command {
        val token = activeToken() ?: return@command UiVcsResult(false, SIGN_IN_FIRST, authRequired = true)
        val slug = originSlug() ?: throw VcsException("This project has no GitHub remote")
        val head = _status.value.branch
        if (head.isBlank()) throw VcsException("HEAD is detached, so there is no branch to propose")
        val pr = withContext(Dispatchers.IO) {
            forge.createPullRequest(token, slug.first, slug.second, title.trim(), body, head, base)
        }
        UiVcsResult.ok("Opened pull request #${pr.number}")
    }

    // ---- repository access ---------------------------------------------------------------------

    /** Open (and cache) the repository for the current project, or null when there is none. Holds [lock]. */
    private fun repositoryOrNull(): VcsRepository? {
        val git = provider ?: return null
        val workspace = ctx.servicesOrNull?.workspaceRoot ?: return null
        val root = git.findRoot(workspace)
        if (root == null) {
            closeRepository()
            return null
        }
        val cached = repository
        if (cached != null && openRoot == root) return cached
        closeRepository()
        return runCatching { git.open(root) }
            .onSuccess { repository = it; openRoot = root }
            .getOrElse { e -> log.warn("Could not open the repository at $root", e); null }
    }

    private fun closeRepository() {
        repository?.let { runCatching { it.close() } }
        repository = null
        openRoot = null
    }

    /** Release the open repository and stop the refresh and sign-in coroutines. Called on app teardown. */
    fun close() {
        runCatching { signInJob?.cancel() }
        runCatching { scope.cancel() }
        synchronized(this) { closeRepository() }
    }

    /** Run [body] against the open repository, failing when the project is not under version control. */
    private suspend fun <T> withRepository(body: (VcsRepository) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val repo = repositoryOrNull() ?: throw VcsException("This project is not under version control")
            body(repo)
        }
    }

    /** Run [body] as a read, returning null when there is no repository or the read failed. */
    private suspend fun <T> read(body: (VcsRepository) -> T): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            val repo = repositoryOrNull() ?: return@withLock null
            runCatching { body(repo) }.getOrElse { e -> log.warn("Version-control read failed", e); null }
        }
    }

    private fun credentialsFor(repo: VcsRepository): VcsCredentials {
        val url = runCatching { repo.remotes() }.getOrDefault(emptyList())
            .firstOrNull { it.name == VcsRepository.DEFAULT_REMOTE }?.fetchUrl
            ?: return VcsCredentials.Anonymous
        return store?.credentialsFor(url) ?: VcsCredentials.Anonymous
    }

    /** `owner` and `name` of the repository `origin` points at, or null when there is no usable remote. */
    private suspend fun originSlug(): Pair<String, String>? {
        val url = read { repo ->
            repo.remotes().firstOrNull { it.name == VcsRepository.DEFAULT_REMOTE }?.fetchUrl
        } ?: return null
        return parseSlug(url)
    }

    private fun activeToken(): String? {
        val accounts = store ?: return null
        val active = accounts.activeAccount() ?: return null
        return accounts.token(active.id)
    }

    private suspend fun reloadAccounts() {
        val accounts = store ?: return
        _accounts.value = withContext(Dispatchers.IO) {
            val active = accounts.activeAccount()?.id
            accounts.accounts().map { it.toUi(it.id == active) }
        }
    }

    /** The identity from the Version Control settings page, used when the repository config carries none. */
    private fun configuredIdentity(): VcsAuthor? {
        val name = ctx.manager?.preference(VcsPlugin.PREF_USER_NAME)?.trim().orEmpty()
        val email = ctx.manager?.preference(VcsPlugin.PREF_USER_EMAIL)?.trim().orEmpty()
        return if (name.isBlank() && email.isBlank()) null else VcsAuthor(name.ifBlank { email }, email)
    }

    private fun progressSink(): VcsProgress = VcsProgress { task, completed, total ->
        _activity.value = UiVcsActivity(
            busy = true,
            task = task,
            fraction = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else -1f,
        )
    }

    /** Mark a long-running command as in flight so the panel can show a progress row. */
    private suspend fun <T> busy(task: String, body: suspend () -> T): T {
        _activity.value = UiVcsActivity(busy = true, task = task)
        return try {
            body()
        } finally {
            _activity.value = UiVcsActivity()
        }
    }

    /**
     * Run a mutating command, refresh the working-tree snapshot, and turn any engine failure into a result
     * carrying a message the UI shows as-is.
     *
     * Catches [Throwable] rather than [Exception]: the Git engine is a desktop-JVM library, so a call into it
     * can fail with a [LinkageError] when the device's runtime lacks a method it was compiled against. Such an
     * error is not an [Exception], so it used to unwind out of the coroutine and take the process down.
     */
    private suspend fun command(body: suspend () -> UiVcsResult): UiVcsResult = try {
        val result = body()
        refresh()
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: VcsAuthException) {
        refresh()
        UiVcsResult(false, e.userMessage(), authRequired = true)
    } catch (e: Throwable) {
        log.warn("Version-control command failed", e)
        refresh()
        UiVcsResult(false, e.userMessage())
    }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Something went wrong (${this::class.java.simpleName})"

    // ---- mapping -------------------------------------------------------------------------------

    private fun VcsStatus.toUi(): UiVcsStatus = UiVcsStatus(
        present = true,
        branch = branch.orEmpty(),
        detached = detached,
        unborn = unborn,
        upstream = tracking.upstream.orEmpty(),
        ahead = tracking.ahead,
        behind = tracking.behind,
        operation = operation.toUiId(),
        staged = staged.map { it.toUi() },
        unstaged = unstaged.map { it.toUi() },
        conflicted = conflicted.map { it.toUi() },
        headSummary = head?.summary.orEmpty(),
        headShortId = head?.shortId.orEmpty(),
    )

    private fun VcsChange.toUi(): UiVcsChange = UiVcsChange(
        path = path,
        name = path.substringAfterLast('/'),
        directory = path.substringBeforeLast('/', ""),
        status = when (kind) {
            VcsChangeKind.ADDED -> UiVcsChange.STATUS_ADDED
            VcsChangeKind.MODIFIED -> UiVcsChange.STATUS_MODIFIED
            VcsChangeKind.DELETED -> UiVcsChange.STATUS_DELETED
            VcsChangeKind.RENAMED -> UiVcsChange.STATUS_RENAMED
            VcsChangeKind.COPIED -> UiVcsChange.STATUS_COPIED
            VcsChangeKind.UNTRACKED, VcsChangeKind.IGNORED -> UiVcsChange.STATUS_UNTRACKED
            VcsChangeKind.CONFLICTED -> UiVcsChange.STATUS_CONFLICTED
        },
        staged = area == VcsChangeArea.STAGED,
        conflicted = area == VcsChangeArea.CONFLICTED,
        oldPath = oldPath,
    )

    private fun VcsBranch.toUi(): UiVcsBranch =
        UiVcsBranch(name, remote, current, upstream.orEmpty(), tip?.take(SHORT_ID).orEmpty())

    private fun VcsCommit.toUi(): UiVcsCommit =
        UiVcsCommit(id, shortId, summary, body, author.name, author.email, timeMs, ageLabel(timeMs), refs, merge)

    private fun VcsAccount.toUi(active: Boolean): UiVcsAccount =
        UiVcsAccount(id, forgeId, host, login, name, avatarUrl, active)

    private fun ForgeRepo.toUi(): UiForgeRepo = UiForgeRepo(
        owner = owner,
        name = name,
        fullName = fullName,
        description = description,
        private = private,
        fork = fork,
        defaultBranch = defaultBranch,
        cloneUrl = cloneUrl,
        webUrl = webUrl,
        stars = stars,
        language = language,
        updatedMs = updatedMs,
        updatedLabel = ageLabel(updatedMs),
    )

    private fun VcsOperation.toUiId(): String = when (this) {
        VcsOperation.NONE -> UiVcsStatus.OP_NONE
        VcsOperation.MERGE -> UiVcsStatus.OP_MERGE
        VcsOperation.REBASE -> UiVcsStatus.OP_REBASE
        VcsOperation.CHERRY_PICK -> UiVcsStatus.OP_CHERRY_PICK
        VcsOperation.REVERT -> UiVcsStatus.OP_REVERT
        VcsOperation.BISECT -> UiVcsStatus.OP_BISECT
    }

    private companion object {
        const val VCS_DIR = "vcs"
        const val SHORT_ID = 7
        const val NO_PROJECT = "Open a project first"
        const val NO_ENGINE = "Version control is not available in this build"
        const val SIGN_IN_FIRST = "Sign in to GitHub first"

        /** What a CodeAssist project should not track: build output, IDE metadata, and signing material. */
        val DEFAULT_IGNORES = listOf(
            "build/",
            ".platform/",
            ".gradle/",
            "local.properties",
            "*.apk",
            "*.aab",
            "*.jks",
            "*.keystore",
            ".DS_Store",
        )
    }
}

/**
 * How long ago [timeMs] was, as the short label history and repository lists show: minutes and hours for
 * today, days inside a week, then a short date. Formatted here because the Compose UI is platform-neutral
 * and has no clock or locale formatter of its own (the same reason log entries carry a formatted label).
 */
internal fun ageLabel(timeMs: Long, now: Long = System.currentTimeMillis()): String {
    if (timeMs <= 0L) return ""
    val elapsed = now - timeMs
    return when {
        elapsed < 60_000L -> "now"
        elapsed < 3_600_000L -> "${elapsed / 60_000L}m"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L}h"
        elapsed < 7 * 86_400_000L -> "${elapsed / 86_400_000L}d"
        else -> runCatching {
            java.time.Instant.ofEpochMilli(timeMs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT))
        }.getOrDefault("")
    }
}

/**
 * `owner` and `name` from a remote URL, in both forms Git accepts: `https://host/owner/repo.git` and the
 * SCP-like `git@host:owner/repo.git`.
 */
internal fun parseSlug(remoteUrl: String): Pair<String, String>? {
    val trimmed = remoteUrl.trim().removeSuffix("/").removeSuffix(".git")
    val tail = when {
        "://" in trimmed -> trimmed.substringAfter("://").substringAfter('/')
        ':' in trimmed -> trimmed.substringAfter(':')
        else -> return null
    }
    val parts = tail.split('/').filter { it.isNotBlank() }
    if (parts.size < 2) return null
    return parts[parts.size - 2] to parts[parts.size - 1]
}
