package dev.codeassist.ndk

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * A plain C or C++ library: a header, an implementation, and nothing Android in it.
 *
 * No manifest, no resources, no Java, and no `android/log.h` in the generated code -- the module is a
 * `java-lib`, and what comes out is a shared library. It is the template for writing C, as opposed to the
 * template for writing an Android app that happens to be written in C, which is [NativeActivityTemplate].
 *
 * The header is generated alongside the source rather than left as an exercise. A one-file starter teaches
 * the wrong shape for a language whose whole compilation model is headers, and it leaves the user with no
 * `.h` open, which is the file this plugin's include handling and header diagnostics are most visible in.
 */
class NativeCppTemplate : ProjectTemplate {

    override val id = TemplateId("ndk-native-cpp")
    override val displayName = "C/C++ Library"
    override val description =
        "A C or C++ shared library, compiled on the device by the bundled clang. No Android in it."
    override val category = TemplateCategory.OTHER
    override val iconId = NdkFileIcons.CPP

    override fun parameters(): List<TemplateParameter> = listOf(
        TemplateParameter.Choice(
            key = NdkTemplateSupport.LANGUAGE,
            label = "Language",
            options = listOf(
                TemplateParameter.Choice.Option(NdkTemplateSupport.LANG_CPP, "C++"),
                TemplateParameter.Choice.Option(NdkTemplateSupport.LANG_C, "C"),
            ),
            help = "Which of the two the starter files are written in. Both compile in the same module: " +
                "the build picks the language from each file's extension.",
        ),
        NdkTemplateSupport.libraryNameParam(
            "The name of the .so that is produced, without the lib prefix, and what a loader is given.",
        ),
        NdkTemplateSupport.standardParam(cppOnly = false),
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val library = NdkTemplateSupport.libraryName(args)
        val cpp = args.string(NdkTemplateSupport.LANGUAGE, NdkTemplateSupport.LANG_CPP) != NdkTemplateSupport.LANG_C
        val standard = args.string(NdkTemplateSupport.STANDARD, if (cpp) "c++17" else "c17")
        val prefix = NdkTemplateSupport.symbolPrefix(library)

        NdkTemplateSupport.scaffoldModule(
            scaffold = scaffold,
            projectName = args.name,
            moduleTypeId = "java-lib",
            ndk = NdkTemplateSupport.withStandard(
                NdkFacet(
                    libraryName = library,
                    // Nothing Android-specific is included, so nothing Android-specific is linked. `log` is
                    // what the activity template needs and this one does not.
                    linkLibraries = emptyList(),
                ),
                standard,
            ),
        )

        val ext = if (cpp) "cpp" else "c"
        scaffold.writeText("app/src/main/cpp/$library.h", header(library, prefix))
        scaffold.writeText("app/src/main/cpp/$library.$ext", if (cpp) cppSource(library, prefix) else cSource(library, prefix))
        scaffold.writeText("README.md", readme(library, ext, standard))
    }

    /**
     * The public header, with the `extern "C"` guard both languages want.
     *
     * In C++ it is what stops the names being mangled, so a loader can find them; in C the guard compiles to
     * nothing. It is in both because a header that is C-only until someone includes it from C++ is a header
     * that breaks on the day the project grows a second file.
     */
    private fun header(library: String, prefix: String): String = """
        #pragma once

        #ifdef __cplusplus
        extern "C" {
        #endif

        /** Adds two numbers. The smallest thing worth checking the plumbing with. */
        int ${prefix}_add(int a, int b);

        /** A string owned by the library, valid for as long as it is loaded. */
        const char* ${prefix}_greeting(void);

        #ifdef __cplusplus
        }  // extern "C"
        #endif
    """.trimIndent() + "\n"

    private fun cppSource(library: String, prefix: String): String = """
        #include "$library.h"

        #include <string>

        namespace {
        // A C++ object with static storage, returned across a C boundary as a pointer into it: the point of
        // the file is that the library is C++ inside and C at the edge.
        const std::string kGreeting = "Built on the device by clang";
        }  // namespace

        int ${prefix}_add(int a, int b) {
            return a + b;
        }

        const char* ${prefix}_greeting(void) {
            return kGreeting.c_str();
        }
    """.trimIndent() + "\n"

    private fun cSource(library: String, prefix: String): String = """
        #include "$library.h"

        int ${prefix}_add(int a, int b) {
            return a + b;
        }

        const char* ${prefix}_greeting(void) {
            /* A string literal has static storage, so returning it is safe for the library's lifetime. */
            return "Built on the device by clang";
        }
    """.trimIndent() + "\n"

    private fun readme(library: String, ext: String, standard: String): String = """
        # $library

        A shared library compiled on the device. The toolchain is a clang and an lld built to RUN on Android
        arm64 and packaged inside the NDK plugin, so nothing here needs a desktop.

        - `app/src/main/cpp/$library.$ext` is the implementation and `$library.h` is its public header.
        - The `[ndk]` table in `app/module.toml` is the build configuration: source directories, ABIs, the
          language standards (this project was created with `$standard`), the STL, optimization, extra flags
          and the libraries to link. Everything in it has a default; the file spells them out so it says what
          can be changed.
        - Building the project writes `app/src/main/jniLibs/arm64-v8a/lib$library.so`.

        Errors appear in the editor as you type, reported by the same clang that builds the code, so the two
        cannot disagree.
    """.trimIndent() + "\n"
}
