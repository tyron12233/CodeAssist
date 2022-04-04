package com.tyron.code.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

import de.prinova.git.model.*
import de.prinova.git.usecases.*

import com.tyron.common.logging.IdeLog

lateinit var git: Gitter

class GitViewModel : ViewModel() {
	
	private val _projectPath = MutableLiveData<String>()
	val projectPath: LiveData<String> = _projectPath
	
	private val _hasRepo = MutableLiveData<Boolean>()
	val hasRepo: LiveData<Boolean> = _hasRepo
	
	fun getPath(): LiveData<String> = _projectPath
	
	fun getRepo(): LiveData<Boolean> = _hasRepo
	
	fun setPath(newPath: String) {
		_projectPath.value = newPath
		_hasRepo.value = initRepo(newPath)
		if (_hasRepo.value == true) {
			git = newPath.openGit()
		}
	}
	
	fun getLog(): String = if(::git.isInitialized) git.getLog() else ""
	
	fun getBranchList(): List<String> = if(::git.isInitialized) git.getBranchList() else listOf("")
	
	fun initializeRepo (commiter: Author) {
		_projectPath.value?.initializeRepo(commiter)
	}
	
	fun commiting(commiter: Author, msg: String): Gitter = git.commiting(commiter, msg)
	
	fun createBranch(branch: String): Gitter = git.createBranch(branch)
	
	fun mergeBranch(branch: String): Gitter = git.mergeBranch(branch)
	
	fun getBranch(): String = git.getBranch()
	
	fun deleteBranch(branch: String): Gitter = git.deleteBranch(branch)
	
	fun checkout(branch: String) = if(::git.isInitialized) git.checkout(branch) else {}
	
	fun dispose() {
		if(::git.isInitialized) git.dispose()
	}
} 