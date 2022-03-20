package de.prinova.git.usecases

import java.io.File
import java.util.Date

import kotlinx.coroutines.*

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit

import de.prinova.git.model.*

typealias LogList = List<RevCommit>

/*internal fun Git.init(filePath: String, commiter: Author): Git =
	filePath.initializeRepo(commiter)
*/

internal fun String.initializeRepo (commiter: Author): Git =
	createRepo()
	.createGit()
	.addProjectFiles()
	.commiting(commiter, "Initial commit")

internal fun String.openGit(): Git =
	openRepo()
	.createGit()
	
private fun String.openRepo(): Repository =
	FileRepositoryBuilder()
	.setGitDir(File("${this}/.git"))
	.build()

private fun String.createRepo (): Repository = 
	FileRepositoryBuilder
	.create ( File("${this}/.git") )
	.apply {
		create()
	}
	
private fun Repository.createGit(): Git = Git(this)

internal fun Git.addProjectFiles(): Git = 
	apply {
		add().addFilepattern(".").call()
	}

internal fun Git.commiting(commiter: Author, msg: String): Git = apply {
	runBlocking {
		launch(Dispatchers.Default) {
			commit(commiter, msg)
		}
	}
}

private fun Git.commit(person: Author, msg: String): Git = apply { 
	commit()
	.setCommitter(person.of())
	.setAuthor(person.of())
	.setMessage(msg)
	.setAll(true)
	.call()
}

internal fun Git.getLog(): String = 
	getLogList()
	.formatLog()
	
private fun Git.getLogList(): LogList =
	log()
	.call()	
	.toList()

private fun LogList.formatLog(): String =
	map { elem ->
		val type = Constants.typeString(elem.getType()).uppercase()
		val name = "${elem.name().substring(8)}..."
		val time = Date( elem.commitTime.toLong() * 1000 )
		val msg = elem.getFullMessage()
		"\t${type}\n${name}\n${time}\n\n${msg}\n"
	}.joinToString("\n")	
	
internal fun Git.createBranch(branch: String): Git = apply {
	runBlocking {
		launch(Dispatchers.Default) {
			branchCreate()
			.setName(branch)
			.call()
		}
	}
}
	
internal fun Git.getBranch(): String = 
	getRepository()
	.getBranch()

internal fun Git.getBranchList(): List<String> =
	branchList()
	.call()
	.map { ref ->
		Repository.shortenRefName(ref.getName())
	}
	
internal fun Git.checkout(branch: String): Git = apply {
	addProjectFiles()
	runBlocking {
		launch(Dispatchers.Default) {
			checkout()
			.setName( branch )
			.call()
		}
	}
}

internal fun Git.mergeBranch(branch: String): Git = apply {
	runBlocking {
		launch(Dispatchers.Default) {
			addProjectFiles()
			.mergeWith(branch)
			.setCommit(true)
			.call()
		}
	}
}

fun Git.mergeWith(branch: String): MergeCommand = 
	merge()
	.include(resolve(branch))

fun Git.resolve(branch: String): ObjectId = 
	getRepository()
	.resolve(branch)


internal fun Git.deleteBranch(branch: String): Git = apply {
	runBlocking {
		launch(Dispatchers.Default) {
			branchDelete()
			.setBranchNames(branch)
			.call()
		}
	}
}

internal fun initRepo(filePath: String) = File("$filePath/.git").exists()
	
internal fun Git.destroy() {
	close()
}	