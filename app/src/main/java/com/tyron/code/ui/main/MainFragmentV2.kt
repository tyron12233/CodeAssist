package com.tyron.code.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import com.tyron.code.databinding.MainFragmentBinding
import com.tyron.code.ui.editor.EditorFragment
import com.tyron.code.ui.legacyEditor.EditorTabUtil
import com.tyron.code.ui.file.FileViewModel
import com.tyron.code.util.applySystemWindowInsets
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile

class MainFragmentV2 : Fragment() {

    private var editorListState: TextEditorListState? = null

    private val viewModelV2 by viewModels<MainViewModelV2>()
    private val fileViewModel by viewModels<FileViewModel>()

    private lateinit var binding: MainFragmentBinding
    private val toolbarManager by lazy { ToolbarManager() }

    private val indexingUiFragment by lazy { BottomSheetDialogFragment() }
    private val editorMap = mutableMapOf<VirtualFile, Fragment>()

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

        view.applySystemWindowInsets(false) { _, top, _, bottom ->
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

    private fun observeViewModel() {
        lifecycleScope.launchWhenResumed {
            viewModelV2.currentTextEditorState.collect { textEditorState ->
                val editors = getEditorFragments()
                if (textEditorState == null) {
                    val transaction = childFragmentManager.beginTransaction()
                    editors.forEach(transaction::hide)
                    transaction.commit()
                    return@collect
                }

                val fragment = editorMap.computeIfAbsent(textEditorState.file) {
                    EditorFragment().apply {
                        arguments = bundleOf(
                            Pair("filePath", textEditorState.file.path)
                        )
                    }
                }

                if (fragment.isVisible) {
                    // no need to do expensive work
                    return@collect
                }

                val tag = "editor-{${textEditorState.file.hashCode()}"

                val transaction = childFragmentManager.beginTransaction()
                editors.forEach(transaction::hide)
                transaction.replace(binding.editorContainer.viewpager.id, fragment, tag)
                transaction.commit()
            }
        }

        lifecycleScope.launchWhenResumed {
            viewModelV2.textEditorListState.collect {
                val oldList = editorListState?.editors ?: emptyList()

                editorListState = it

                TransitionManager.beginDelayedTransition(
                    binding.editorContainer.viewpager,
                    MaterialFadeThrough()
                )
                if (it.editors.isEmpty()) {
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
                binding.progressbar.visibility =
                    if (state.showProgressBar) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launchWhenResumed {
            viewModelV2.indexingState.collect { indexingState ->
                if (indexingState == null) {
                    // dismiss logic handled in the fragment itself
                    return@collect
                }

                if (indexingUiFragment.isDetached) {
                    indexingUiFragment.show(childFragmentManager, "")
                }
            }
        }
    }

    private fun getEditorFragments() = childFragmentManager.fragments
        .filter { it.tag != null }
        .filter { it.tag!!.startsWith("editor") }

    companion object {
        @JvmStatic
        fun newInstance(projectPath: String): MainFragmentV2 {
            val fragment = MainFragmentV2()
            fragment.arguments = bundleOf(Pair("project_path", projectPath))
            return fragment
        }
    }
}