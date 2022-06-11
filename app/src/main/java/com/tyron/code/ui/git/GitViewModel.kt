package com.tyron.code.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

import de.prinova.git.model.*
import de.prinova.git.usecases.*

import com.tyron.common.logging.IdeLog

lateinit var git: Gitter

class GitViewModel : ViewModel() {
	
	var postCheckout: ()-> Unit = {}
	var onSave: ()-> Unit = {}
	
	private val _projectPath = MutableLiveData<String>()
	val projectPath: LiveData<String> = _projectPath
	
	private val _hasRepo = MutableLiveData<Boolean>()
	val hasRepo: LiveData<Boolean> = _hasRepo
	
	private val _gitLog = MutableLiveData<String>()
	val gitLog : LiveData<String> = _gitLog
	
	private val _branchList = MutableLiveData<List<String>>()
	val branchList = _branchList
	
	fun setPath(newPath: String) {
		_projectPath.value = newPath
		if(isGitRepoAt(newPath)) {
			_hasRepo.value = true
			git = openGitAt(newPath)
			getLog()
			getBranchList()
		} else {
			_hasRepo.value = false
		}
	}
	
	fun getLog() {
		_gitLog.value = /*if(::git.isInitialized)*/ git.getLog() /*else ""*/
	}
	
	fun getBranchList() {
		_branchList.value = /*if(::git.isInitialized)*/ git.getBranchList() /*else listOf("")*/
	}
	
	fun createGitRepoWith (commiter: Author) {
		_projectPath.value?.apply {
			_hasRepo.value = true
			git = createGitRepoWith(commiter)
			getLog()
			getBranchList()
			postCheckout()
		}
		
	}
	
	fun commiting(commiter: Author, msg: String) {
		git.commiting(commiter, msg)
		getLog()
	}
	
	fun createBranch(branch: String): Result {
		git.createBranch(branch)
		getLog()
		getBranchList()
		return Success
	}
	
	fun mergeBranch(branch: String): Result {
		if (branch in git.getBranchList()) {
			onSave()
			git.mergeBranch(branch)
			getLog()
			getBranchList()
			postCheckout()
			return Success
		}
		return Failure
	}
	
	fun getBranch(): String = git.getBranch()
	
	fun deleteBranch(branch: String): Result {
		if(branch !in getBranch()) {
			git.deleteBranch(branch)
			getLog()
			getBranchList()
			return Success
		}
		return Failure
	}
	
	fun checkout(position: Int) {
		if(::git.isInitialized) {
			_branchList.value?.let {
				if(it.isNotEmpty()) {
					val branch = it[position]
					if(branch.isNotBlank()) {
						onSave()
						git.checkout(branch)
						postCheckout()
						getLog()
						getBranchList()
					}
				}
			}
		}
	}
	
	fun dispose() {
		postCheckout = {}
		onSave = {}
		if(::git.isInitialized) git.dispose()
		_branchList.value = listOf("")
		_gitLog.value = "No Log available"
	}
}