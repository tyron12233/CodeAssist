package com.tyron.builder.gradle.tasks

import com.google.common.base.Joiner
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

class CommandLineArgumentProviderAdapter(
    @get:Input
    val classNames: Provider<List<String>>,

    @get:Input
    val arguments: Provider<Map<String, String>>
): CommandLineArgumentProvider {

    override fun asArguments(): MutableIterable<String> {
        return mutableListOf<String>().also {
            if (classNames.get().isNotEmpty()) {
                it.add("-processor")
                it.add(Joiner.on(',').join(classNames.get()))
            }

            for ((key, value) in arguments.get()) {
                it.add("-A$key=$value")
            }
        }
    }
}
