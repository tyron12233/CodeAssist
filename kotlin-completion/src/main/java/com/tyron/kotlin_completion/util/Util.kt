package com.tyron.kotlin_completion.util

import com.tyron.kotlin_completion.SourcePath
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext

fun util(sourcesContext: BindingContext?, sources: Set<SourcePath.SourceFile>, allChanged: Set<SourcePath.SourceFile>): BindingContext {
    val same = sources - allChanged
    val combined = listOf(sourcesContext).filterNotNull() + same.map { it.compiledContext!! }
    return CompositeBindingContext.create(combined)
}