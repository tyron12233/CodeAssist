package com.tyron.code.ui.main

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collect

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

    }

    private fun updateProgressText(text: String) {

    }
}