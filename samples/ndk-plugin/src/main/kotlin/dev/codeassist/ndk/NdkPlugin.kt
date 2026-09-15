package dev.codeassist.ndk

import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.build.BUILD_PLUGIN_EP
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP
import dev.ide.lang.completion.CompletionContribution
import dev.ide.model.FACET_CODEC_EP
import dev.ide.model.FileIconExtensionPoint
import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.platform.notify.USER_MESSAGES
import dev.ide.platform.notify.UserMessages
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.UI_ACTION_EP
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * The engine facet: what this plugin contributes to the IDE that is not Compose.
 *
 * `register` runs at startup, before any project is open, and it deliberately does NOT touch the toolchain.
 * Unpacking ~2800 files is not something to do on the way to the first frame, and a device with no compiler
 * for its ABI should still get a plugin that loads and can say so. [NdkToolchain.prepare] is called from the
 * first thing that actually needs a compiler.
 */
class NdkPlugin : Plugin {

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("NdkPlugin")
        val messages: UserMessages? = reg.appServices.getServiceOrNull(USER_MESSAGES)
        val toolchain = NdkToolchain(reg, log, messages)
        NdkState.toolchain = toolchain

        // Route C and C++ sources to their own language, so the engine stops treating a .cpp as unknown text.
        // The ids match what the UI facet registers an EditorLanguage for; one language is one id across both.
        reg.register(FILE_TYPE_EP, FileTypeMapping(C_SUFFIXES, LanguageId(C_LANGUAGE)))
        reg.register(FILE_TYPE_EP, FileTypeMapping(CPP_SUFFIXES, LanguageId(CPP_LANGUAGE)))

        // The module's native configuration, persisted as the `[ndk]` table of its module.toml. A facet is
        // a type plus its codec, always both: the core cannot serialize one generically.
        reg.register(FACET_CODEC_EP, NdkFacetCodec)
        // Two templates, not one with a switch, because the two projects are not variants of each other: a
        // C++ library has no manifest and no Android in it, while a native activity is an Android app whose
        // only code is C++. Each also arrives with its `[ndk]` table written out in full, which is where a
        // user finds out what is configurable.
        reg.register(ProjectTemplateExtensionPoint, NativeCppTemplate())
        reg.register(ProjectTemplateExtensionPoint, NativeActivityTemplate())

        // What a .c, .cpp or .h looks like in the project tree. The UI facet registers the matching art and
        // the same mapping for tabs and breadcrumbs; both read one table ([NdkFileIcons]).
        reg.register(FileIconExtensionPoint, NdkFileIconProvider)

        // Completion answered by the compiler itself: the same frontend that builds the file knows what a
        // `.` can be followed by, so there is no second parser to disagree with the first.
        reg.register(
            COMPLETION_CONTRIBUTOR_EP,
            CompletionContribution(
                contributor = NdkCompletionContributor({ NdkState.toolchain }, reg.logger("NdkComplete")),
                languages = setOf(LanguageId(C_LANGUAGE), LanguageId(CPP_LANGUAGE)),
            ),
        )

        // The native half of an ordinary build: one task per module carrying an [ndk] facet, wired ahead of
        // the Android packaging merge so the .so is on disk before it looks for one.
        reg.register(BUILD_PLUGIN_EP, NdkBuildPlugin({ NdkState.toolchain }, reg.logger("NdkBuild")))

        // Errors in the editor, from the compiler that builds the file. Registered with the toolchain read
        // lazily, because `register` runs before there is any reason to unpack one.
        reg.register(DIAGNOSTIC_PROVIDER_EP, NdkDiagnosticProvider({ NdkState.toolchain }, reg.logger("NdkClang")))

        // The command that makes the toolchain real to the user: it prepares it (unpacking on first run,
        // with a progress row), then compiles and links a throwaway file so the answer is "it works" rather
        // than "the binary exists".
        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "dev.codeassist.ndk.checkToolchain",
                text = "NDK: check the C/C++ toolchain",
                places = setOf(ActionPlaces.COMMAND_PALETTE, ActionPlaces.MORE_MENU),
                iconId = "build",
            ) {
                when (val status = toolchain.prepare()) {
                    is NdkToolchain.Status.Unavailable -> {
                        log.warn("toolchain unavailable: ${status.reason}")
                        messages?.show(
                            dev.ide.platform.notify.UserMessage(
                                status.reason,
                                dev.ide.platform.notify.MessageSeverity.WARNING,
                            )
                        )
                        ActionResult.message(status.reason)
                    }

                    is NdkToolchain.Status.Ready -> {
                        val built = buildProbe(toolchain, reg)
                        log.info("${status.version}; probe: $built")
                        NdkState.lastCheck = "${status.version}\n$built"
                        messages?.show(
                            dev.ide.platform.notify.UserMessage(
                                "NDK toolchain ready: ${status.version}",
                                dev.ide.platform.notify.MessageSeverity.INFO,
                            )
                        )
                        ActionResult.message(status.version)
                    }
                }
            },
        )

        log.info("registered; toolchain is prepared on first use")
    }

    /**
     * Compile and link a file the plugin writes itself.
     *
     * A version string only proves the binary starts. This proves the parts that actually go wrong: that the
     * resource headers and sysroot were found, that the linker was reached under its own odd name, and that
     * what came out is an AArch64 object rather than something for the build machine.
     */
    private fun buildProbe(toolchain: NdkToolchain, reg: PluginRegistration): String {
        val dir = reg.dataDir.resolve("probe")
        Files.createDirectories(dir)
        val source = dir.resolve("probe.cpp")
        // writeText, not Files.writeString: this runs on ART with a minSdk of 26, and Files.writeString is
        // API 33 -- it dexes clean and throws NoSuchMethodError on the devices this plugin targets.
        source.writeText(
            """
            #include <string>
            #include <vector>
            extern "C" int ca_probe() {
                std::vector<std::string> v{"ndk"};
                return static_cast<int>(v[0].size());
            }
            """.trimIndent()
        )
        val obj = dir.resolve("probe.o")
        val compiled = toolchain.compile(source, obj, cpp = true, extraFlags = listOf("-std=c++17", "-fPIC"))
        if (!compiled.ok) return "compile failed: ${compiled.output.take(300)}"

        val so = dir.resolve("libcaprobe.so")
        val linked = toolchain.linkShared(listOf(obj), so, libs = listOf("log"))
        if (!linked.ok) return "link failed: ${linked.output.take(300)}"

        return "compiled and linked ${so.fileName} (${Files.size(so)} bytes)"
    }

    companion object {
        const val C_LANGUAGE = "c"
        const val CPP_LANGUAGE = "cpp"

        val C_SUFFIXES = listOf(".c")

        /** `.h` goes to C++: a header is compiled as whichever language includes it, and C++ is the tolerant
         *  reading of the two, so treating it as C would flag every class in a C++ project's headers. */
        val CPP_SUFFIXES = listOf(".cpp", ".cc", ".cxx", ".c++", ".h", ".hpp", ".hh", ".hxx", ".inl")
    }
}

/** What the two facets share. They load off one APK on one classloader, so this is one object to both. */
object NdkState {
    @Volatile
    var toolchain: NdkToolchain? = null

    @Volatile
    var lastCheck: String? = null
}
