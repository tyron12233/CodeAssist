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

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import java.io.File

import de.prinova.git.usecases.*
import de.prinova.git.model.*

import com.tyron.code.R

lateinit var gitLogText : TextView
lateinit var gitBranchSpinner : Spinner
lateinit var gitInitButton : Button
lateinit var gitCommitButton : Button
lateinit var gitCreateBranchButton : Button
lateinit var gitMergeBranchButton : Button
lateinit var gitDeleteBranchButton : Button
lateinit var arrayAdapter: ArrayAdapter<String>
lateinit var git: Gitter

class GitFragment : Fragment(), AdapterView.OnItemSelectedListener {
	
	override fun onCreate (savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
	}
	
	override fun onCreateView (
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val root = inflater.inflate(R.layout.fragment_git, container, false)
		root.initializeUI(requireContext(), this)
		return root
	}
	
	override fun onViewCreated (view: View, savedInstanceState: Bundle?) {
		super.onViewCreated (view, savedInstanceState)		
		//view.initializeUI(requireContext(), this)
		
		val perso = Author("step", "step@hdhdh.com")
		val gitDir = "/storage/emulated/0/CodeAssist/TestApp"
		
		val hasRepo = initRepo(gitDir)
		switchButtons(hasRepo)
		
		if (hasRepo) {
			git = gitDir.openGit()
			gitLogText.text = git.getLog()
			arrayAdapter.listOf(git.getBranchList())
		}
		
		gitInitButton.setOnClickListener { _ ->
			git = gitDir.initializeRepo(perso)
			gitLogText.text = git.getLog()
			arrayAdapter.listOf(git.getBranchList())
			switchButtons(true)
		}
		
		gitCommitButton.setOnClickListener {
			commit(requireContext(), perso)
		}
		
		gitCreateBranchButton.setOnClickListener {
			createBranch(requireContext())
		}
		
		gitMergeBranchButton.setOnClickListener {
			mergeBranch(requireContext())
		}
		
		gitDeleteBranchButton.setOnClickListener {
			deleteBranch(requireContext())
		}	
	}
	
	override fun onDestroy() {
		git.destroy()
		super.onDestroy()	
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
	arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
	gitBranchSpinner.setAdapter(arrayAdapter)
	gitBranchSpinner.setOnItemSelectedListener(listener)
}		

fun switchButtons(hasRepo: Boolean)
{
	gitInitButton.setEnabled(!hasRepo)
	gitCommitButton.setEnabled(hasRepo)
	gitCreateBranchButton.setEnabled(hasRepo)
	gitBranchSpinner.setEnabled(hasRepo)
	gitMergeBranchButton.setEnabled(hasRepo)
	gitDeleteBranchButton.setEnabled(hasRepo)
}

fun commit(context: Context, commiter: Author) {	
	val commitText = EditText(context).apply {
			setHint("Commit Message")
		}
	MaterialAlertDialogBuilder(context)
	.setTitle("Commiting")
	.setView( commitText )		
	.setPositiveButton("Commit") {_, _ ->
		git.commiting(commiter, commitText.getText().toString())
		gitLogText.text = "${git.getLog()}"
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
		arrayAdapter.listOf(git.getBranchList())
		gitLogText.text = "${git.getLog()}"
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
			arrayAdapter.listOf(branchList)
			gitLogText.text = "${git.getLog()}"
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
		val branchList = git.getBranch()
		val text = branchText.getText().toString()
		
		if (text !in branchList) {
			git.deleteBranch(text)
			arrayAdapter.listOf(git.getBranchList())
			gitLogText.text = "${git.getLog()}"
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

fun checkout(position: Int) {
	if(::git.isInitialized) {
		val branch = git.getBranchList()[position]
		git.checkout(branch)
		gitLogText.setText(git.getLog())
	}
}

fun <I> ArrayAdapter<I>.listOf(items: List<I>) {	
	clear()
	addAll(items)
}