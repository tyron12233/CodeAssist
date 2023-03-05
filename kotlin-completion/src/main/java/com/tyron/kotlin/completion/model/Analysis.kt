package com.tyron.kotlin.completion.model

import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.container.ComponentProvider

data class Analysis(val componentProvider: ComponentProvider, val analysisResult: AnalysisResult)