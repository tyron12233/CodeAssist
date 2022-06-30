package com.tyron.kotlin.completion.core.model

import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.idea.KotlinLanguage
import java.io.File

class KotlinLightVirtualFile(
    val file: File, text: String
) : LightVirtualFile(file.name, KotlinLanguage.INSTANCE, text) {

    override fun getPath(): String {
        return file.absolutePath
    }
}