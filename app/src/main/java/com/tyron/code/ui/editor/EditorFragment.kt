package com.tyron.code.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.tyron.code.databinding.NewEditorFragmentBinding
import com.tyron.code.ui.legacyEditor.CodeAssistCompletionAdapter
import io.github.rosemoe.sora.event.SelectionChangeEvent

class EditorFragment : Fragment() {

    private val viewModel: EditorViewModel by viewModels()


    private lateinit var binding: NewEditorFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = NewEditorFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launchWhenResumed {
            viewModel.editorState.collect { editorState ->

                resetVisibilityStates()

                setLoadingContentViewVisibility(editorState.loadingContent)
                if (editorState.loadingContent) {
                    return@collect
                }

                editorState.loadingErrorMessage?.let {
                    setErrorViewVisibility(editorState.loadingErrorMessage)
                    return@collect
                }

                binding.editorView.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                    viewModel.handleCursorEvent(event);
                }
                binding.editorView.setAutoCompletionItemAdapter(CodeAssistCompletionAdapter(editorState.editor))
                binding.editorView.setText(editorState.editorContent)
                binding.editorView.setEditorLanguage(editorState.soraLanguage)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        binding.editorView.release()
    }

    private fun setErrorViewVisibility(loadingErrorMessage: String) {
        resetVisibilityStates()

        binding.editorContent.visibility = View.GONE
        binding.completionIndicatorContainer.visibility = View.GONE
        binding.errorView.visibility = View.VISIBLE
        binding.errorTextView.text = loadingErrorMessage
    }

    private fun resetVisibilityStates() {
        binding.loadingView.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.editorContent.visibility = View.VISIBLE
        binding.completionIndicatorContainer.visibility = View.VISIBLE
    }

    private fun setLoadingContentViewVisibility(visible: Boolean) {
        if (visible) {
            binding.loadingView.visibility = View.VISIBLE
            binding.editorContent.visibility = View.GONE
            binding.completionIndicatorContainer.visibility = View.GONE
        } else {
            binding.loadingView.visibility = View.GONE
            binding.editorContent.visibility = View.VISIBLE
            binding.completionIndicatorContainer.visibility = View.VISIBLE
        }
    }
}