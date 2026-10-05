package dev.codeassist.ndk

import dev.ide.plugin.ui.NewFileContent
import dev.ide.plugin.ui.NewFileTemplate
import dev.ide.plugin.ui.UiRegistration
import java.io.File

/**
 * What the C/C++ "New" entries create, as plain functions of a name, shared by the New-menu templates (SPI
 * 3.1.0) and the file-tree actions an older IDE gets instead.
 */
object CppNewFiles {

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** A C++ identifier from what the user typed, or an [IllegalArgumentException] saying what is wrong. */
    fun className(input: String): String {
        val name = input.trim().removeSuffix(".h").removeSuffix(".hpp").removeSuffix(".cpp")
        require(name.isNotEmpty()) { "Enter a class name" }
        require(IDENTIFIER.matches(name)) { "A class name is letters, digits and _, and cannot start with a digit" }
        return name
    }

    /** `Widget` gives `Widget.h` and `Widget.cpp`, the source including the header. */
    fun cppClass(name: String): List<Pair<String, String>> = listOf(
        "$name.h" to """
            #pragma once

            class $name {
            public:
                $name();
                ~$name();
            };
        """.trimIndent() + "\n",
        "$name.cpp" to """
            #include "$name.h"

            $name::$name() = default;

            $name::~$name() = default;
        """.trimIndent() + "\n",
    )

    /** A source file: `.cpp` unless the name already says `.c`, `.cc` or `.cpp`. */
    fun source(input: String): Pair<String, String> {
        val name = fileName(input, "cpp", setOf("c", "cc", "cpp", "cxx"))
        val text = if (name.endsWith(".c")) "#include <stdio.h>\n\n" else "#include <cstdio>\n\n"
        return name to text
    }

    /** A header with an include guard the compiler understands without a macro to keep unique. */
    fun header(input: String): Pair<String, String> = fileName(input, "h", setOf("h", "hpp", "hh")) to "#pragma once\n\n"

    private fun fileName(input: String, defaultExtension: String, allowed: Set<String>): String {
        val name = input.trim()
        require(name.isNotEmpty()) { "Enter a file name" }
        require(name.none { it in "/\\:*?\"<>|" }) { "A file name cannot contain / \\ : * ? \" < > |" }
        return if (name.substringAfterLast('.', "").lowercase() in allowed) name else "$name.$defaultExtension"
    }

    /**
     * Whether [dir] is a place C/C++ belongs: a `cpp`, `jni`, `c` or `native` directory or one under it, or
     * one already holding C/C++ files. Kept narrow so a Java package does not grow C++ entries.
     */
    fun isNativeDirectory(dir: String): Boolean {
        val segments = dir.replace('\\', '/').split('/')
        if (segments.any { it in NATIVE_DIRS }) return true
        val files = runCatching { File(dir).list()?.toList() }.getOrNull().orEmpty()
        return files.any { f -> NATIVE_EXTENSIONS.any { f.endsWith(it) } }
    }

    private val NATIVE_DIRS = setOf("cpp", "jni", "c", "native")
    private val NATIVE_EXTENSIONS = listOf(".c", ".cpp", ".cc", ".cxx", ".h", ".hpp")
}

/** The New-menu entries, on a host with SPI 3.1.0. */
internal object CppNewFileTemplates {

    /** Register them; false when this host has no New-menu templates (it predates SPI 3.1.0). */
    fun register(ui: UiRegistration): Boolean = try {
        ui.newFileTemplate(
            NewFileTemplate(
                id = "dev.codeassist.ndk.cppClass",
                title = "C++ Class",
                nameLabel = "Class name",
                iconId = NdkFileIcons.CPP,
                appliesTo = CppNewFiles::isNativeDirectory,
                files = { _, name -> CppNewFiles.cppClass(CppNewFiles.className(name)).map { NewFileContent(it.first, it.second) } },
            ),
        )
        ui.newFileTemplate(
            NewFileTemplate(
                id = "dev.codeassist.ndk.source",
                title = "C/C++ Source File",
                nameLabel = "File name",
                iconId = NdkFileIcons.CPP,
                appliesTo = CppNewFiles::isNativeDirectory,
                files = { _, name -> CppNewFiles.source(name).let { listOf(NewFileContent(it.first, it.second)) } },
            ),
        )
        ui.newFileTemplate(
            NewFileTemplate(
                id = "dev.codeassist.ndk.header",
                title = "C/C++ Header File",
                nameLabel = "File name",
                iconId = NdkFileIcons.HEADER,
                appliesTo = CppNewFiles::isNativeDirectory,
                files = { _, name -> CppNewFiles.header(name).let { listOf(NewFileContent(it.first, it.second)) } },
            ),
        )
        true
    } catch (e: LinkageError) {
        false
    }
}
