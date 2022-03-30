@file:JvmName("GitFragmentUtils")

package com.tyron.code.ui.git

import android.os.Bundle
import android.content.Context

import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import android.widget.Spinner

import androidx.fragment.app.Fragment
import androidx.core.os.*
import androidx.core.view.ViewCompat

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import java.io.File

import de.prinova.git.usecases.*
import de.prinova.git.model.*

import com.tyron.code.R
import com.tyron.code.util.*
import com.tyron.common.logging.IdeLog
import com.tyron.builder.project.Project
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl

import kotlinx.coroutines.*

var gitLogText : TextView? = null
var gitBranchSpinner : Spinner? = null
var gitInitButton : Button? = null
var gitCommitButton : Button? = null
var gitCreateBranchButton : Button? = null
var gitMergeBranchButton : Button? = null
var gitDeleteBranchButton : Button? = null

var arrayAdapter: ArrayAdapter<String>? = null
lateinit var git: Gitter
lateinit var perso: Author
//var root: View? = null

const val ARG_PATH_ID = "pathId"

//TODO: Create ViewModel for git logic (commit, checkout, merge, etc)

//TODO: fix wrong filetreeview after deleting file in a topic branch and checking out
//TODO: fix wrong logtext, when fast switching between projects
//TODO: Put Author into PreferenceSettings
//TODO: Let select commits for reverting, restore and reset

var onSave: ()-> Unit = {}
var preCheckout: ()-> Unit = {}
var postCheckout: ()-> Unit = {}

class GitFragment : Fragment(), AdapterView.OnItemSelectedListener {
	
	
	companion object {
		@JvmStatic
		fun newInstance(path: String) = GitFragment().apply {
			arguments = bundleOf(ARG_PATH_ID to path)
		}
	}
			
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
	}
	
	override fun onCreateView (
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val root = inflater.inflate(R.layout.git_fragment, container, false)
		root.initializeUI(requireContext(), this)
		return root
	}
	
	override fun onViewCreated (view: View, savedInstanceState: Bundle?) {
		super.onViewCreated (view, savedInstanceState)
		
		ViewCompat.requestApplyInsets(view)
        view.addSystemWindowInsetToPadding(false, true, false, true)
        
		perso = Author("User", "user@localhost.com")
		
		val gitDir = requireArguments().getString(ARG_PATH_ID, "")
		
		val hasRepo = initRepo(gitDir).also {
			switchButtons(it)
		}
		
		if (hasRepo) {
			git = gitDir.openGit().also {
				gitLogText?.text = it.getLog()
				arrayAdapter?.listOf(it.getBranchList())
			}
		}
		
		gitInitButton?.setOnClickListener { _ ->
			git = gitDir.initializeRepo(perso).also {
				gitLogText?.text = it.getLog()
				arrayAdapter?.listOf(it.getBranchList())
				switchButtons(true)
			}
		}
		
		gitCommitButton?.setOnClickListener {
			commit(requireContext(), perso)
		}
		
		gitCreateBranchButton?.setOnClickListener {
			createBranch(requireContext())
		}
		
		gitMergeBranchButton?.setOnClickListener {
			mergeBranch(requireContext())
		}
		
		gitDeleteBranchButton?.setOnClickListener {
			deleteBranch(requireContext())
		}	
	}
	
	override fun onDestroyView() {
		super.onDestroyView()
		dispose()
		if(::git.isInitialized) git.destroy()
	}
	
	override fun onResume() {
		super.onResume()
		//root.initializeUI(requireContext(), this)
	}
	
	override fun onDestroy() {
		super.onDestroy()
	}
	
	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
	}
	
	override fun onItemSelected(
		parent: AdapterView<*>,
		v: View?,
		position: Int,
		id: Long
	) {
		checkout(position)	
	}
	override fun onNothingSelected(parent: AdapterView<*>) {}		
}

fun View.initializeUI(context: Context, listener: AdapterView.OnItemSelectedListener) {
	
	gitInitButton = findViewById (R.id.git_button_create)
	gitCommitButton = findViewById(R.id.git_button_commit)
	gitCreateBranchButton = findViewById(R.id.git_button_create_branch)
	gitBranchSpinner = findViewById (R.id.git_spinner_branch)
	gitMergeBranchButton = findViewById(R.id.git_button_merge_branch)
	gitDeleteBranchButton = findViewById(R.id.git_button_delete_branch)
	gitLogText = findViewById (R.id.git_text_log)
	
	arrayAdapter = ArrayAdapter<String>(
		context, 
		android.R.layout.simple_spinner_item,
		mutableListOf("")
	)
	arrayAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
	gitBranchSpinner?.setAdapter(arrayAdapter)
	gitBranchSpinner?.setOnItemSelectedListener(listener)
}		
	
