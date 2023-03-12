package com.tyron.code.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.tyron.code.databinding.NewEditorFragmentBinding
import com.tyron.code.highlighter.JavaFileHighlighter
import com.tyron.code.ui.legacyEditor.EditorChangeUtil
import com.tyron.code.ui.legacyEditor.EditorView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentEvent
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentListener
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.StandardProgressIndicatorBase

class EditorFragment : Fragment() {

    private val viewModel: EditorViewModel by viewModels()


    private lateinit var binding: NewEditorFragmentBinding
    private lateinit var editorImpl: EditorImpl



    private val language = object : EmptyLanguage() {
        val analyzeManager = EditorView.TestAnalyzeManager(JavaFileHighlighter())

        private var completionProgressIndicator: ProgressIndicator = EmptyProgressIndicator()

        override fun getAnalyzeManager(): AnalyzeManager {
            return analyzeManager
        }

        override fun requireAutoComplete(
            content: ContentReference,
            position: CharPosition,
            publisher: CompletionPublisher,
            extraArguments: Bundle
        ) {

            completionProgressIndicator.cancel()
            completionProgressIndicator = StandardProgressIndicatorBase()

            ProgressManager.getInstance().runProcess({
                try {
                    EditorChangeUtil.performCompletionUnderIndicator(
                        viewModel.project,
                        editorImpl,
                        publisher,
                        viewModel.disposable
                    )
                } catch (_: ProcessCanceledException) {

                }
            }, completionProgressIndicator)
        }
    }

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

                editorImpl = EditorImpl(binding.editorView, editorState.editorDocument)

                binding.editorView.setText(editorState.editorContent)
                binding.editorView.setEditorLanguage(language)
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