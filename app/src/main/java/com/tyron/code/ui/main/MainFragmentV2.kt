package com.tyron.code.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import com.tyron.code.databinding.MainFragmentBinding
import com.tyron.code.ui.editor.EditorTabUtil
import com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeCodeEditor
import com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeEditorFacade
import com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeEditorProvider
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewBinder
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewFactory
import com.tyron.code.ui.file.tree.model.TreeFile
import com.tyron.code.util.viewModel
import com.tyron.ui.treeview.TreeNode
import com.tyron.ui.treeview.TreeViewAdapter
import kotlinx.coroutines.flow.collect
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport
import java.io.File

class MainFragmentV2 : Fragment() {

    private var editorListState: TextEditorListState? = null

    private val treeFileNodeFactory = TreeFileNodeViewFactory(object: TreeFileNodeViewBinder.TreeFileNodeListener {
        override fun onNodeToggled(treeNode: TreeNode<TreeFile>?, expanded: Boolean) {

        }

        override fun onNodeLongClicked(
            view: View?,
            treeNode: TreeNode<TreeFile>?,
            expanded: Boolean
        ): Boolean {
            return false;
        }

    })


    private lateinit var viewModelV2: MainViewModelV2
    private lateinit var binding: MainFragmentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)

        val projectPath = requireArguments().getString("project_path")!!
        viewModelV2 = viewModel(MainViewModelV2::class.java, projectPath)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = MainFragmentBinding.inflate(inflater, container, false)


        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launchWhenResumed {
            viewModelV2.currentTextEditorState.collect {



                if (it == null) return@collect


                val editor = RosemoeCodeEditor(
                    requireContext(),
                    File(it.file.path),
                    RosemoeEditorProvider()
                )


                binding.editorContainer.viewpager.addView(editor.view, ViewGroup.LayoutParams(
                    -1, -1
                ))
            }

            viewModelV2.textEditorListState.collect {
                val oldList = editorListState?.editors ?: emptyList()

                editorListState = it

                TransitionManager.beginDelayedTransition(binding.editorContainer.viewpager, MaterialFadeThrough())
                if (it.editors.isEmpty()) {
                    binding.editorContainer.viewpager.removeAllViews()
                    binding.editorContainer.tablayout.removeAllTabs()
                    binding.editorContainer.tablayout.visibility = View.GONE
                } else {
                    binding.editorContainer.tablayout.visibility = View.VISIBLE
                    EditorTabUtil.updateTabLayoutState(
                        binding.editorContainer.tablayout,
                        oldList,
                        it.editors
                    )
                }
            }


            viewModelV2.projectState.collect { state ->
                if (state.initialized && state.projectPath != null) {
                    binding.toolbar.setTitle(state.projectPath)
                }

                binding.progressbar.visibility = if (state.showProgressBar) View.VISIBLE else View.GONE
            }

            viewModelV2.treeNode.collect {
                if (it == null) return@collect
                binding.fileTree.fileTreeRecyclerview.adapter = TreeViewAdapter(requireContext(), it, treeFileNodeFactory)
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(projectPath: String): MainFragmentV2 {
            val fragment = MainFragmentV2()
            fragment.arguments = bundleOf(Pair("project_path", projectPath))
            return fragment
        }
    }
}