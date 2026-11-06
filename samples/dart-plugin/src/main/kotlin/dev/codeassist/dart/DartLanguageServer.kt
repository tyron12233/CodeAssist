package dev.codeassist.dart

import dev.ide.lang.LanguageId
import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.platform.log.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dart's analysis server, the one `dart language-server` starts, run straight from its snapshot.
 *
 * One server per package ([rootFor] is the directory holding the nearest `pubspec.yaml`), which is how the
 * Dart tools scope analysis. A package that never had `pub get` gets one before the server starts, because
 * without its package config every `package:` import is an error.
 */
class DartLanguageServer(private val sdk: DartSdk, private val pub: Pub, private val log: Logger) : LanguageServerDefinition {
    override val id = "dart"
    override val displayName = "Dart analysis server"
    override val languages = setOf(LanguageId(DartPlugin.LANGUAGE))

    override fun start(context: LanguageServerContext): LanguageServerConnection? {
        val status = sdk.prepare()
        if (status is DartSdk.Status.Unavailable) {
            log.warn(status.reason)
            return null
        }
        val runtime = sdk.runtime ?: return null
        val server = sdk.analysisServer ?: return null
        if (Files.isRegularFile(context.root.resolve(DartPackages.PUBSPEC)) && DartPackages.needsPubGet(context.root)) {
            // Best effort: offline, or a dependency that does not resolve, still leaves a server worth having.
            val result = pub.run(context.root, "get", timeoutSeconds = 120) { line -> context.logger.info("pub: $line") }
            if (result != 0) context.logger.warn("pub get exited with $result; package imports may not resolve")
        }
        return LanguageServerConnection.process(
            listOf(runtime.toString()) + DartSdk.VM_TUNING + listOf(
                server.toString(),
                "--protocol=lsp",
                "--dart-sdk=${sdk.sdkDir}",
                "--client-id=codeassist",
                "--cache=${sdk.home.resolve("analysis-cache")}",
            ),
            context.root,
            sdk.environment(),
        )
    }

    /**
     * Only the packages with an open file are analysed, which is what keeps the server's memory proportional
     * to what the user is looking at on a phone. Completion offers what is not imported yet, with the import.
     */
    override fun initializationOptions(context: LanguageServerContext): String =
        """{"onlyAnalyzeProjectsWithOpenFiles":true,"suggestFromUnimportedLibraries":true,"closingLabels":false,"outline":false,"flutterOutline":false}"""

    override fun rootFor(file: Path, context: LanguageServerContext): Path? =
        DartPackages.packageRootOf(file, context.projectRoot)
}
