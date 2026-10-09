package dev.ide.vcs.impl

import dev.ide.vcs.VcsAuthor
import dev.ide.vcs.VcsBranch
import dev.ide.vcs.VcsChange
import dev.ide.vcs.VcsChangeArea
import dev.ide.vcs.VcsChangeKind
import dev.ide.vcs.VcsCommit
import dev.ide.vcs.VcsCommitDetail
import dev.ide.vcs.VcsCredentials
import dev.ide.vcs.VcsDiff
import dev.ide.vcs.VcsException
import dev.ide.vcs.VcsMergeResult
import dev.ide.vcs.VcsMessage
import dev.ide.vcs.VcsOperation
import dev.ide.vcs.VcsProgress
import dev.ide.vcs.VcsRemote
import dev.ide.vcs.VcsRepository
import dev.ide.vcs.VcsStash
import dev.ide.vcs.VcsStatus
import dev.ide.vcs.VcsSyncResult
import dev.ide.vcs.VcsText
import dev.ide.vcs.VcsTracking
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A working copy driven by JGit. Every call is blocking; the host serializes calls per repository and runs
 * them off the UI thread.
 */
internal class GitRepository(
    private val git: Git,
    override val root: Path,
) : VcsRepository {

    private val repo: Repository get() = git.repository

    // ---- reading -------------------------------------------------------------------------------

    override fun status(): VcsStatus = guard(msg(VcsMessage.STATUS_FAILED)) {
        val status = git.status().call()
        val changes = buildList {
            status.added.forEach { add(VcsChange(it, VcsChangeKind.ADDED, VcsChangeArea.STAGED)) }
            status.changed.forEach { add(VcsChange(it, VcsChangeKind.MODIFIED, VcsChangeArea.STAGED)) }
            status.removed.forEach { add(VcsChange(it, VcsChangeKind.DELETED, VcsChangeArea.STAGED)) }
            status.modified.forEach { add(VcsChange(it, VcsChangeKind.MODIFIED, VcsChangeArea.UNSTAGED)) }
            status.missing.forEach { add(VcsChange(it, VcsChangeKind.DELETED, VcsChangeArea.UNSTAGED)) }
            status.untracked.forEach { add(VcsChange(it, VcsChangeKind.UNTRACKED, VcsChangeArea.UNSTAGED)) }
            status.conflicting.forEach { add(VcsChange(it, VcsChangeKind.CONFLICTED, VcsChangeArea.CONFLICTED)) }
        }.sortedWith(compareBy({ it.area.ordinal }, { it.path }))

        val headRef = repo.exactRef(Constants.HEAD)
        val detached = headRef != null && !headRef.isSymbolic
        val headId = repo.resolve(Constants.HEAD)
        val branch = if (detached) null else repo.branch

        VcsStatus(
            branch = branch,
            head = headId?.let { id -> RevWalk(repo).use { walk -> walk.parseCommit(id).toVcsCommit(emptyMap()) } },
            detached = detached,
            changes = changes,
            tracking = branch?.let { trackingOf(it) } ?: VcsTracking(),
            operation = repo.repositoryState.toOperation(),
            unborn = headId == null,
        )
    }

    private fun trackingOf(branch: String): VcsTracking {
        val status = runCatching { BranchTrackingStatus.of(repo, branch) }.getOrNull() ?: return VcsTracking()
        return VcsTracking(
            upstream = Repository.shortenRefName(status.remoteTrackingBranch),
            ahead = status.aheadCount,
            behind = status.behindCount,
        )
    }

    override fun branches(includeRemote: Boolean): List<VcsBranch> = guard(msg(VcsMessage.LIST_BRANCHES_FAILED)) {
        val mode = if (includeRemote) {
            org.eclipse.jgit.api.ListBranchCommand.ListMode.ALL
        } else {
            null
        }
        val current = runCatching { repo.branch }.getOrNull()
        val refs: List<Ref> = git.branchList().apply { if (mode != null) setListMode(mode) }.call()
        refs.mapNotNull { ref ->
            val full = ref.name
            // HEAD shows up among remote refs as `refs/remotes/<remote>/HEAD`; it is a pointer, not a branch.
            if (full.endsWith("/HEAD")) return@mapNotNull null
            val remote = full.startsWith(Constants.R_REMOTES)
            val short = Repository.shortenRefName(full)
            VcsBranch(
                name = short,
                ref = full,
                remote = remote,
                current = !remote && short == current,
                tip = ref.objectId?.name,
                upstream = if (remote) null else upstreamOf(short),
            )
        }.sortedWith(compareBy({ it.remote }, { !it.current }, { it.name }))
    }

    private fun upstreamOf(branch: String): String? {
        val config = repo.config
        val remote = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE)
        val merge = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE)
        if (remote.isNullOrBlank() || merge.isNullOrBlank()) return null
        return "$remote/${Repository.shortenRefName(merge)}"
    }

    override fun remotes(): List<VcsRemote> = guard(msg(VcsMessage.LIST_REMOTES_FAILED)) {
        git.remoteList().call().map { config ->
            val fetch = config.urIs.firstOrNull()?.toString().orEmpty()
            val push = config.pushURIs.firstOrNull()?.toString() ?: fetch
            VcsRemote(config.name, fetch, push)
        }
    }

    override fun log(limit: Int, skip: Int, path: String?, ref: String?): List<VcsCommit> =
        guard(msg(VcsMessage.HISTORY_FAILED)) {
            if (repo.resolve(Constants.HEAD) == null) return@guard emptyList()
            val decorations = refDecorations()
            val command = git.log().setMaxCount(limit).setSkip(skip)
            if (!ref.isNullOrBlank()) {
                val start = repo.resolve(ref) ?: throw VcsException(msg(VcsMessage.UNKNOWN_REVISION, ref))
                command.add(start)
            }
            if (!path.isNullOrBlank()) command.addPath(path)
            command.call().map { it.toVcsCommit(decorations) }
        }

    /** Branch and tag names by the commit they point at, for the "refs on this commit" chips in history. */
    private fun refDecorations(): Map<String, List<String>> {
        val byCommit = mutableMapOf<String, MutableList<String>>()
        val db = repo.refDatabase
        for (prefix in listOf(Constants.R_HEADS, Constants.R_REMOTES, Constants.R_TAGS)) {
            for (ref in runCatching { db.getRefsByPrefix(prefix) }.getOrDefault(emptyList())) {
                // A tag ref may be annotated, in which case the peeled id is the commit it names.
                val peeled = runCatching { db.peel(ref) }.getOrNull()
                val id = (peeled?.peeledObjectId ?: ref.objectId)?.name ?: continue
                byCommit.getOrPut(id) { mutableListOf() }.add(Repository.shortenRefName(ref.name))
            }
        }
        return byCommit
    }

    override fun commitDetail(id: String): VcsCommitDetail = guard(msg(VcsMessage.READ_COMMIT_FAILED, id)) {
        val objectId = repo.resolve(id) ?: throw VcsException(msg(VcsMessage.UNKNOWN_COMMIT, id))
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(objectId)
            val parent = commit.parents.firstOrNull()?.let { walk.parseCommit(it.id) }
            val scanned = GitDiffs.changes(repo, parent?.tree, commit.tree)
            VcsCommitDetail(
                commit = commit.toVcsCommit(refDecorations()),
                changes = scanned.changes,
                insertions = scanned.insertions,
                deletions = scanned.deletions,
            )
        }
    }

    override fun diff(path: String, staged: Boolean, commitId: String?): VcsDiff =
        guard(msg(VcsMessage.DIFF_FAILED, path)) { GitDiffs.diff(repo, path, staged, commitId) }

    override fun show(path: String, ref: String): String? = guard(msg(VcsMessage.SHOW_FAILED, path, ref)) {
        val id = repo.resolve("$ref:$path") ?: return@guard null
        runCatching { repo.open(id).bytes.toString(Charsets.UTF_8) }.getOrNull()
    }

    override fun stashes(): List<VcsStash> = guard(msg(VcsMessage.LIST_STASHES_FAILED)) {
        git.stashList().call().mapIndexed { index, commit ->
            VcsStash(
                index = index,
                id = commit.name,
                message = commit.fullMessage.trim(),
                timeMs = commit.commitTime.toLong() * 1000L,
            )
        }
    }

    // ---- working tree --------------------------------------------------------------------------

    override fun stage(paths: List<String>) = guard(msg(VcsMessage.STAGE_FAILED)) {
        if (paths.isEmpty()) return@guard
        val (present, gone) = paths.partition { Files.exists(root.resolve(it)) }
        if (present.isNotEmpty()) {
            val add = git.add()
            present.forEach { add.addFilepattern(it) }
            add.call()
        }
        if (gone.isNotEmpty()) {
            // A path deleted from the working tree is staged by dropping its index entry.
            val rm = git.rm().setCached(true)
            gone.forEach { rm.addFilepattern(it) }
            rm.call()
        }
    }

    override fun unstage(paths: List<String>) = guard(msg(VcsMessage.UNSTAGE_FAILED)) {
        if (paths.isEmpty()) return@guard
        if (repo.resolve(Constants.HEAD) == null) {
            // Nothing is committed yet, so there is no HEAD to reset against; drop the index entries instead.
            val rm = git.rm().setCached(true)
            paths.forEach { rm.addFilepattern(it) }
            rm.call()
            return@guard
        }
        val reset = git.reset().setRef(Constants.HEAD)
        paths.forEach { reset.addPath(it) }
        reset.call()
    }

    override fun discard(paths: List<String>) = guard(msg(VcsMessage.DISCARD_FAILED)) {
        if (paths.isEmpty()) return@guard
        val status = git.status().call()
        // A checkout refuses an unmerged path, which would surface as JGit's bare "Unmerged path". Say what to
        // do instead.
        paths.firstOrNull { it in status.conflicting }?.let {
            throw VcsException(msg(VcsMessage.DISCARD_CONFLICTED, it))
        }
        val untracked = status.untracked
        val (fresh, tracked) = paths.partition { it in untracked }
        if (tracked.isNotEmpty()) {
            val checkout = git.checkout()
            tracked.forEach { checkout.addPath(it) }
            checkout.call()
        }
        for (path in fresh) {
            val file = root.resolve(path).toFile()
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }

    override fun markResolved(paths: List<String>) = guard(msg(VcsMessage.RESOLVE_FAILED)) {
        if (paths.isEmpty()) return@guard
        // Deleting the file is a valid resolution (the usual one for a modify/delete conflict), but `add` keeps
        // the conflict stages of a path that is gone, so a deleted path is recorded as a removal instead.
        val (gone, present) = paths.partition { !Files.exists(root.resolve(it)) }
        if (present.isNotEmpty()) {
            val add = git.add()
            present.forEach { add.addFilepattern(it) }
            add.call()
        }
        if (gone.isNotEmpty()) {
            val rm = git.rm().setCached(true)
            gone.forEach { rm.addFilepattern(it) }
            rm.call()
        }
    }

    // ---- history -------------------------------------------------------------------------------

    override fun commit(message: String, author: VcsAuthor?, amend: Boolean): VcsCommit {
        if (message.isBlank()) throw VcsException(msg(VcsMessage.COMMIT_MESSAGE_REQUIRED))
        val identity = author ?: identity()
            ?: throw VcsException(msg(VcsMessage.IDENTITY_REQUIRED))
        return guard(msg(VcsMessage.COMMIT_FAILED)) {
            val command = git.commit()
                .setMessage(message)
                .setAmend(amend)
                .setCommitter(PersonIdent(identity.name, identity.email))
            // An amend keeps the original author, as `git commit --amend` does: JGit reuses the amended
            // commit's author only when none is set, and the commit may well be someone else's.
            if (!amend) command.setAuthor(PersonIdent(identity.name, identity.email))
            val commit = command.call()
            commit.toVcsCommit(emptyMap())
        }
    }

    // ---- branches ------------------------------------------------------------------------------

    override fun createBranch(name: String, startPoint: String?, checkout: Boolean): VcsBranch {
        validateBranchName(name)
        return guard(msg(VcsMessage.CREATE_BRANCH_FAILED, name)) {
            val create = git.branchCreate().setName(name)
            if (!startPoint.isNullOrBlank()) create.setStartPoint(startPoint)
            val ref = create.call()
            if (checkout) git.checkout().setName(name).call()
            VcsBranch(
                name = Repository.shortenRefName(ref.name),
                ref = ref.name,
                remote = false,
                current = checkout,
                tip = ref.objectId?.name,
            )
        }
    }

    override fun checkout(name: String) = guard(msg(VcsMessage.CHECKOUT_FAILED, name)) {
        if (repo.exactRef(Constants.R_HEADS + name) != null) {
            git.checkout().setName(name).call()
            return@guard
        }
        val remoteRef = repo.exactRef(Constants.R_REMOTES + name)
        if (remoteRef != null) {
            // `origin/feature` becomes a local `feature` tracking it, the same shape `git switch` produces.
            val local = name.substringAfter('/', name)
            val existing = repo.exactRef(Constants.R_HEADS + local)
            if (existing != null) {
                git.checkout().setName(local).call()
            } else {
                git.checkout()
                    .setName(local)
                    .setCreateBranch(true)
                    .setStartPoint(name)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                    .call()
            }
            return@guard
        }
        // Anything else is a tag or commit id, which checks out with a detached HEAD.
        repo.resolve(name) ?: throw VcsException(msg(VcsMessage.UNKNOWN_BRANCH_OR_REVISION, name))
        git.checkout().setName(name).call()
    }

    override fun deleteBranch(name: String, force: Boolean) = guard(msg(VcsMessage.DELETE_BRANCH_FAILED, name)) {
        if (name == runCatching { repo.branch }.getOrNull()) {
            throw VcsException(msg(VcsMessage.DELETE_CURRENT_BRANCH, name))
        }
        git.branchDelete().setBranchNames(name).setForce(force).call()
        Unit
    }

    override fun renameBranch(from: String, to: String) {
        validateBranchName(to)
        guard(msg(VcsMessage.RENAME_BRANCH_FAILED, from, to)) {
            git.branchRename().setOldName(from).setNewName(to).call()
            Unit
        }
    }

    override fun merge(name: String): VcsMergeResult = guard(msg(VcsMessage.MERGE_FAILED, name)) {
        val ref = repo.findRef(name) ?: throw VcsException(msg(VcsMessage.UNKNOWN_BRANCH, name))
        try {
            git.merge().include(ref).call().toVcsMergeResult()
        } catch (e: CheckoutConflictException) {
            // A fast-forward blocked by local edits throws where a real merge would return CHECKOUT_CONFLICT.
            VcsMergeResult(VcsMergeResult.Status.FAILED, emptyList(), blockedByLocalEdits(e.conflictingPaths.orEmpty()))
        }
    }

    override fun abortMerge() = guard(msg(VcsMessage.ABORT_FAILED)) {
        when (repo.repositoryState) {
            RepositoryState.REBASING_MERGE, RepositoryState.REBASING_INTERACTIVE -> {
                git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
                return@guard
            }

            else -> Unit
        }
        // A hard reset clears MERGE_HEAD and the merge message, but it also reverts every file, and JGit lets a
        // merge start while files it does not touch carry uncommitted edits. `git merge --abort` keeps those,
        // so they are read out first and written back after the reset.
        val kept = editsOutsideMerge()
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
        for ((path, bytes) in kept) {
            val file = root.resolve(path)
            if (bytes == null) {
                Files.deleteIfExists(file)
            } else {
                file.parent?.let { Files.createDirectories(it) }
                Files.write(file, bytes)
            }
        }
    }

    /**
     * The working-tree state of every changed file the in-progress merge never touched, as bytes, or null for a
     * file the user deleted. A file counts as touched when the merged-in side changed it since the merge base,
     * which covers both the cleanly merged files and the conflicted ones.
     */
    private fun editsOutsideMerge(): Map<String, ByteArray?> {
        val heads = runCatching { repo.readMergeHeads() }.getOrNull().orEmpty()
        val head = repo.resolve(Constants.HEAD) ?: return emptyMap()
        if (heads.isEmpty()) return emptyMap()
        val touched = mutableSetOf<String>()
        RevWalk(repo).use { walk ->
            for (id in heads) {
                walk.reset()
                walk.setRevFilter(RevFilter.MERGE_BASE)
                val ours = walk.parseCommit(head)
                val theirs = walk.parseCommit(id)
                walk.markStart(ours)
                walk.markStart(theirs)
                val base = walk.next()
                TreeWalk(repo).use { tree ->
                    tree.isRecursive = true
                    tree.filter = TreeFilter.ANY_DIFF
                    if (base != null) tree.addTree(walk.parseCommit(base).tree) else tree.addTree(EmptyTreeIterator())
                    tree.addTree(theirs.tree)
                    while (tree.next()) touched += tree.pathString
                }
            }
        }
        val status = git.status().call()
        val edited = status.modified + status.changed + status.added + status.missing + status.removed
        return edited.filter { it !in touched && it !in status.conflicting }.associateWith { path ->
            val file = root.resolve(path)
            if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
        }
    }

    // ---- remotes -------------------------------------------------------------------------------

    override fun addRemote(name: String, url: String) = guard(msg(VcsMessage.ADD_REMOTE_FAILED, name)) {
        val uri = URIish(url)
        if (git.remoteList().call().any { it.name == name }) {
            git.remoteSetUrl().setRemoteName(name).setRemoteUri(uri).call()
        } else {
            git.remoteAdd().setName(name).setUri(uri).call()
        }
        Unit
    }

    override fun removeRemote(name: String) = guard(msg(VcsMessage.REMOVE_REMOTE_FAILED, name)) {
        git.remoteRemove().setRemoteName(name).call()
        Unit
    }

    // ---- stash ---------------------------------------------------------------------------------

    override fun stashPush(message: String, includeUntracked: Boolean): Boolean =
        guard(msg(VcsMessage.STASH_FAILED)) {
            val create = git.stashCreate().setIncludeUntracked(includeUntracked)
            if (message.isNotBlank()) create.setWorkingDirectoryMessage(message)
            val stashed = create.call() ?: return@guard false
            // stashCreate records the commit but leaves the working tree alone; the reset is what makes it
            // behave like `git stash push`.
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
            stashed.name.isNotEmpty()
        }

    override fun stashApply(index: Int, drop: Boolean) = guard(msg(VcsMessage.STASH_APPLY_FAILED)) {
        git.stashApply().setStashRef("stash@{$index}").call()
        if (drop) git.stashDrop().setStashRef(index).call()
        Unit
    }

    override fun stashDrop(index: Int) = guard(msg(VcsMessage.STASH_DROP_FAILED)) {
        git.stashDrop().setStashRef(index).call()
        Unit
    }

    // ---- network -------------------------------------------------------------------------------

    override fun fetch(remote: String, auth: VcsCredentials?, progress: VcsProgress): VcsSyncResult {
        return try {
            val result = git.fetch()
                .setRemote(remote)
                .setCredentialsProvider(auth.toJGit())
                .setProgressMonitor(GitProgressMonitor(progress))
                .setRemoveDeletedRefs(true)
                .setTimeout(NETWORK_TIMEOUT_SECONDS)
                .call()
            val updates = result.trackingRefUpdates.map { "${Repository.shortenRefName(it.localName)}: ${it.result}" }
            VcsSyncResult(ok = true, message = result.messages.trim(), updates = updates)
        } catch (e: Throwable) {
            throw e.asVcsFailure(msg(VcsMessage.FETCH_FAILED, remote))
        }
    }

    override fun pull(remote: String, auth: VcsCredentials?, progress: VcsProgress): VcsSyncResult {
        // A branch that tracks nothing has no `branch.<name>.merge` entry, and JGit refuses to pull without one.
        // Pull the same-named branch instead and record it as the upstream, as a first push would.
        val untracked = runCatching { repo.branch }.getOrNull()?.takeIf { upstreamOf(it) == null }
        return try {
            val command = git.pull()
                .setRemote(remote)
                .setCredentialsProvider(auth.toJGit())
                .setProgressMonitor(GitProgressMonitor(progress))
            command.setTimeout(NETWORK_TIMEOUT_SECONDS)
            if (untracked != null) command.setRemoteBranchName(untracked)
            val result = command.call()
            if (untracked != null && result.isSuccessful) recordUpstream(untracked, remote)
            // A repository set to pull with rebase reports through rebaseResult and leaves mergeResult null.
            val merge = result.mergeResult?.toVcsMergeResult() ?: result.rebaseResult?.toVcsMergeResult()
            VcsSyncResult(
                ok = result.isSuccessful,
                text = merge?.text ?: VcsText.literal(result.fetchResult?.messages?.trim().orEmpty()),
                merge = merge,
            )
        } catch (e: CheckoutConflictException) {
            val message = blockedByLocalEdits(e.conflictingPaths.orEmpty())
            VcsSyncResult(ok = false, text = message, merge = VcsMergeResult(VcsMergeResult.Status.FAILED, emptyList(), message))
        } catch (e: Throwable) {
            throw e.asVcsFailure(msg(VcsMessage.PULL_FAILED, remote))
        }
    }

    override fun push(
        remote: String,
        branch: String?,
        force: Boolean,
        setUpstream: Boolean,
        auth: VcsCredentials?,
        progress: VcsProgress,
    ): VcsSyncResult {
        // `repo.branch` is the commit id on a detached HEAD, so the full name is what tells the two apart.
        val target = branch ?: runCatching { repo.fullBranch }.getOrNull()
            ?.takeIf { it.startsWith(Constants.R_HEADS) }
            ?.removePrefix(Constants.R_HEADS)
            ?: throw VcsException(msg(VcsMessage.PUSH_DETACHED))
        if (repo.exactRef(Constants.R_HEADS + target)?.objectId == null) {
            throw VcsException(msg(VcsMessage.PUSH_UNBORN, target))
        }
        return try {
            val results = git.push()
                .setRemote(remote)
                .setRefSpecs(RefSpec("${Constants.R_HEADS}$target:${Constants.R_HEADS}$target"))
                .setForce(force)
                .setCredentialsProvider(auth.toJGit())
                .setProgressMonitor(GitProgressMonitor(progress))
                .setTimeout(NETWORK_TIMEOUT_SECONDS)
                .call()

            val updates = mutableListOf<String>()
            val problems = mutableListOf<VcsText>()
            for (result in results) {
                for (update in result.remoteUpdates) {
                    val name = Repository.shortenRefName(update.remoteName)
                    updates += "$name: ${update.status}${update.message?.let { " ($it)" }.orEmpty()}"
                    if (update.status != RemoteRefUpdate.Status.OK &&
                        update.status != RemoteRefUpdate.Status.UP_TO_DATE
                    ) {
                        problems += update.explain(name)
                    }
                }
            }
            val ok = problems.isEmpty()
            if (ok && setUpstream && upstreamOf(target) == null) recordUpstream(target, remote)
            VcsSyncResult(
                ok = ok,
                // One ref is pushed at a time, so there is at most one problem to report; the rest is in updates.
                text = problems.firstOrNull() ?: VcsText.literal(""),
                updates = updates,
            )
        } catch (e: Throwable) {
            throw e.asVcsFailure(msg(VcsMessage.PUSH_FAILED, remote))
        }
    }

    private fun recordUpstream(branch: String, remote: String) {
        val config = repo.config
        config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE, remote)
        config.setString(
            ConfigConstants.CONFIG_BRANCH_SECTION,
            branch,
            ConfigConstants.CONFIG_KEY_MERGE,
            Constants.R_HEADS + branch,
        )
        config.save()
    }

    // ---- config --------------------------------------------------------------------------------

    override fun identity(): VcsAuthor? {
        val config = repo.config
        val name = config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME)
        val email = config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL)
        if (name.isNullOrBlank() && email.isNullOrBlank()) return null
        return VcsAuthor(name.orEmpty().ifBlank { email.orEmpty() }, email.orEmpty())
    }

    override fun setIdentity(author: VcsAuthor) = guard(msg(VcsMessage.IDENTITY_SAVE_FAILED)) {
        val config = repo.config
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME, author.name)
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, author.email)
        config.save()
    }

    override fun ignore(patterns: List<String>) = guard(msg(VcsMessage.IGNORE_FAILED)) {
        if (patterns.isEmpty()) return@guard
        val file = root.resolve(".gitignore")
        val existing = if (Files.exists(file)) Files.readAllLines(file).map { it.trim() }.toSet() else emptySet()
        val additions = patterns.map { it.trim() }.filter { it.isNotEmpty() && it !in existing }
        if (additions.isEmpty()) return@guard
        val text = buildString {
            if (Files.exists(file) && Files.size(file) > 0) {
                val last = Files.readAllBytes(file).lastOrNull()
                if (last != '\n'.code.toByte()) append('\n')
            }
            additions.forEach { append(it).append('\n') }
        }
        Files.write(
            file,
            text.toByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
        Unit
    }

    override fun close() {
        runCatching { git.close() }
        // A Git built around an existing Repository (how GitProvider.open makes one) does not close it, which
        // would leak its pack files and handles on every project switch. Closing twice is harmless.
        runCatching { repo.close() }
    }

    // ---- mapping -------------------------------------------------------------------------------

    private fun RevCommit.toVcsCommit(decorations: Map<String, List<String>>): VcsCommit {
        val full = fullMessage.trim()
        val summary = shortMessage.trim()
        return VcsCommit(
            id = name,
            shortId = name.take(SHORT_ID_LENGTH),
            summary = summary,
            body = full.removePrefix(summary).trim(),
            author = authorIdent.toVcsAuthor(),
            committer = committerIdent.toVcsAuthor(),
            timeMs = authorIdent?.`when`?.time ?: (commitTime.toLong() * 1000L),
            parents = parents.map { it.name },
            refs = decorations[name].orEmpty(),
        )
    }

    private fun PersonIdent?.toVcsAuthor(): VcsAuthor =
        VcsAuthor(this?.name.orEmpty().ifBlank { this?.emailAddress.orEmpty() }, this?.emailAddress.orEmpty())

    private fun MergeResult.toVcsMergeResult(): VcsMergeResult {
        val status = when (mergeStatus) {
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> VcsMergeResult.Status.ALREADY_UP_TO_DATE
            MergeResult.MergeStatus.FAST_FORWARD,
            MergeResult.MergeStatus.FAST_FORWARD_SQUASHED,
            -> VcsMergeResult.Status.FAST_FORWARD

            MergeResult.MergeStatus.MERGED,
            MergeResult.MergeStatus.MERGED_SQUASHED,
            MergeResult.MergeStatus.MERGED_NOT_COMMITTED,
            MergeResult.MergeStatus.MERGED_SQUASHED_NOT_COMMITTED,
            -> VcsMergeResult.Status.MERGED

            MergeResult.MergeStatus.CONFLICTING -> VcsMergeResult.Status.CONFLICTS

            MergeResult.MergeStatus.ABORTED -> VcsMergeResult.Status.ABORTED
            else -> VcsMergeResult.Status.FAILED
        }
        val conflicts = if (status == VcsMergeResult.Status.CONFLICTS) conflicts?.keys?.toList().orEmpty() else emptyList()
        // CHECKOUT_CONFLICT and FAILED both mean the merge never started because uncommitted edits sit on files it
        // would change. The tree is untouched, so these are not conflicts to resolve but edits to commit or stash.
        val blocking = (checkoutConflicts.orEmpty() + failingPaths?.keys.orEmpty()).distinct()
        val message = when {
            status == VcsMergeResult.Status.CONFLICTS -> msg(VcsMessage.MERGE_CONFLICTS, conflicts.size)
            blocking.isNotEmpty() -> blockedByLocalEdits(blocking)
            status == VcsMergeResult.Status.ALREADY_UP_TO_DATE -> msg(VcsMessage.ALREADY_UP_TO_DATE)
            status == VcsMergeResult.Status.FAST_FORWARD -> msg(VcsMessage.FAST_FORWARDED)
            status == VcsMergeResult.Status.MERGED -> msg(VcsMessage.MERGED)
            else -> msg(VcsMessage.MERGE_INCOMPLETE, mergeStatus)
        }
        return VcsMergeResult(status, conflicts, message)
    }

    private fun RebaseResult.toVcsMergeResult(): VcsMergeResult = when (status) {
        RebaseResult.Status.OK,
        RebaseResult.Status.FAST_FORWARD,
        RebaseResult.Status.UP_TO_DATE,
        RebaseResult.Status.NOTHING_TO_COMMIT,
        -> VcsMergeResult(
            if (status == RebaseResult.Status.UP_TO_DATE) {
                VcsMergeResult.Status.ALREADY_UP_TO_DATE
            } else {
                VcsMergeResult.Status.MERGED
            },
            emptyList(),
            if (status == RebaseResult.Status.UP_TO_DATE) msg(VcsMessage.ALREADY_UP_TO_DATE) else msg(VcsMessage.REBASED),
        )

        RebaseResult.Status.STOPPED, RebaseResult.Status.EDIT -> {
            val conflicted = conflicts.orEmpty()
            VcsMergeResult(
                VcsMergeResult.Status.CONFLICTS,
                conflicted,
                msg(VcsMessage.REBASE_STOPPED),
            )
        }

        RebaseResult.Status.CONFLICTS, RebaseResult.Status.UNCOMMITTED_CHANGES ->
            VcsMergeResult(VcsMergeResult.Status.FAILED, emptyList(), blockedByLocalEdits(conflicts.orEmpty() + uncommittedChanges.orEmpty()))

        else -> VcsMergeResult(VcsMergeResult.Status.FAILED, emptyList(), msg(VcsMessage.PULL_INCOMPLETE, status))
    }

    private fun blockedByLocalEdits(paths: List<String>): VcsText {
        val shown = paths.distinct()
        val names: Any = when {
            shown.isEmpty() -> msg(VcsMessage.SOME_FILES)
            shown.size > 3 -> msg(VcsMessage.AND_MORE, shown.take(3).joinToString(", "), shown.size - 3)
            else -> shown.joinToString(", ")
        }
        return msg(VcsMessage.BLOCKED_BY_LOCAL_EDITS, names)
    }

    /** Why the remote refused [this] ref, in words a user can act on. */
    private fun RemoteRefUpdate.explain(name: String): VcsText = when (status) {
        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
            msg(VcsMessage.PUSH_BEHIND, name)

        RemoteRefUpdate.Status.REJECTED_NODELETE -> msg(VcsMessage.PUSH_NO_DELETE, name)
        else -> msg(VcsMessage.WITH_REASON, name, message?.takeIf { it.isNotBlank() } ?: status.toString())
    }

    private fun RepositoryState.toOperation(): VcsOperation = when (this) {
        RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED -> VcsOperation.MERGE
        RepositoryState.REBASING,
        RepositoryState.REBASING_REBASING,
        RepositoryState.REBASING_MERGE,
        RepositoryState.REBASING_INTERACTIVE,
        RepositoryState.APPLY,
        -> VcsOperation.REBASE

        RepositoryState.CHERRY_PICKING, RepositoryState.CHERRY_PICKING_RESOLVED -> VcsOperation.CHERRY_PICK
        RepositoryState.REVERTING, RepositoryState.REVERTING_RESOLVED -> VcsOperation.REVERT
        RepositoryState.BISECTING -> VcsOperation.BISECT
        else -> VcsOperation.NONE
    }

    /**
     * Run a JGit call, turning any failure into the neutral exception pair with a message fit to show.
     *
     * Catches [Throwable] for the reason [GitProvider] does: JGit is a desktop-JVM library, so a missing
     * runtime method surfaces as a [LinkageError], which is not an [Exception].
     */
    private inline fun <T> guard(what: VcsText, body: () -> T): T = try {
        body()
    } catch (e: VcsException) {
        throw e
    } catch (e: Throwable) {
        throw e.asVcsFailure(what, network = false)
    }

    private fun validateBranchName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw VcsException(msg(VcsMessage.BRANCH_NAME_REQUIRED))
        if (!Repository.isValidRefName(Constants.R_HEADS + trimmed)) {
            throw VcsException(msg(VcsMessage.BRANCH_NAME_INVALID, trimmed))
        }
    }

    private companion object {
        const val SHORT_ID_LENGTH = 7

        /** Seconds a fetch, pull, or push may sit without the server answering before it fails. Without it a
         *  dead mobile connection blocks forever, and the host serializes every Git call behind that one. */
        const val NETWORK_TIMEOUT_SECONDS = 60
    }
}
