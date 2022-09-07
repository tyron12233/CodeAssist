package com.tyron.builder.dexing

import com.android.ide.common.blame.Message
import com.android.ide.common.blame.MessageReceiver
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.diagnostic.MissingDefinitionsDiagnostic
import com.android.tools.r8.errors.UnsupportedMainDexListUsageDiagnostic
import java.nio.file.Path

/** Handle R8-specific warning/errors and capture additional information. */
class R8DiagnosticsHandler(
    private val missingKeepRulesFile: Path,
    private val messageReceiver: MessageReceiver,
    tool: String,
) : D8DiagnosticsHandler(messageReceiver, tool) {

    override fun warning(warning: Diagnostic?) {
        if (warning is MissingDefinitionsDiagnostic) {
            generateMissingRulesFile(warning, Message.Kind.WARNING)
        }

        if (warning is UnsupportedMainDexListUsageDiagnostic) {
            messageReceiver.receiveMessage(
                Message(
                    Message.Kind.WARNING,
                    "Using multiDexKeepFile property with R8 is deprecated and will be fully" +
                            " removed in AGP 8.0. Please migrate to use multiDexKeepProguard instead."
                )
            )
        }

        super.warning(warning)
    }

    override fun error(warning: Diagnostic?) {
        if (warning is MissingDefinitionsDiagnostic) {
            generateMissingRulesFile(warning, Message.Kind.ERROR)
        }

        super.error(warning)
    }

    private fun generateMissingRulesFile(warning: MissingDefinitionsDiagnostic, messageKind: Message.Kind) {
        val additionalMessage =
                "This will become an error in AGP 8.0.".takeIf { messageKind==Message.Kind.ERROR }
                        ?: ""
        messageReceiver.receiveMessage(
                Message(
                        messageKind,
                        """
                                Missing classes detected while running R8. Please add the missing classes or apply additional keep rules that are generated in $missingKeepRulesFile.
                                $additionalMessage
                                """.trimIndent()))
        val missingClasses = warning.missingDefinitions.mapNotNull {
            if (it.isMissingClass) {
                it.asMissingClass().classReference.typeName
            } else null
        }
        missingKeepRulesFile.toFile().writeText(
                missingClasses.joinToString(
                        separator = System.lineSeparator(),
                        prefix = """
                                # Please add these rules to your existing keep rules in order to suppress warnings.
                                # This is generated automatically by the Android Gradle plugin.

                            """.trimIndent()) {
                    "-dontwarn $it"
                })
    }
}