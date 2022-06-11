package de.prinova.git.usecases

import java.io.File
import java.util.Date

import kotlinx.coroutines.*

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit

import de.prinova.git.model.*
import de.prinova.git.model.Gitter


//TODO: Add merge-conflict logic
//TODO: Add logic for commit-reset, -revert and -restore (in this order)
//TODO: Add commit amend
//TODO: rename branch
//TODO: put author into log formating

typealias LogList = List<RevCommit>

const val GIT_SUFFIX = ".git"

fun String.createGitRepoWith (commiter: Author): Gitter =
	createRepo()
	.createGit()
	.addProjectFiles()
	.commiting(commiter, "Initial commit")

fun openGitAt(path: String): Gitter =
	path.openRepo()
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
	
private fun Repository.createGit(): Gitter = Gitter(Git(this))

private fun Gitter.addProjectFiles(): Gitter = apply {
		git.add().addFilepattern(".").call()
		git.add().addFilepattern(".").setUpdate(true).call()
	}

fun Gitter.commiting(commiter: Author, msg: String): Gitter = apply {
	addProjectFiles()
	runBlocking {
		launch(Dispatchers.Default) {
			commit(commiter, msg)
		}
	}
}

private suspend fun Gitter.commit(person: Author, msg: String): Gitter = apply { 
	git.commit()
	.setCommitter(person.of())
	.setAuthor(person.of())
	.setMessage(msg)
	.setAll(true)
	.call()
}

fun Gitter.getLog(): String = 
	getLogList()
	.formatLog()
	
private fun Gitter.getLogList(): LogList =
	git.log()
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
	
fun Gitter.createBranch(branch: String): Gitter = apply {
	runBlocking {
		launch(Dispatchers.Default) {
			git.branchCreate()
			.setName(branch)
			.call()
		}
	}
}
	
fun Gitter.getBranch(): String = 
	git.getRepository()
	.getBranch()

fun Gitter.getBranchList(): List<String> =
	git.branchList()
	.call()
	.map { ref ->
		Repository.shortenRefName(ref.getName())
	}
	
fun Gitter.checkout(branch: String): Gitter = apply {
	addProjectFiles()
	runBlocking {
		launch(Dispatchers.Default) {
			git.checkout()
			.setName( branch )
			.call()
		}
	}
}

fun Gitter.mergeBranch(branch: String): Gitter = apply {
	addProjectFiles()
	runBlocking {
		launch(Dispatchers.Default) {
			mergeWith(branch)
			.setCommit(true)
			.call()
		}
	}
}

suspend fun Gitter.mergeWith(branch: String): MergeCommand = 
	git.merge()
	.include(resolve(branch))

suspend fun Gitter.resolve(branch: String): ObjectId = 
	git.getRepository()
	.resolve(branch)


fun Gitter.deleteBranch(branch: String): Gitter = apply {
	runBlocking {
		launch(Dispatchers.Default) {
			git.branchDelete()
			.setBranchNames(branch)
			.setForce(true)
			.call()
		}
	}
}

fun isGitRepoAt(filePath: String) = File("$filePath/$GIT_SUFFIX").exists()
	
fun Gitter.dispose() {
	git.close()
}	