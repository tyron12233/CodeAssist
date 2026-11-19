// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.ui

/**
 * An entry in the file tree's **New ▸** menu: a named kind of file a plugin knows how to start, such as a C++
 * class (a header and its source), a shader, or a config file with its required keys already in place.
 *
 * The host asks for a name in its own dialog, then writes what [files] returns, relative to the directory the
 * menu was opened on, and opens the first one. A file that already exists is left alone rather than replaced.
 *
 * ```
 * ui.newFileTemplate(NewFileTemplate(
 *     id = "ndk.cppClass",
 *     title = "C++ Class",
 *     nameLabel = "Class name",
 *     iconId = "cpp",
 *     appliesTo = { dir -> "/src/" in dir },
 *     files = { _, name -> listOf(NewFileContent("$name.h", header(name)), NewFileContent("$name.cpp", source(name))) },
 * ))
 * ```
 *
 * A template that starts more than one sort of file under one menu row (a class, an interface, an enum)
 * gives them as [kinds] instead of [files]; the dialog then offers them as a row of choices above the name,
 * the way the IDE's own New Kotlin file does:
 *
 * ```
 * ui.newFileTemplate(NewFileTemplate(
 *     id = "csharp.file",
 *     title = "C# File",
 *     kinds = listOf(
 *         NewFileKind("class", "Class") { _, name -> listOf(NewFileContent("$name.cs", csClass(name))) },
 *         NewFileKind("interface", "Interface") { _, name -> listOf(NewFileContent("$name.cs", csInterface(name))) },
 *     ),
 * ))
 * ```
 *
 * Since SPI 3.1.0; [kinds] since 3.6.0.
 */
class NewFileTemplate(
    /** Stable id, unique across plugins; prefix it with the plugin's own. */
    val id: String,

    /** The menu row and the dialog's title, e.g. "C++ Class". */
    val title: String,

    /** What the name field asks for, e.g. "Class name". */
    val nameLabel: String = "Name",

    /**
     * The icon drawn on the row: an id registered through [UiRegistration.fileIcon] (or a built-in tree icon
     * id). Null draws the generic new-file glyph.
     */
    val iconId: String? = null,

    /** Whether to offer this template in the menu for the directory at [dirPath] (absolute). */
    val appliesTo: (dirPath: String) -> Boolean = { true },

    /**
     * The files to create for [name] in [dirPath], relative paths using `/`. Throw [IllegalArgumentException]
     * with a message the user can act on to refuse a name ("A class name cannot start with a digit"); the
     * host shows the message and keeps the dialog open.
     */
    val files: (dirPath: String, name: String) -> List<NewFileContent>,
) {
    /**
     * The sorts of file this template starts, offered as a choice in the dialog; the first is selected when it
     * opens. Empty for a template that starts one sort, which is then [files]. Since SPI 3.6.0.
     */
    var kinds: List<NewFileKind> = emptyList()
        private set

    /**
     * A template with a choice of [kinds] (at least one). [files] is then the first kind's, which is what a
     * host older than 3.6.0 would have used, had it been able to load this constructor: it cannot, and the call
     * throws a `LinkageError` there. Since SPI 3.6.0.
     */
    constructor(
        id: String,
        title: String,
        kinds: List<NewFileKind>,
        nameLabel: String = "Name",
        iconId: String? = null,
        appliesTo: (dirPath: String) -> Boolean = { true },
    ) : this(id, title, nameLabel, iconId, appliesTo, files = { dirPath, name -> kinds.first().files(dirPath, name) }) {
        require(kinds.isNotEmpty()) { "A template with kinds needs at least one" }
        require(kinds.map { it.id }.toSet().size == kinds.size) { "Kind ids must be unique within a template" }
        this.kinds = kinds.toList()
    }
}

/**
 * One choice in a [NewFileTemplate]'s dialog: [label] on its chip ("Interface"), and [files] for what a name
 * turns into when it is the one selected, under the same rules as [NewFileTemplate.files]. [id] is stable and
 * unique within the template. Since SPI 3.6.0.
 */
class NewFileKind(
    val id: String,
    val label: String,
    val files: (dirPath: String, name: String) -> List<NewFileContent>,
)

/** One file a [NewFileTemplate] creates: [relativePath] under the target directory, holding [text]. */
class NewFileContent(val relativePath: String, val text: String)
