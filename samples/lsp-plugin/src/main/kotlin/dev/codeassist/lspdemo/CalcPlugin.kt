package dev.codeassist.lspdemo

import dev.ide.build.TEST_PROVIDER_EP
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.lsp.LANGUAGE_SERVER_EP
import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginRegistration
import java.io.File
import java.net.URLDecoder

/**
 * The engine facet: Calc's file type, and the language server that answers for it. That is the whole of the
 * language integration; diagnostics, completion, navigation and the rest come from the server through the
 * IDE's LSP client.
 */
class CalcPlugin : Plugin {
    override fun register(reg: PluginRegistration) {
        val log = reg.logger("CalcPlugin")
        reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(SUFFIX), LanguageId(LANGUAGE)))
        // `test NAME: EXPR is EXPECTED` lines: gutter marks, a Run row, results in the Tests tab.
        reg.register(TEST_PROVIDER_EP, CalcTestProvider)
        reg.register(LANGUAGE_SERVER_EP, object : LanguageServerDefinition {
            override val id = "calc"
            override val displayName = "Calc language server"
            override val languages = setOf(LanguageId(LANGUAGE))

            override fun start(context: LanguageServerContext): LanguageServerConnection? {
                val vm = LAUNCHERS.firstOrNull { File(it).exists() }
                val apk = ownApk()
                if (vm == null || apk == null) {
                    log.warn("cannot start the Calc server: vm=$vm apk=$apk")
                    return null
                }
                val tmp = File(reg.dataDir.toFile(), "tmp").apply { mkdirs() }
                return LanguageServerConnection.process(
                    listOf(vm, "-Xmx96m", "-Djava.io.tmpdir=${tmp.path}", "-cp", apk, SERVER_MAIN),
                    context.root,
                )
            }
        })
    }

    /**
     * Where this plugin's APK is installed: the server's VM loads its classes from it. A plugin has no
     * `Context`, so the path is read off the URL its classloader gives for a file packaged only here.
     */
    private fun ownApk(): String? {
        val url = CalcPlugin::class.java.classLoader?.getResource(MARKER)?.toString() ?: return null
        // jar:file:/data/app/~~…/dev.codeassist.lspdemo-…/base.apk!/assets/calc-server.marker
        val path = url.substringAfter("file:", "").substringBefore("!/")
        return URLDecoder.decode(path, "UTF-8").takeIf { it.isNotEmpty() && File(it).isFile }
    }

    companion object {
        const val LANGUAGE = "calc"
        const val SUFFIX = ".calc"
        const val SERVER_MAIN = "dev.codeassist.lspdemo.CalcServerMain"
        private const val MARKER = "assets/calc-server.marker"

        /** Command-line VMs that take `-cp <apk> <class>`; the first that exists wins. */
        private val LAUNCHERS = listOf(
            "/apex/com.android.art/bin/dalvikvm64",
            "/apex/com.android.art/bin/dalvikvm",
            "/system/bin/dalvikvm64",
            "/system/bin/dalvikvm",
        )
    }
}
