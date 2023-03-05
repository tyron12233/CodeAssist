package com.tyron.builder.dexing

import com.android.tools.r8.BackportedMethodList
import com.android.tools.r8.BackportedMethodListCommand
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.StringConsumer
import java.io.File

/**
 * Generates backported desugared methods handled by D8, which will be consumed by Lint.
 */
object D8DesugaredMethodsGenerator {
    fun generate(
        coreLibDesugarConfig: String?,
        bootclasspath: Set<File>
    ): List<String> {
        val consumer = CustomStringConsumer()
        val commandBuilder = BackportedMethodListCommand.builder()

        if (coreLibDesugarConfig != null) {
            commandBuilder
                .addDesugaredLibraryConfiguration(coreLibDesugarConfig)
                .addLibraryFiles(bootclasspath.map { it.toPath() })
        }

        BackportedMethodList.run(
            commandBuilder.setConsumer(consumer).build())
        return consumer.strings
    }

    private class CustomStringConsumer : StringConsumer {
        val strings = mutableListOf<String>()

        override fun accept(string: String, handler: DiagnosticsHandler) {
            strings.add(string)
        }

        override fun finished(handler: DiagnosticsHandler) {}
    }
}
