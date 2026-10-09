package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.plugins.VcsPlugin
import dev.ide.core.project.ImportableKind
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
import dev.ide.ui.backend.UiVcsText
import dev.ide.ui.backend.VcsService
import dev.ide.vcs.AccountStore
import dev.ide.vcs.DeviceAuthPoll
import dev.ide.vcs.ForgeRepo
import dev.ide.vcs.VCS_PROVIDER_EP
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
import dev.ide.vcs.VcsMessage
import dev.ide.vcs.VcsOperation
import dev.ide.vcs.VcsProgress
import dev.ide.vcs.VcsProvider
import dev.ide.vcs.VcsRepository
import dev.ide.vcs.VcsStatus
import dev.ide.vcs.VcsText
import dev.ide.vcs.impl.FileAccountStore
import dev.ide.vcs.impl.GitHubClient
import dev.ide.vcs.impl.GitProvider
import dev.ide.vcs.impl.hostOf
import dev.ide.vcs.impl.redactUrl
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

    /**
     * Accounts whose token the forge refused this session. Listing calls cannot return an error, so a revoked
     * token used to read as "no repositories"; marking the account lets every screen say to sign in again.
     * Signing that account in again clears it.
     */
    private val refusedAccounts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
                runCatching {
                    val remotes = runCatching { repo.remotes().map { it.name } }.getOrDefault(emptyList())
                    repo.status().toUi(repo.root.toString()).copy(remotes = remotes)
                }.getOrElse { e ->
                    log.warn("Could not read the repository status", e)
                    e.userText().let { UiVcsStatus(present = true, error = it.english, errorText = it.toUi()) }
                }
            }
        }
    }

    override suspend fun initRepository(): UiVcsResult = command {
        val root = ctx.servicesOrNull?.workspaceRoot ?: throw VcsException(VcsText.of(VcsMessage.NO_PROJECT))
        val git = provider ?: throw VcsException(VcsText.of(VcsMessage.NO_ENGINE))
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
        ok(VcsMessage.REPO_CREATED)
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
        // The Settings identity wins over one in the repository config. Earlier builds copied it into every
        // repository it was saved in, so a repository identity is usually a stale copy of it, and preferring
        // that meant a later edit in Settings was silently ignored.
        val commit = withRepository { repo -> repo.commit(message, configuredIdentity() ?: repo.identity(), amend) }
        ok(VcsMessage.COMMITTED, commit.shortId)
    }

    override suspend fun addDefaultIgnores(): UiVcsResult = command {
        withRepository { it.ignore(DEFAULT_IGNORES) }
        ctx.bumpFileSystemEpoch()
        ok(VcsMessage.GITIGNORE_UPDATED)
    }

    // ---- branches ------------------------------------------------------------------------------

    override suspend fun branches(includeRemote: Boolean): List<UiVcsBranch> =
        read { repo -> repo.branches(includeRemote).map { it.toUi() } }.orEmpty()

    override suspend fun createBranch(name: String, startPoint: String?, checkout: Boolean): UiVcsResult = command {
        val branch = withRepository { it.createBranch(name.trim(), startPoint, checkout) }
        if (checkout) ctx.bumpFileSystemEpoch()
        ok(VcsMessage.BRANCH_CREATED, branch.name)
    }

    override suspend fun checkoutBranch(name: String): UiVcsResult = command {
        withRepository { it.checkout(name) }
        ctx.bumpFileSystemEpoch()
        ok(VcsMessage.SWITCHED, name)
    }

    override suspend fun deleteBranch(name: String, force: Boolean): UiVcsResult =
        command { withRepository { it.deleteBranch(name, force) }; ok(VcsMessage.BRANCH_DELETED, name) }

    override suspend fun renameBranch(from: String, to: String): UiVcsResult =
        command { withRepository { it.renameBranch(from, to.trim()) }; ok(VcsMessage.BRANCH_RENAMED, to.trim()) }

    override suspend fun mergeBranch(name: String): UiVcsResult = command {
        val merge = withRepository { it.merge(name) }
        ctx.bumpFileSystemEpoch()
        UiVcsResult(
            ok = merge.status != VcsMergeResult.Status.FAILED && merge.status != VcsMergeResult.Status.ABORTED,
            message = merge.message,
            conflicts = merge.conflicts,
            text = merge.text.toUi(),
        )
    }

    override suspend fun abortMerge(): UiVcsResult = command {
        withRepository { it.abortMerge() }
        ctx.bumpFileSystemEpoch()
        ok(VcsMessage.ABORTED)
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
        ok(if (stashed) VcsMessage.STASHED else VcsMessage.NOTHING_TO_STASH)
    }

    override suspend fun stashApply(index: Int, drop: Boolean): UiVcsResult = command {
        withRepository { it.stashApply(index, drop) }
        ctx.bumpFileSystemEpoch()
        ok(VcsMessage.STASH_APPLIED)
    }

    override suspend fun stashDrop(index: Int): UiVcsResult =
        command { withRepository { it.stashDrop(index) }; ok(VcsMessage.STASH_DROPPED) }

    // ---- remotes and sync ----------------------------------------------------------------------

    override suspend fun remotes(): List<UiVcsRemote> =
        read { repo -> repo.remotes().map { UiVcsRemote(it.name, it.fetchUrl) } }.orEmpty()

    override suspend fun addRemote(name: String, url: String): UiVcsResult =
        command { withRepository { it.addRemote(name.trim(), url.trim()) }; ok(VcsMessage.REMOTE_ADDED, name.trim()) }

    override suspend fun removeRemote(name: String): UiVcsResult =
        command { withRepository { it.removeRemote(name) }; ok(VcsMessage.REMOTE_REMOVED, name) }

    override suspend fun fetch(): UiVcsResult = busy(VcsMessage.ACTIVITY_FETCHING) {
        command {
            withRepository { repo ->
                val remote = syncRemote(repo)
                repo.fetch(remote, auth = credentialsFor(repo, remote), progress = progressSink())
            }
            ok(VcsMessage.FETCHED)
        }
    }

    override suspend fun pull(): UiVcsResult = busy(VcsMessage.ACTIVITY_PULLING) {
        command {
            val sync = withRepository { repo ->
                val remote = syncRemote(repo)
                repo.pull(remote, auth = credentialsFor(repo, remote), progress = progressSink())
            }
            ctx.bumpFileSystemEpoch()
            val text = sync.text.takeIf { sync.message.isNotBlank() }
                ?: VcsText.of(if (sync.ok) VcsMessage.PULLED else VcsMessage.PULL_NOT_COMPLETED)
            UiVcsResult(
                ok = sync.ok,
                message = text.english,
                conflicts = sync.merge?.conflicts.orEmpty(),
                text = text.toUi(),
            )
        }
    }

    override suspend fun push(force: Boolean): UiVcsResult = busy(VcsMessage.ACTIVITY_PUSHING) {
        command {
            val sync = withRepository { repo ->
                val remote = syncRemote(repo)
                repo.push(remote, force = force, auth = credentialsFor(repo, remote, push = true), progress = progressSink())
            }
            if (!sync.ok) throw VcsException(sync.text.takeIf { sync.message.isNotBlank() } ?: VcsText.of(VcsMessage.PUSH_REJECTED))
            ok(VcsMessage.PUSHED)
        }
    }

    // ---- identity ------------------------------------------------------------------------------

    override suspend fun identity(): UiVcsIdentity {
        val author = configuredIdentity() ?: read { it.identity() }
        return UiVcsIdentity(author?.name.orEmpty(), author?.email.orEmpty())
    }

    override suspend fun setIdentity(name: String, email: String): UiVcsResult = command {
        ctx.manager?.setPreference(VcsPlugin.PREF_USER_NAME, name.trim())
        ctx.manager?.setPreference(VcsPlugin.PREF_USER_EMAIL, email.trim())
        ok(VcsMessage.IDENTITY_SAVED)
    }

    // ---- accounts ------------------------------------------------------------------------------

    override fun deviceAuthSupported(): Boolean = forge.deviceAuthSupported

    override suspend fun startSignIn() {
        if (signInJob?.isActive == true) return
        val accounts = store ?: run { _signIn.value = signInFailed(VcsText.of(VcsMessage.NO_ENGINE)); return }
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
                            _signIn.value = signInFailed(poll.text)
                            return@launch
                        }

                        is DeviceAuthPoll.Authorized -> {
                            val account = withContext(Dispatchers.IO) {
                                accounts.add(forge.verifyToken(poll.token).copy(kind = VcsAccount.Kind.OAUTH), poll.token)
                                    .also { accounts.setActive(it.id) }
                            }
                            refusedAccounts.remove(account.id)
                            reloadAccounts()
                            _signIn.value = UiVcsSignIn.Done(account.toUi(active = true))
                            return@launch
                        }
                    }
                }
                _signIn.value = signInFailed(VcsText.of(VcsMessage.SIGN_IN_CODE_EXPIRED))
            } catch (e: CancellationException) {
                // cancelSignIn already set Idle; reporting the cancellation as a failure would overwrite it.
                throw e
            } catch (e: Exception) {
                log.warn("GitHub sign-in failed", e)
                _signIn.value = signInFailed(e.userText())
            }
        }
    }

    override fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        _signIn.value = UiVcsSignIn.Idle
    }

    override suspend fun signInWithToken(token: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(VcsText.of(VcsMessage.NO_ENGINE))
        if (token.isBlank()) throw VcsException(VcsText.of(VcsMessage.TOKEN_REQUIRED))
        val account = withContext(Dispatchers.IO) {
            // The account just signed in is the one the user means to use next, so it becomes the active one.
            accounts.add(forge.verifyToken(token.trim()).copy(kind = VcsAccount.Kind.TOKEN), token.trim())
                .also { accounts.setActive(it.id) }
        }
        refusedAccounts.remove(account.id)
        reloadAccounts()
        _signIn.value = UiVcsSignIn.Done(account.toUi(active = true))
        ok(VcsMessage.SIGNED_IN, account.login)
    }

    override suspend fun signOut(accountId: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(VcsText.of(VcsMessage.NO_ENGINE))
        withContext(Dispatchers.IO) { accounts.remove(accountId) }
        reloadAccounts()
        _signIn.value = UiVcsSignIn.Idle
        ok(VcsMessage.SIGNED_OUT)
    }

    override suspend fun setActiveAccount(accountId: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(VcsText.of(VcsMessage.NO_ENGINE))
        withContext(Dispatchers.IO) { accounts.setActive(accountId) }
        reloadAccounts()
        UiVcsResult.Ok
    }

    override suspend fun credentialHosts(): List<String> {
        val accounts = store ?: return emptyList()
        return withContext(Dispatchers.IO) { accounts.credentialHosts() }
    }

    override suspend fun saveHostCredentials(host: String, username: String, password: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(VcsText.of(VcsMessage.NO_ENGINE))
        if (host.isBlank() || username.isBlank()) throw VcsException(VcsText.of(VcsMessage.HOST_CREDENTIALS_REQUIRED))
        withContext(Dispatchers.IO) { accounts.saveHostCredentials(host.trim(), username.trim(), password) }
        ok(VcsMessage.HOST_CREDENTIALS_SAVED, host.trim())
    }

    override suspend fun clearHostCredentials(host: String): UiVcsResult = command {
        val accounts = store ?: throw VcsException(VcsText.of(VcsMessage.NO_ENGINE))
        withContext(Dispatchers.IO) { accounts.clearHostCredentials(host) }
        ok(VcsMessage.HOST_CREDENTIALS_REMOVED, host)
    }

    // ---- forge ---------------------------------------------------------------------------------

    override suspend fun forgeRepositories(query: String, page: Int): List<UiForgeRepo> {
        val token = activeToken() ?: return emptyList()
        return runCatching {
            withContext(Dispatchers.IO) { forge.repositories(token, query, page) }.map { it.toUi() }
        }.getOrElse { e ->
            noteRefusal(e)
            log.warn("Could not list repositories", e)
            emptyList()
        }
    }

    override suspend fun cloneRepository(url: String, directoryName: String): UiVcsResult = busy(VcsMessage.ACTIVITY_CLONING) {
        command {
            val git = provider ?: throw VcsException(VcsText.of(VcsMessage.NO_ENGINE))
            val manager = ctx.manager ?: throw VcsException(VcsText.of(VcsMessage.NO_PROJECT))
            val name = directoryName.trim().ifBlank { url.trim().substringAfterLast('/').removeSuffix(".git") }
            if (name.isBlank()) throw VcsException(VcsText.of(VcsMessage.CLONE_FOLDER_REQUIRED))
            // A pasted URL may carry a token (`https://ghp_x@github.com/...`); only the clone itself may see it.
            val shownUrl = redactUrl(url.trim())
            val target = manager.projectsRoot.resolve(name)
            val auth = store?.credentialsFor(url) ?: VcsCredentials.Anonymous
            withContext(Dispatchers.IO) {
                git.clone(url.trim(), target, auth = auth, progress = progressSink()).close()
            }
            // A repository is not a CodeAssist project. Adopt whatever landed so it is listable and openable,
            // and report what it turned out to be: a clone that no build system recognizes is a real outcome
            // the screen has to say out loud, not a silent one the user discovers via an empty picker.
            val kind = withContext(Dispatchers.IO) {
                runCatching { manager.adoptFolderInPlace(target, origin = shownUrl) }
                    .getOrElse { e ->
                        log.warn("Cloned $shownUrl but could not adopt $target as a project", e)
                        ImportableKind.NONE
                    }
            }
            ok(VcsMessage.CLONED, name).copy(path = target.toString(), projectKind = kind.toUi())
        }
    }

    override suspend fun publishToForge(name: String, description: String, private: Boolean): UiVcsResult =
        busy(VcsMessage.ACTIVITY_PUBLISHING) {
            command {
                val token = activeToken() ?: return@command failed(VcsText.of(VcsMessage.SIGN_IN_FIRST), authRequired = true)
                // Everything that would make the push fail is checked before the GitHub repository exists. Creating
                // it first left an empty repository behind on every failed attempt, and the retry then failed with
                // "name already exists".
                withRepository { repo ->
                    val status = repo.status()
                    if (status.unborn) throw VcsException(VcsText.of(VcsMessage.PUBLISH_NEEDS_COMMIT))
                    if (status.branch == null) throw VcsException(VcsText.of(VcsMessage.PUBLISH_DETACHED))
                    if (repo.remotes().any { it.name == VcsRepository.DEFAULT_REMOTE }) {
                        throw VcsException(VcsText.of(VcsMessage.PUBLISH_REMOTE_EXISTS, VcsRepository.DEFAULT_REMOTE))
                    }
                }
                val created = withContext(Dispatchers.IO) {
                    forge.createRepository(token, name.trim(), description.trim(), private)
                }
                withRepository { repo ->
                    repo.addRemote(VcsRepository.DEFAULT_REMOTE, created.cloneUrl)
                    val sync = repo.push(auth = credentialsFor(repo, VcsRepository.DEFAULT_REMOTE, push = true), progress = progressSink())
                    if (!sync.ok) throw VcsException(sync.text.takeIf { sync.message.isNotBlank() } ?: VcsText.of(VcsMessage.PUSH_REJECTED))
                }
                ok(VcsMessage.PUBLISHED, created.fullName)
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
            noteRefusal(e)
            log.warn("Could not list pull requests", e)
            emptyList()
        }
    }

    override suspend fun defaultBranch(): String? {
        val token = activeToken()
        val slug = originSlug()
        if (token != null && slug != null) {
            val fromForge = runCatching {
                withContext(Dispatchers.IO) { forge.repository(token, slug.first, slug.second) }?.defaultBranch
            }.onFailure { noteRefusal(it) }.getOrNull()
            if (!fromForge.isNullOrBlank()) return fromForge
        }
        // Offline, or not on GitHub: the remote's own branches are the next best evidence.
        return read { repo ->
            val remote = runCatching { syncRemote(repo) }.getOrNull() ?: return@read null
            val names = repo.branches(includeRemote = true).filter { it.remote }.map { it.name }
            listOf("main", "master", "trunk", "develop").firstOrNull { "$remote/$it" in names }
        }
    }

    /** Mark the active account as needing a new sign-in when [failure] is the forge refusing its token. */
    private suspend fun noteRefusal(failure: Throwable) {
        if (failure !is VcsAuthException) return
        val active = store?.let { withContext(Dispatchers.IO) { it.activeAccount() } } ?: return
        if (refusedAccounts.add(active.id)) reloadAccounts()
    }

    override suspend fun createPullRequest(title: String, body: String, base: String): UiVcsResult = command {
        val token = activeToken() ?: return@command failed(VcsText.of(VcsMessage.SIGN_IN_FIRST), authRequired = true)
        val slug = originSlug() ?: throw VcsException(VcsText.of(VcsMessage.NO_GITHUB_REMOTE))
        if (title.isBlank()) throw VcsException(VcsText.of(VcsMessage.PR_TITLE_REQUIRED))
        // Read under the lock rather than from the last published status, which can predate a branch switch.
        val head = withRepository { it.status().branch }
            ?: throw VcsException(VcsText.of(VcsMessage.PR_DETACHED))
        if (head == base.trim()) throw VcsException(VcsText.of(VcsMessage.PR_SAME_BRANCH, head))
        val pr = withContext(Dispatchers.IO) {
            forge.createPullRequest(token, slug.first, slug.second, title.trim(), body, head, base)
        }
        ok(VcsMessage.PR_OPENED, pr.number)
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
            val repo = repositoryOrNull() ?: throw VcsException(VcsText.of(VcsMessage.NOT_UNDER_VCS))
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

    /**
     * The remote fetch, pull and push talk to: the one the current branch tracks, else `origin`, else the only
     * other remote there is. A branch that tracks nothing yet still has somewhere to go, which is what lets the
     * first push from a hand-added remote record the tracking link.
     */
    private fun syncRemote(repo: VcsRepository): String {
        val names = repo.remotes().map { it.name }
        // A remote name may itself contain a slash, so the longest name that prefixes the upstream is the one.
        val tracked = repo.branches(includeRemote = false).firstOrNull { it.current }?.upstream
            ?.let { upstream -> names.filter { upstream.startsWith("$it/") }.maxByOrNull { it.length } }
        return tracked
            ?: VcsRepository.DEFAULT_REMOTE.takeIf { it in names }
            ?: names.firstOrNull()
            ?: throw VcsException(VcsText.of(VcsMessage.NO_REMOTE))
    }

    /**
     * Credentials for talking to [remote]. A push goes to the remote's push URL, which can name a different
     * host than its fetch URL (`pushurl`, `pushInsteadOf`), so the credentials are matched to the URL the
     * transport will actually dial.
     */
    private fun credentialsFor(repo: VcsRepository, remote: String, push: Boolean = false): VcsCredentials {
        val config = runCatching { repo.remotes() }.getOrDefault(emptyList()).firstOrNull { it.name == remote }
        val url = (if (push) config?.pushUrl else config?.fetchUrl) ?: return VcsCredentials.Anonymous
        return store?.credentialsFor(url) ?: VcsCredentials.Anonymous
    }

    /**
     * `owner` and `name` of the GitHub repository the project syncs with, or null when there is none. Only a
     * github.com remote counts: the slug of a GitLab or self-hosted remote names some unrelated GitHub
     * repository, or none at all.
     */
    private suspend fun originSlug(): Pair<String, String>? {
        val url = read { repo ->
            val remote = runCatching { syncRemote(repo) }.getOrNull() ?: return@read null
            repo.remotes().firstOrNull { it.name == remote }?.fetchUrl
        } ?: return null
        if (!hostOf(url).equals("github.com", ignoreCase = true)) return null
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
            accounts.accounts().map { it.toUi(it.id == active).copy(needsSignIn = it.id in refusedAccounts) }
        }
    }

    /** The identity from the Version Control settings page, used when the repository config carries none. */
    private fun configuredIdentity(): VcsAuthor? {
        val name = ctx.manager?.preference(VcsPlugin.PREF_USER_NAME)?.trim().orEmpty()
        val email = ctx.manager?.preference(VcsPlugin.PREF_USER_EMAIL)?.trim().orEmpty()
        return if (name.isBlank() && email.isBlank()) null else VcsAuthor(name.ifBlank { email }, email)
    }

    private fun progressSink(): VcsProgress = VcsProgress { task, completed, total ->
        // The transport's step names ("Receiving objects") are English only, so the operation's own
        // localizable name stays alongside them for the UI to show.
        _activity.value = _activity.value.copy(
            busy = true,
            task = task,
            fraction = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else -1f,
        )
    }

    /** Mark a long-running command as in flight so the panel can show a progress row. */
    private suspend fun <T> busy(task: VcsMessage, body: suspend () -> T): T {
        val text = VcsText.of(task)
        _activity.value = UiVcsActivity(busy = true, task = text.english, taskText = text.toUi())
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
        failed(e.userText(), authRequired = true)
    } catch (e: Throwable) {
        log.warn("Version-control command failed", e)
        refresh()
        failed(e.userText())
    }

    /** What to tell the user about [this]: the engine's own message when it wrote one, else the raw text. */
    private fun Throwable.userText(): VcsText = when (this) {
        is VcsException -> text
        is VcsAuthException -> text
        else -> message?.takeIf { it.isNotBlank() }?.let(VcsText::literal)
            ?: VcsText.of(VcsMessage.SOMETHING_WENT_WRONG, this::class.java.simpleName)
    }

    private fun ok(message: VcsMessage, vararg args: Any): UiVcsResult =
        VcsText.of(message, *args).let { UiVcsResult(true, it.english, text = it.toUi()) }

    private fun failed(text: VcsText, authRequired: Boolean = false): UiVcsResult =
        UiVcsResult(false, text.english, authRequired = authRequired, text = text.toUi())

    private fun signInFailed(text: VcsText): UiVcsSignIn = UiVcsSignIn.Failed(text.english, text.toUi())

    private fun VcsText.toUi(): UiVcsText = UiVcsText(
        key = message?.key.orEmpty(),
        args = args.map { if (it is VcsText) it.toUi() else UiVcsText(text = it.toString()) },
        text = english,
    )

    // ---- mapping -------------------------------------------------------------------------------

    private fun VcsStatus.toUi(root: String): UiVcsStatus = UiVcsStatus(
        present = true,
        root = root,
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
