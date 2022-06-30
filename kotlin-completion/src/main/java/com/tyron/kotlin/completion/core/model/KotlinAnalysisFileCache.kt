package com.tyron.kotlin.completion.core.model

import com.tyron.kotlin.completion.core.resolve.AnalysisResultWithProvider
import com.tyron.kotlin.completion.core.resolve.CodeAssistAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.psi.KtFile


data class FileAnalysisResults(val file: KtFile, val analysisResult: AnalysisResultWithProvider)

object KotlinAnalysisFileCache {
    @Volatile
    private var lastAnalysedFileCache: FileAnalysisResults? = null

    @Synchronized fun getAnalysisResult(file: KtFile): AnalysisResultWithProvider {
        return getImmediatlyFromCache(file) ?: run {
            val environment = getEnvironment(file.project)!!
            val analysisResult = resolve(file, environment)

            lastAnalysedFileCache = FileAnalysisResults(file, analysisResult)
            lastAnalysedFileCache!!.analysisResult
        }
    }

    fun resetCache() {
        lastAnalysedFileCache = null
    }

    private fun resolve(file: KtFile, environment: KotlinCoreEnvironment): AnalysisResultWithProvider {
        return when (environment) {
//            is KotlinScriptEnvironment -> EclipseAnalyzerFacadeForJVM.analyzeScript(environment, file)
            is KotlinCoreEnvironment -> CodeAssistAnalyzerFacadeForJVM.analyzeSources(environment, listOf(file))
            else -> throw IllegalArgumentException("Could not analyze file with environment: $environment")
        }
    }

    @Synchronized
    private fun getImmediatlyFromCache(file: KtFile): AnalysisResultWithProvider? {
        return if (lastAnalysedFileCache != null && lastAnalysedFileCache!!.file == file)
            lastAnalysedFileCache!!.analysisResult
        else
            null
    }
}