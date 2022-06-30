package com.tyron.kotlin.completion.core.util

import com.tyron.builder.project.api.KotlinModule
import com.tyron.kotlin.completion.core.builder.KotlinPsiManager
import org.jetbrains.kotlin.psi.KtFile

object ProjectUtils {

    fun getSourceFiles(module: KotlinModule): List<KtFile> {
        val tempFiles = KotlinPsiManager.getFilesByProject(module);
        return tempFiles.map { KotlinPsiManager.getParsedFile(module, it) }
    }
}