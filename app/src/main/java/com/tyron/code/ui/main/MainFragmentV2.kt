package com.tyron.code.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import com.tyron.code.databinding.MainFragmentBinding
import com.tyron.code.ui.editor.EditorTabUtil
import com.tyron.code.ui.editor.EditorView
import com.tyron.code.ui.file.FileViewModel
import com.tyron.code.util.applySystemWindowInsets
import com.tyron.code.util.viewModel
import kotlinx.coroutines.flow.collect

class MainFragmentV2 : Fragment() {

    private var editorListState: TextEditorListState? = null

    private val viewModelV2 by viewModels<MainViewModelV2>()
    private val fileViewModel by viewModels<FileViewModel>()

    private lateinit var binding: MainFragmentBinding
    private val toolbarManager by lazy { ToolbarManager() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
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
        toolbarManager.bind(binding)

        view.applySystemWindowInsets(false) { left, top, right, bottom ->
            binding.drawerMainContent.updatePadding(top = top, bottom = bottom)
        }

        binding.editorContainer.tablayout.addOnTabSelectedListener(object : OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
               tab?.position?.let { viewModelV2.onTabSelected(it) }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabReselected(tab: TabLayout.Tab?) {

            }

        })

        observeViewModel()
    }

    fun observeViewModel() {
        lifecycleScope.launchWhenResumed {
            viewModelV2.currentTextEditorState.collect { textEditorState ->
                if (textEditorState == null) {
                    binding.editorContainer.viewpager.removeAllViews()
                    return@collect
                }

                val editorView = binding.editorContainer.viewpager.children.filterIsInstance(EditorView::class.java)
                    .find { it.file == textEditorState.file }
                if (editorView != null) {
                    editorView.bringToFront()
                    // view already selected
                    return@collect
                }

                val editor = EditorView(
                    requireContext(),
                    viewModelV2.projectEnvironment.project,
                    textEditorState
                )
                binding.editorContainer.viewpager.addView(editor, ViewGroup.LayoutParams(
                    -1, -1
                ))
            }
        }

        lifecycleScope.launchWhenResumed {
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
        }

        lifecycleScope.launchWhenResumed {
            viewModelV2.projectState.collect { state ->
                if (state.initialized && state.projectName != null) {
                    binding.toolbar.title = state.projectName
                }

                binding.progressbar.isIndeterminate = true
                binding.progressbar.visibility = if (state.showProgressBar) View.VISIBLE else View.GONE
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