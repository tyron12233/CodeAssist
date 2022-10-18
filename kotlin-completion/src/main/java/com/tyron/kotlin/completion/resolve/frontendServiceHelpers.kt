package com.tyron.kotlin.completion.resolve

import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory

/**
 * Helper methods for commonly used frontend components.
 * Use them to avoid explicit opt-ins.
 * Before adding a new helper method please make sure component doesn't have fragile invariants that can be violated by external use.
 */

@OptIn(FrontendInternals::class)
fun ResolutionFacade.getLanguageVersionSettings(): LanguageVersionSettings =
    frontendService()

@OptIn(FrontendInternals::class)
fun ResolutionFacade.getDataFlowValueFactory(): DataFlowValueFactory =
    frontendService()