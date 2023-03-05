package com.tyron.builder.gradle.internal.core

import com.tyron.builder.gradle.internal.dsl.AnnotationProcessorOptions
import org.gradle.process.CommandLineArgumentProvider

class MergedAnnotationProcessorOptions:
    AnnotationProcessorOptions(),
    MergedOptions<com.tyron.builder.gradle.api.AnnotationProcessorOptions> {

    override fun setClassNames(classNames: MutableList<String>) {
        this.classNames.clear()
        this.classNames.addAll(classNames)
    }

    override fun setArguments(arguments: MutableMap<String, String>) {
        this.arguments.clear()
        this.arguments.putAll(arguments)
    }

    override fun setCompilerArgumentProviders(compilerArgumentProviders: MutableList<CommandLineArgumentProvider>) {
        this.compilerArgumentProviders.clear()
        this.compilerArgumentProviders.addAll(compilerArgumentProviders)
    }

    override val classNames = mutableListOf<String>()
    override val arguments = mutableMapOf<String, String>()
    override val compilerArgumentProviders = mutableListOf<CommandLineArgumentProvider>()

    override fun reset() {
        classNames.clear()
        arguments.clear()
        compilerArgumentProviders.clear()
    }

    override fun append(option: com.tyron.builder.gradle.api.AnnotationProcessorOptions) {
        classNames.addAll(option.classNames)
        arguments.putAll(option.arguments)
        compilerArgumentProviders.addAll(option.compilerArgumentProviders)
    }
}