fun switchButtons(hasRepo: Boolean)
{
	gitInitButton?.setEnabled(!hasRepo)
	gitCommitButton?.setEnabled(hasRepo)
	gitCreateBranchButton?.setEnabled(hasRepo)
	gitBranchSpinner?.setEnabled(hasRepo)
	gitMergeBranchButton?.setEnabled(hasRepo)
	gitDeleteBranchButton?.setEnabled(hasRepo)
}

fun commit(context: Context, commiter: Author) {
	onSave()
	val commitText = EditText(context).apply {
			setHint("Commit Message")
		}
	MaterialAlertDialogBuilder(context)
	.setTitle("Commiting")
	.setView( commitText )		
	.setPositiveButton("Commit") {_, _ ->
		git.commiting(commiter, commitText.getText().toString())
		gitLogText?.text = git.getLog()
	}
	.setNegativeButton("Cancel") {_,_ ->}	
	.show()
}

fun createBranch(context: Context) {	
	val branchText = EditText(context).apply {
			setHint("Branch Name")
		}
	MaterialAlertDialogBuilder(context)
	.setTitle("New Branch")
	.setView( branchText )		
	.setPositiveButton("Create") {_, _ ->
		git.createBranch(branchText.getText().toString())
		arrayAdapter?.listOf(git.getBranchList())
		gitLogText?.text = git.getLog()
	}
	.setNegativeButton("Cancel") {_,_ ->}	
	.show()
	
}

fun mergeBranch(context: Context) {
	val branchText = EditText(context).apply {
		setHint("Type exact Branch to merge with")
	}
	MaterialAlertDialogBuilder(context)
	.setTitle("Merge Branch")
	.setView( branchText )		
	.setPositiveButton("Merge") {_, _ ->
		val branchList = git.getBranchList()
		val text = branchText.getText().toString()
		
		if (text in branchList) {
			git.mergeBranch(text)
			arrayAdapter?.listOf(branchList)
			postCheckout()
			onSave()
			gitLogText?.text = git.getLog()
		} else {
			MaterialAlertDialogBuilder(context)
			.setTitle("!! Alert !!")
			.setMessage("Branch not in Repository")
			.setPositiveButton("OK") {_, _ -> }
			.show()
		}
	}
	.setNegativeButton("Cancel") {_,_ ->}	
	.show()
}

fun deleteBranch(context: Context) {
	val branchText = EditText(context).apply {
		setHint("Type exact Branch to delete. Must not be the current branch")
	}
	MaterialAlertDialogBuilder(context)
	.setTitle("Delete Branch")
	.setView( branchText )		
	.setPositiveButton("Delete") {_, _ ->
		val currentBranch = git.getBranch()
		val text = branchText.getText().toString()
		
		if (text !in currentBranch) {
			git.deleteBranch(text)
			arrayAdapter?.listOf(git.getBranchList())
			gitLogText?.text = git.getLog()
		} else {
			MaterialAlertDialogBuilder(context)
			.setTitle("!! Alert !!")
			.setMessage("Current Branch must not be the branch to delete.")
			.setPositiveButton("OK") {_, _ -> }
			.show()
		}
	}
	.setNegativeButton("Cancel") {_,_ ->}	
	.show()
}

fun GitFragment.checkout(position: Int) {
	if(::git.isInitialized) {
		val branch = git.getBranchList().let {
			if(it.isNotEmpty()) it[position] else ""
		}
		//postCheckout()
		git.checkout(branch)
		postCheckout()
		gitLogText?.setText(git.getLog())
	}
}

fun dispose() {
	onSave = {}
	postCheckout = {}
	gitBranchSpinner = null
	gitCommitButton = null
	gitCreateBranchButton = null
	gitDeleteBranchButton = null
	gitInitButton = null
	gitMergeBranchButton = null
	gitLogText = null
	arrayAdapter?.clear()
	arrayAdapter = null
}

fun toContent(file: File?) = file?.readText() ?: ""

fun <I> ArrayAdapter<I>.listOf(items: List<I>) {	
	clear()
	addAll(items)
}