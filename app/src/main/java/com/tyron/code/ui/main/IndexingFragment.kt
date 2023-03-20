package com.tyron.code.ui.main

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Used to display the indexing progress, this class is temporary as the user should be able
 * to interact with the project during indexing.
 *
 * For now this is used to prevent the user from modifying files until
 * DumbService is properly implemented
 *
 * This fragment requires the parent fragment to be MainFragmentV2 as the indexing state
 * is stored in its scope
 */
class IndexingFragment : BottomSheetDialogFragment() {

    private val viewModel: MainViewModelV2 by viewModels({ requireParentFragment() })

    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var loadingText: TextView
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext())
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.gravity = Gravity.CENTER

        progressBar = LinearProgressIndicator(requireContext())
        progressBar.isIndeterminate = true
        progressBar.max = 100
        root.addView(progressBar)

        loadingText = TextView(requireContext())

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launchWhenResumed {
            viewModel.indexingState.collect { state ->
                if (state == null) {
                    dismiss()
                    return@collect
                }

                updateProgressFraction(state.fraction)
                updateProgressText(state.text)
            }
        }
    }

    private fun updateProgressFraction(fraction: Double) {
        if (progressBar.isIndeterminate) {
            progressBar.isIndeterminate = false
        }
        progressBar.progress = (fraction * 100).toInt()
    }

    private fun updateProgressText(text: String) {
        loadingText.text = text
    }
}