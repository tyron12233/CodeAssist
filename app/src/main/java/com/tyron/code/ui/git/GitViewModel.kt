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
	
	private val _gitLog = MutableLiveData<String>()
	val gitLog : LiveData<String> = _gitLog
	
	private val _branchList = MutableLiveData<List<String>>()
	val branchList = _branchList
	
	//fun getPath(): LiveData<String> = _projectPath
	
	//fun getRepo(): LiveData<Boolean> = _hasRepo
	
	fun setPath(newPath: String) {
		if(isGitRepoWith(newPath)) {
			_hasRepo.value = true
			_projectPath.value = newPath
			git = openGitAt(newPath)
			getLog()
		}
	}
	
	fun getLog() {
		_gitLog.value = if(::git.isInitialized) git.getLog() else ""
	}
	
	fun getBranchList() {
		_branchList.value = if(::git.isInitialized) git.getBranchList() else listOf("")
	}
	
	fun createGitRepoWith (commiter: Author) {
		_projectPath.value?.apply {
			git = createGitRepoWith(commiter)
			_hasRepo.value = true
			_gitLog.value = git.getLog()
			_branchList.value = git.getBranchList()
		}
		
	}
	
	fun commiting(commiter: Author, msg: String) {
		git.commiting(commiter, msg)
		getLog()
	}
	
	fun createBranch(branch: String) {
		git.createBranch(branch)
		getLog()
		getBranchList()
	}
	
	fun mergeBranch(branch: String) {
		git.mergeBranch(branch)
		getLog()
		getBranchList()
	}
	
	fun getBranch(): String = git.getBranch()
	
	fun deleteBranch(branch: String) {
		git.deleteBranch(branch)
		getLog()
		getBranchList()
	}
	
	fun checkout(position: Int) {
		if(::git.isInitialized) {
			_branchList.value?.let {
				if(it.isNotEmpty()) {
					val branch = it[position]
					if(branch.isNotBlank()) {
						git.checkout(branch)
					}
					getLog()
					getBranchList()
				}
			}
		}
	}
	
	fun dispose() {
		if(::git.isInitialized) git.dispose()
		_branchList.value = listOf("")
		_gitLog.value = "No Log available"
	}
} 