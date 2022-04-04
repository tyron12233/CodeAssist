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
import androidx.lifecycle.ViewModelProvider

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import java.io.File

import de.prinova.git.model.Author

import com.tyron.code.R
import com.tyron.code.util.*
import com.tyron.common.logging.IdeLog
import com.tyron.builder.project.Project
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl
import com.tyron.code.ui.git.GitViewModel

import kotlinx.coroutines.*

var gitLogText : TextView? = null
var gitBranchSpinner : Spinner? = null
var gitNewGitButton : Button? = null
var gitCommitButton : Button? = null
var gitCreateBranchButton : Button? = null
var gitMergeBranchButton : Button? = null
var gitDeleteBranchButton : Button? = null

var arrayAdapter: ArrayAdapter<String>? = null

lateinit var perso: Author

const val ARG_PATH_ID = "pathId"

//TODO: implement ListView for merging and deleting
//TODO: implement Recyclerview for LogList
//TODO: fix wrong filetreeview after deleting file in a topic branch and checking out
//TODO: Put Author into PreferenceSettings
//TODO: Let select commits for reverting, restore and reset

var onSave: ()-> Unit = {}
var preCheckout: ()-> Unit = {}
var postCheckout: ()-> Unit = {}

class GitFragment : Fragment(), AdapterView.OnItemSelectedListener {
	
	val mGitViewModel : GitViewModel by lazy {
		ViewModelProvider(requireActivity()).get(GitViewModel::class.java)
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
		
		mGitViewModel.hasRepo.observe(getViewLifecycleOwner()) { isRepo -> 
			switchButtons(isRepo)
		}
		
		mGitViewModel.gitLog.observe(viewLifecycleOwner) { log ->
			gitLogText?.text = log
		}
		
		mGitViewModel.branchList.observe(viewLifecycleOwner) {
			arrayAdapter?.listOf(it)
		}
		
		return root
	}
	
	override fun onViewCreated (view: View, savedInstanceState: Bundle?) {
		super.onViewCreated (view, savedInstanceState)
		
		ViewCompat.requestApplyInsets(view)
        view.addSystemWindowInsetToPadding(false, true, false, true)
        
		perso = Author("User", "user@localhost.com")
		
		gitNewGitButton?.setOnClickListener { _ ->
			with(mGitViewModel) {
				createGitRepoWith(perso)
			}
		}
		
		gitCommitButton?.setOnClickListener {
			commitWith(perso)
		}
		
		gitCreateBranchButton?.setOnClickListener {
			createBranch()
		}
		
		gitMergeBranchButton?.setOnClickListener {
			mergeBranch()
		}
		
		gitDeleteBranchButton?.setOnClickListener {
			deleteBranch()
		}	
	}
	
	override fun onDestroyView() {
		super.onDestroyView()
		mGitViewModel.dispose()
	}
	
	override fun onResume() {
		super.onResume()
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
		mGitViewModel.checkout(position)
		postCheckout()	
	}
	override fun onNothingSelected(parent: AdapterView<*>) {}		
}

fun View.initializeUI(context: Context, listener: AdapterView.OnItemSelectedListener) {
	
	gitNewGitButton = findViewById (R.id.git_button_create)
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
	gitNewGitButton?.setEnabled(!hasRepo)
	gitCommitButton?.setEnabled(hasRepo)
	gitCreateBranchButton?.setEnabled(hasRepo)
	gitBranchSpinner?.setEnabled(hasRepo)
	gitMergeBranchButton?.setEnabled(hasRepo)
	gitDeleteBranchButton?.setEnabled(hasRepo)
}

fun GitFragment.commitWith(commiter: Author) {
	onSave()
	val commitText = EditText(requireContext()).apply {
			setHint("Commit Message")
		}
	MaterialAlertDialogBuilder(requireContext())
	.setTitle("Commiting")
	.setView( commitText )		
	.setPositiveButton("Commit") {_, _ ->
		mGitViewModel.commiting(commiter, commitText.getText().toString())
	}
	.setNegativeButton("Cancel") {_,_ ->}	
	.show()
}

fun GitFragment.createBranch() {	
	val branchText = EditText(requireContext()).apply {
			setHint("Branch Name")
		}
	MaterialAlertDialogBuilder(requireContext())
	.setTitle("New Branch")
	.setView( branchText )		
	.setPositiveButton("Create") {_, _ ->
		mGitViewModel.createBranch(branchText.getText().toString())
	}
	.setNegativeButton("Cancel") {_,_ ->}	
	.show()
	
}

fun GitFragment.mergeBranch() {
	val branchText = EditText(requireContext()).apply {
		setHint("Type exact Branch to merge with")
	}
	MaterialAlertDialogBuilder(requireContext())
	.setTitle("Merge Branch")
	.setView( branchText )		
	.setPositiveButton("Merge") {_, _ ->
		val text = branchText.getText().toString()
		mGitViewModel.mergeBranch(text) {
			postCheckout()
			onSave()
		}
		.or {
			MaterialAlertDialogBuilder(requireContext())
			.setTitle("!! Alert !!")
			.setMessage("$text not in Repository")
			.setPositiveButton("OK") {_, _ -> }
			.show()
		}
	}
	.setNegativeButton("Cancel") {_,_ ->}	
	.show()
}

fun GitFragment.deleteBranch() {
	val branchText = EditText(requireContext()).apply {
		setHint("Type exact Branch to delete. Must not be the current branch")
	}
	MaterialAlertDialogBuilder(requireContext())
	.setTitle("Delete Branch")
	.setView( branchText )		
	.setPositiveButton("Delete") {_, _ ->
		val text = branchText.getText().toString()
		mGitViewModel.deleteBranch(text)
		.or {
			MaterialAlertDialogBuilder(requireContext())
			.setTitle("!! Alert !!")
			.setMessage("$text must not be the current branch to delete.")
			.setPositiveButton("OK") {_, _ -> }
			.show()
		}
	}
	.setNegativeButton("Cancel") {_,_ ->}	
	.show()
}

fun dispose() {
	onSave = {}
	postCheckout = {}
	gitBranchSpinner = null
	gitCommitButton = null
	gitCreateBranchButton = null
	gitDeleteBranchButton = null
	gitNewGitButton = null
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