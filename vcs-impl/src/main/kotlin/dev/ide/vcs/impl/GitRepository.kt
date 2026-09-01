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
import dev.ide.vcs.VcsOperation
import dev.ide.vcs.VcsProgress
import dev.ide.vcs.VcsRemote
import dev.ide.vcs.VcsRepository
import dev.ide.vcs.VcsStash
import dev.ide.vcs.VcsStatus
import dev.ide.vcs.VcsSyncResult
import dev.ide.vcs.VcsTracking
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
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

    override fun status(): VcsStatus = guard("Could not read the repository status") {
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

    override fun branches(includeRemote: Boolean): List<VcsBranch> = guard("Could not list branches") {
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

    override fun remotes(): List<VcsRemote> = guard("Could not list remotes") {
        git.remoteList().call().map { config ->
            val fetch = config.urIs.firstOrNull()?.toString().orEmpty()
            val push = config.pushURIs.firstOrNull()?.toString() ?: fetch
            VcsRemote(config.name, fetch, push)
        }
    }

    override fun log(limit: Int, skip: Int, path: String?, ref: String?): List<VcsCommit> =
        guard("Could not read the commit history") {
            if (repo.resolve(Constants.HEAD) == null) return@guard emptyList()
            val decorations = refDecorations()
            val command = git.log().setMaxCount(limit).setSkip(skip)
            if (!ref.isNullOrBlank()) {
                val start = repo.resolve(ref) ?: throw VcsException("Unknown revision $ref")
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

    override fun commitDetail(id: String): VcsCommitDetail = guard("Could not read commit $id") {
        val objectId = repo.resolve(id) ?: throw VcsException("Unknown commit $id")
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
        guard("Could not diff $path") { GitDiffs.diff(repo, path, staged, commitId) }

    override fun show(path: String, ref: String): String? = guard("Could not read $path at $ref") {
        val id = repo.resolve("$ref:$path") ?: return@guard null
        runCatching { repo.open(id).bytes.toString(Charsets.UTF_8) }.getOrNull()
    }

    override fun stashes(): List<VcsStash> = guard("Could not list stashes") {
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

    override fun stage(paths: List<String>) = guard("Could not stage the selected files") {
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

    override fun unstage(paths: List<String>) = guard("Could not unstage the selected files") {
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

    override fun discard(paths: List<String>) = guard("Could not discard the selected changes") {
        if (paths.isEmpty()) return@guard
        val untracked = git.status().call().untracked
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

    override fun markResolved(paths: List<String>) = guard("Could not mark the conflicts resolved") {
        if (paths.isEmpty()) return@guard
        val add = git.add()
        paths.forEach { add.addFilepattern(it) }
        add.call()
    }

    // ---- history -------------------------------------------------------------------------------

    override fun commit(message: String, author: VcsAuthor?, amend: Boolean): VcsCommit {
        if (message.isBlank()) throw VcsException("Enter a commit message")
        val identity = author ?: identity()
            ?: throw VcsException("Set your name and email in Settings before committing")
        return guard("Could not create the commit") {
            val commit = git.commit()
                .setMessage(message)
                .setAmend(amend)
                .setAuthor(PersonIdent(identity.name, identity.email))
                .setCommitter(PersonIdent(identity.name, identity.email))
                .call()
            commit.toVcsCommit(emptyMap())
        }
    }

    // ---- branches ------------------------------------------------------------------------------

    override fun createBranch(name: String, startPoint: String?, checkout: Boolean): VcsBranch {
        validateBranchName(name)
        return guard("Could not create branch $name") {
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

    override fun checkout(name: String) = guard("Could not switch to $name") {
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
        repo.resolve(name) ?: throw VcsException("Unknown branch or revision $name")
        git.checkout().setName(name).call()
    }

    override fun deleteBranch(name: String, force: Boolean) = guard("Could not delete branch $name") {
        if (name == runCatching { repo.branch }.getOrNull()) {
            throw VcsException("$name is the current branch. Switch to another branch first.")
        }
        git.branchDelete().setBranchNames(name).setForce(force).call()
        Unit
    }

    override fun renameBranch(from: String, to: String) {
        validateBranchName(to)
        guard("Could not rename $from to $to") {
            git.branchRename().setOldName(from).setNewName(to).call()
            Unit
        }
    }

    override fun merge(name: String): VcsMergeResult = guard("Could not merge $name") {
        val ref = repo.findRef(name) ?: throw VcsException("Unknown branch $name")
        git.merge().include(ref).call().toVcsMergeResult()
    }

    override fun abortMerge() = guard("Could not abort the merge") {
        // ResetCommand clears MERGE_HEAD and the merge message as part of a hard reset, which is what
        // `git merge --abort` does once the tree is back at HEAD.
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
        Unit
    }

    // ---- remotes -------------------------------------------------------------------------------

    override fun addRemote(name: String, url: String) = guard("Could not add remote $name") {
        val uri = URIish(url)
        if (git.remoteList().call().any { it.name == name }) {
            git.remoteSetUrl().setRemoteName(name).setRemoteUri(uri).call()
        } else {
            git.remoteAdd().setName(name).setUri(uri).call()
        }
        Unit
    }

    override fun removeRemote(name: String) = guard("Could not remove remote $name") {
        git.remoteRemove().setRemoteName(name).call()
        Unit
    }

    // ---- stash ---------------------------------------------------------------------------------

    override fun stashPush(message: String, includeUntracked: Boolean): Boolean =
        guard("Could not stash the changes") {
            val create = git.stashCreate().setIncludeUntracked(includeUntracked)
            if (message.isNotBlank()) create.setWorkingDirectoryMessage(message)
            val stashed = create.call() ?: return@guard false
            // stashCreate records the commit but leaves the working tree alone; the reset is what makes it
            // behave like `git stash push`.
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
            stashed.name.isNotEmpty()
        }

    override fun stashApply(index: Int, drop: Boolean) = guard("Could not apply the stash") {
        git.stashApply().setStashRef("stash@{$index}").call()
        if (drop) git.stashDrop().setStashRef(index).call()
        Unit
    }

    override fun stashDrop(index: Int) = guard("Could not drop the stash") {
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
                .call()
            val updates = result.trackingRefUpdates.map { "${Repository.shortenRefName(it.localName)}: ${it.result}" }
            VcsSyncResult(ok = true, message = result.messages.trim(), updates = updates)
        } catch (e: Throwable) {
            throw e.asVcsFailure("Could not fetch from $remote")
        }
    }

    override fun pull(remote: String, auth: VcsCredentials?, progress: VcsProgress): VcsSyncResult {
        return try {
            val result = git.pull()
                .setRemote(remote)
                .setCredentialsProvider(auth.toJGit())
                .setProgressMonitor(GitProgressMonitor(progress))
                .call()
            val merge = result.mergeResult?.toVcsMergeResult()
            VcsSyncResult(
                ok = result.isSuccessful,
                message = merge?.message ?: result.fetchResult?.messages?.trim().orEmpty(),
                merge = merge,
            )
        } catch (e: Throwable) {
            throw e.asVcsFailure("Could not pull from $remote")
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
        val target = branch ?: runCatching { repo.branch }.getOrNull()
            ?: throw VcsException("HEAD is detached, so there is no branch to push")
        return try {
            val results = git.push()
                .setRemote(remote)
                .setRefSpecs(RefSpec("${Constants.R_HEADS}$target:${Constants.R_HEADS}$target"))
                .setForce(force)
                .setCredentialsProvider(auth.toJGit())
                .setProgressMonitor(GitProgressMonitor(progress))
                .call()

            val updates = mutableListOf<String>()
            var ok = true
            for (result in results) {
                for (update in result.remoteUpdates) {
                    val name = Repository.shortenRefName(update.remoteName)
                    updates += "$name: ${update.status}${update.message?.let { " ($it)" }.orEmpty()}"
                    if (update.status != RemoteRefUpdate.Status.OK &&
                        update.status != RemoteRefUpdate.Status.UP_TO_DATE
                    ) {
                        ok = false
                    }
                }
            }
            if (ok && setUpstream && upstreamOf(target) == null) recordUpstream(target, remote)
            VcsSyncResult(
                ok = ok,
                message = if (ok) "" else updates.joinToString("\n"),
                updates = updates,
            )
        } catch (e: Throwable) {
            throw e.asVcsFailure("Could not push to $remote")
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

    override fun setIdentity(author: VcsAuthor) = guard("Could not save the commit identity") {
        val config = repo.config
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME, author.name)
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, author.email)
        config.save()
    }

    override fun ignore(patterns: List<String>) = guard("Could not update .gitignore") {
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

            MergeResult.MergeStatus.CONFLICTING,
            MergeResult.MergeStatus.CHECKOUT_CONFLICT,
            -> VcsMergeResult.Status.CONFLICTS

            MergeResult.MergeStatus.ABORTED -> VcsMergeResult.Status.ABORTED
            else -> VcsMergeResult.Status.FAILED
        }
        val conflicts = conflicts?.keys?.toList()
            ?: checkoutConflicts?.toList()
            ?: emptyList()
        val message = when (status) {
            VcsMergeResult.Status.CONFLICTS ->
                "Merge left ${conflicts.size} file(s) conflicted. Resolve them, then commit."

            VcsMergeResult.Status.ALREADY_UP_TO_DATE -> "Already up to date"
            VcsMergeResult.Status.FAST_FORWARD -> "Fast-forwarded"
            VcsMergeResult.Status.MERGED -> "Merged"
            else -> mergeStatus.toString()
        }
        return VcsMergeResult(status, conflicts, message)
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
    private inline fun <T> guard(what: String, body: () -> T): T = try {
        body()
    } catch (e: VcsException) {
        throw e
    } catch (e: Throwable) {
        throw e.asVcsFailure(what)
    }

    private fun validateBranchName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw VcsException("Enter a branch name")
        if (!Repository.isValidRefName(Constants.R_HEADS + trimmed)) {
            throw VcsException("\"$trimmed\" is not a valid branch name")
        }
    }

    private companion object {
        const val SHORT_ID_LENGTH = 7
    }
}
