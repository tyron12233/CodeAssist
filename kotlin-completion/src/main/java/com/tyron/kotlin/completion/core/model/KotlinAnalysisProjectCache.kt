package com.tyron.kotlin.completion.core.model

import com.tyron.builder.project.api.KotlinModule
import com.tyron.kotlin.completion.core.resolve.CodeAssistAnalyzerFacadeForJVM
import com.tyron.kotlin.completion.core.util.ProjectUtils
import org.jetbrains.kotlin.analyzer.AnalysisResult
import java.util.concurrent.ConcurrentHashMap

object KotlinAnalysisProjectCache {
    private val cachedAnalysisResults = ConcurrentHashMap<KotlinModule, AnalysisResult>()

    fun resetCache(module: KotlinModule) {
        synchronized(module) {
            cachedAnalysisResults.remove(module)
        }
    }

    fun resetAllCaches() {
        cachedAnalysisResults.keys.toList().forEach {
            resetCache(it)
        }
    }

    fun getAnalysisResult(module: KotlinModule): AnalysisResult {
        return synchronized(module) {
            val analysisResult = cachedAnalysisResults[module] ?: run {
                CodeAssistAnalyzerFacadeForJVM.analyzeSources(
                    KotlinEnvironment.getEnvironment(module),
                    ProjectUtils.getSourceFiles(module)
                ).analysisResult
            }

            cachedAnalysisResults.putIfAbsent(module, analysisResult) ?: analysisResult
        }
    }
}