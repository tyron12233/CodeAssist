// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.core.plugins

/**
 * What a built-in plugin tells the person looking at it on the Plugins screen, in the words of someone using the IDE
 * rather than someone writing a plugin. A manifest's own `description` is one line a developer wrote ("Core file-icon
 * classifier and base file-type mappings"); this is the page: what it is for, what it adds, and what happens without it.
 *
 * - [summary]: one plain sentence, the row's subtitle. Replaces the manifest's description for a built-in.
 * - [about]: a short paragraph (blank lines separate paragraphs): what this part of the IDE does and why it exists.
 * - [adds]: what turning it on gets you, one concrete thing per line (a feature, a file type, a template, a check).
 * - [ifOff]: for a plugin that can be turned off, what stops working. For an essential plugin (no switch), why the IDE
 *   cannot do without it.
 */
internal class BuiltInPluginDocs(
    val summary: String,
    val about: String,
    val adds: List<String>,
    val ifOff: String,
)

/**
 * The page for every plugin in [BuiltInPlugins.assemble], by plugin id. A test fails when a built-in has no entry here,
 * so a new built-in plugin cannot ship with only a developer's one-liner.
 */
internal val BUILT_IN_PLUGIN_DOCS: Map<String, BuiltInPluginDocs> = mapOf(

    "platform" to BuiltInPluginDocs(
        summary = "The basics every project needs: file icons in the project tree, and Markdown files.",
        about = "The foundation the other plugins build on. It supplies the default icons shown beside files and folders in the " +
            "project tree, and it tells the IDE that .md and .markdown files are Markdown documents.",
        adds = listOf(
            "Default icons for files and folders in the project tree",
            "Recognizes .md and .markdown files as Markdown, so they open as text and are never checked as if they were Java",
        ),
        ifOff = "Without it the project tree would have no file icons and a Markdown file would be mistaken for source code, " +
            "and the other plugins load on top of it.",
    ),

    "keymap" to BuiltInPluginDocs(
        summary = "Keyboard shortcuts: the editor's defaults, plugins' shortcuts, and your own changes.",
        about = "Keeps every keyboard shortcut in one table, so the editor, the plugins and your own changes never fight over a key. " +
            "A shortcut you rebind wins over a plugin's, and a plugin's wins over the editor's default.",
        adds = listOf(
            "The editor's built-in shortcuts: moving, selecting, deleting, commenting, completing",
            "Shortcuts that plugins add for their own actions",
            "Your own rebindings, applied on top of both",
        ),
        ifOff = "The editor falls back to its built-in shortcuts. Shortcuts you changed, and the ones plugins add, stop working.",
    ),

    "jdt-language" to BuiltInPluginDocs(
        summary = "Teaches the IDE what a .java file is, and keeps the Eclipse compiler for building Java.",
        about = "Links .java files to the Java language, which is how every other Java feature knows which files to handle. " +
            "The Eclipse Java compiler (ecj) that builds your Java code and reports its compile errors belongs with this group of " +
            "features; the editing itself (completion, navigation) is done by Java Language (IntelliJ PSI).",
        adds = listOf(
            ".java files recognized as Java",
            "The Eclipse compiler (ecj) as the Java build compiler",
        ),
        ifOff = "The other language plugins load after it, and without the .java file type none of the Java features " +
            "would know which files are theirs.",
    ),

    "java-psi-language" to BuiltInPluginDocs(
        summary = "Java editing: completion, navigation, rename and error marks, using IntelliJ's Java parser.",
        about = "The Java editor. It reads your code with IntelliJ's own Java parser and resolves names and types the way IntelliJ does, " +
            "so the suggestions and the red marks match what you would see in IntelliJ IDEA.",
        adds = listOf(
            "Code completion for Java",
            "Go to definition and other navigation",
            "Syntax and type-aware highlighting, code folding, parameter hints and inlay hints",
            "Rename of classes, methods, fields and variables across the project",
            "Errors for names that cannot be resolved",
        ),
        ifOff = "It is the only Java editor the IDE has, and the fallback the IDE uses for any language that has no editor of its own.",
    ),

    "xml-language" to BuiltInPluginDocs(
        summary = "XML editing for Android layouts, values, the manifest and drawables.",
        about = "The XML editor. Its parser keeps working while a file is half-typed, so completion and structure stay available in the " +
            "middle of an edit instead of disappearing whenever a tag is unclosed.",
        adds = listOf(
            ".xml files recognized and edited as XML",
            "A forgiving parser that copes with incomplete XML while you type",
            "Code completion for tags and attributes in layouts, values, the manifest and drawables",
        ),
        ifOff = "XML files lose XML completion and structure and open as plain text. Android layouts and the manifest are much harder to edit.",
    ),

    "kotlin-language" to BuiltInPluginDocs(
        summary = "Kotlin editing: parsing, completion and navigation.",
        about = "The Kotlin editor. It parses .kt and .kts files in the IDE itself, which is what drives Kotlin completion and navigation. " +
            "It only affects editing: compiling Kotlin is done by the build, not by this plugin.",
        adds = listOf(
            ".kt and .kts files recognized as Kotlin",
            "Kotlin code completion",
            "Go to definition and other navigation in Kotlin code",
        ),
        ifOff = "Kotlin files open without Kotlin completion or navigation. Builds still compile Kotlin.",
    ),

    "java-support" to BuiltInPluginDocs(
        summary = "Java library modules, and the Java templates in Create Project.",
        about = "What you need to start and organize a plain Java project: the Java library module type, and ready-made project templates.",
        adds = listOf(
            "The Java library module type",
            "Create Project templates: Java console app, Java library, Swing app and Swing canvas",
        ),
        ifOff = "The Java templates disappear from Create Project and Java library modules are no longer recognized.",
    ),

    "kotlin-support" to BuiltInPluginDocs(
        summary = "Everything around Kotlin projects: templates, Compose, serialization and Parcelize.",
        about = "The parts of Kotlin development that are not the editor. It teaches the IDE about Kotlin modules (including multiplatform), " +
            "bundles the Kotlin compiler plugins that Compose, kotlinx.serialization and Parcelize need, and makes the code they generate " +
            "visible to the editor so completion and checks know about it.",
        adds = listOf(
            "The Kotlin compiler plugins for Jetpack Compose, kotlinx.serialization and Parcelize",
            "Editor support for what they generate: Foo.serializer() and Foo.CREATOR show up in completion",
            "Compose Multiplatform resources: generates the Res class and bundles the resource files",
            "A BuildConfig object for Kotlin and multiplatform modules",
            "The multiplatform marker on modules in the project tree, and the [kotlin] module setting",
            "Kotlin classes that Java code can see, so Java and Kotlin in one module work together",
            "Kotlin project templates in Create Project",
        ),
        ifOff = "Compose, serialization and Parcelize stop compiling, Kotlin templates disappear, and Java code can no longer see Kotlin classes.",
    ),

    "ksp-support" to BuiltInPluginDocs(
        summary = "Runs annotation processors such as Room at build time.",
        about = "KSP (Kotlin Symbol Processing) writes code for you from annotations: Room, for example, generates your database classes. " +
            "This plugin runs the processors that come with the IDE inside the IDE's own Kotlin compiler, then compiles and indexes what " +
            "they generate just like code you wrote. A processor runs only when its library is a dependency you declared in the module, " +
            "not when it arrives indirectly through another library.",
        adds = listOf(
            "Runs the bundled KSP processors, including Room, during the build",
            "Generated sources show up in the editor, in completion and in search",
            "Warns up front when your project's library version differs from the processor the IDE ships",
        ),
        ifOff = "Code generated from annotations is no longer produced, so a project that uses Room or another processor fails to build " +
            "on the classes it expected to find.",
    ),

    "gradle-support" to BuiltInPluginDocs(
        summary = "Opens Gradle and Amper projects, and writes dependency changes back to their build files.",
        about = "Lets you open a project that already has a Gradle or Amper build. It reads the build scripts without running Gradle, so a " +
            "project opens right away. When you add a dependency in the IDE, it is written back into the build file, which stays the " +
            "source of truth.",
        adds = listOf(
            "Opens Gradle projects by reading build.gradle and build.gradle.kts",
            "Opens JetBrains Amper projects from project.yaml and module.yaml",
            "Opens a build that exported its own model (.platform/gradle-model.json), for builds too dynamic to read from the script",
            "Writes added and removed dependencies back into the Gradle build file",
            "Kotlin Multiplatform templates in Create Project",
        ),
        ifOff = "Gradle and Amper folders are no longer recognized: they open as empty workspaces instead of as projects.",
    ),

    "blocks" to BuiltInPluginDocs(
        summary = "A visual, Scratch-style view of your code, switchable per file.",
        about = "Shows the same code as blocks instead of text, for people who find that easier than typing, or for seeing a file's " +
            "structure at a glance. It is a view of the file, not a copy: it is the same code in both views.",
        adds = listOf(
            "The Code / Blocks switch on Java and Kotlin files",
            "A block layout for Java and one for Kotlin",
        ),
        ifOff = "The Blocks view disappears. Your code is unaffected.",
    ),

    "android-support" to BuiltInPluginDocs(
        summary = "Everything that makes a project an Android project.",
        about = "Teaches the IDE about Android apps and libraries: their settings, build variants, resources, and the classes Android " +
            "generates for you. It is what lets code refer to R.id.button or a view binding and have the editor know what that is.",
        adds = listOf(
            "Android app and library module types, with variants and Android settings in module.toml",
            "Icons for Android resource folders and files in the project tree",
            "The classes Android generates: R, BuildConfig, view binding and AIDL interfaces",
            "Checks .aidl files while you type, with the same parser the build uses",
            "ProGuard / R8 rule files (.pro) and .aidl files recognized, so they are not mistaken for broken Java",
            "Android project templates and Compose sample games in Create Project",
        ),
        ifOff = "Android modules are not recognized, and code that uses R, BuildConfig or view binding stops resolving.",
    ),

    "samples" to BuiltInPluginDocs(
        summary = "Three small apps to start from: Calculator, Notes and Weather.",
        about = "Complete working example projects, useful to see how a real app is put together, or to experiment with without starting from nothing.",
        adds = listOf("Calculator, Notes and Weather in the Create Project gallery"),
        ifOff = "The samples disappear from the Create Project gallery. Projects you already created from them are not affected.",
    ),

    "plugin-development" to BuiltInPluginDocs(
        summary = "Write your own plugins for the IDE.",
        about = "Everything for building a plugin as its own app: a project template to start from, and checks that catch a mistake in " +
            "the plugin's manifest while you are still typing it, instead of when someone installs the plugin.",
        adds = listOf(
            "A Create Project template for a CodeAssist plugin packaged as its own app",
            "Checks on the plugin manifest in the editor, using the same rules the loader applies",
            "Completion in the manifest, including the ids of the plugins installed in the IDE",
        ),
        ifOff = "The plugin template and the manifest checks and completion disappear. Plugins you already built keep working.",
    ),

    "completion-builtins" to BuiltInPluginDocs(
        summary = "Extra suggestions and smarter ordering in the completion list, for every language.",
        about = "The helpers that work across languages and make the list better than the language's own suggestions alone: words already in " +
            "your file, shortcuts that wrap an expression, your own templates, and an ordering that learns from what you pick.",
        adds = listOf(
            "Words already in the file offered as a fallback suggestion",
            "Postfix templates: type .val, .if or .forEach after an expression to wrap it",
            "Your own live templates",
            "Suggestions you accept often rise to the top",
            "Members your project uses a lot rank higher",
        ),
        ifOff = "The list loses those extras and the learned ordering. The language's own completion still works.",
    ),

    "indexing" to BuiltInPluginDocs(
        summary = "Builds the searchable index behind completion, go to, and search.",
        about = "Reads your project and its libraries once and keeps a searchable record of every class, method, field and resource, " +
            "so looking something up is instant instead of a scan of every file. It runs in the background and keeps itself up to date as you edit.",
        adds = listOf(
            "Java and Kotlin classes, packages, members and top-level functions, in your code and in libraries",
            "Which classes extend which, and where annotations are used",
            "Android resources and where they are referenced",
            "Where each member is used in your project, so frequently used ones are suggested first",
        ),
        ifOff = "Completion, Go to symbol, find usages and search stop finding classes, members and resources.",
    ),

    "jdt-analysis" to BuiltInPluginDocs(
        summary = "Java errors and warnings from the Eclipse compiler, with quick fixes.",
        about = "Checks your Java code as you type using the Eclipse compiler, underlines what it would refuse to compile, and offers fixes.",
        adds = listOf(
            "Compile errors and warnings underlined in Java files",
            "Quick fixes (the lightbulb) for those problems",
            "Editor intentions: small improvements offered even where nothing is wrong",
        ),
        ifOff = "Java files lose their compile-level error marks and quick fixes while you edit. The build still reports errors.",
    ),

    "java-psi-analysis" to BuiltInPluginDocs(
        summary = "Quick fixes for common Java mistakes: wrong type, unhandled exception.",
        about = "Adds one-tap fixes to the Java editor for two mistakes that come up all the time: a value of the wrong type, and an exception the code does not handle.",
        adds = listOf(
            "A fix for a type mismatch",
            "A fix for an unhandled exception",
        ),
        ifOff = "Those two fixes are no longer offered. The errors are still marked.",
    ),

    "kotlin-analysis" to BuiltInPluginDocs(
        summary = "Kotlin errors and warnings, plus Import and Implement members.",
        about = "Checks Kotlin code as you type and underlines what is wrong, and offers fixes for the most common problems.",
        adds = listOf(
            "Errors and warnings underlined in Kotlin files",
            "Add import for a name that is not imported yet",
            "Implement members: generate the methods a class has to provide",
        ),
        ifOff = "Kotlin files lose their error marks and those two actions.",
    ),

    "package-mismatch" to BuiltInPluginDocs(
        summary = "Warns when a file's package does not match the folder it is in.",
        about = "A Java or Kotlin file whose package line says one thing while its folder says another causes confusing errors later. " +
            "This catches it straight away and offers to correct it.",
        adds = listOf("A warning on a Java or Kotlin file whose package does not match its directory, with a one-tap fix"),
        ifOff = "A misplaced package is no longer flagged.",
    ),

    "manifest-permission-fix" to BuiltInPluginDocs(
        summary = "Adds the permission an Android call needs to the manifest, in one tap.",
        about = "Some Android calls only work if the app has declared a permission. When your code uses one without it, this offers to add it " +
            "to the module's AndroidManifest.xml for you.",
        adds = listOf("A quick fix that adds the missing <uses-permission> line to AndroidManifest.xml"),
        ifOff = "The fix is no longer offered. You add the permission to the manifest yourself.",
    ),

    "missing-library-fix" to BuiltInPluginDocs(
        summary = "Offers to add the library an unresolved name comes from.",
        about = "When code refers to a class, import or layout tag that exists in a library you have not added, this finds the library and " +
            "offers to add it to the module, instead of leaving you to look up the dependency.",
        adds = listOf(
            "A quick fix that adds the library for an unresolved import, class or layout tag",
            "A fix for R references that belong to a library's own R class",
        ),
        ifOff = "The fix is no longer offered. You add the dependency yourself from the Dependencies screen.",
    ),

    "xml-analysis" to BuiltInPluginDocs(
        summary = "Checks Android XML for broken references and mistakes, with fixes.",
        about = "Reads your layouts and resources as you edit them and flags what Android would reject or what is likely a mistake.",
        adds = listOf(
            "References to resources that do not exist, such as @string/missing",
            "Tags and views that cannot be found",
            "Hardcoded text that should be a string resource",
            "Required attributes that are missing",
            "Quick fixes for each of these",
        ),
        ifOff = "XML files lose those checks and fixes while you edit.",
    ),

    "android-xml" to BuiltInPluginDocs(
        summary = "Android XML helpers, such as migrating a layout to AppCompat.",
        about = "Small actions for Android layouts that are not about errors but about moving a layout forward.",
        adds = listOf("The AppCompat migration action, offered in modules that use AppCompat"),
        ifOff = "The AppCompat migration action is no longer offered.",
    ),

    "ide-core-services" to BuiltInPluginDocs(
        summary = "The IDE's own engine: analysis, building, search, dependencies and signing.",
        about = "The services the whole IDE runs on. Every other plugin, and the screens you use, ask these services to analyze code, run a " +
            "build, search the project, manage modules and dependencies, sign an app, or reanalyze after a change.",
        adds = listOf(
            "Code analysis for each module",
            "Building and running, and stopping a build",
            "Search across the project and its libraries",
            "Modules and dependencies, and syncing the project",
            "Signing, resources, refactoring (rename and move) and the Compose preview",
        ),
        ifOff = "The IDE cannot analyze, build or search without it.",
    ),

    "interpreter" to BuiltInPluginDocs(
        summary = "Runs your project's code without building it, for previews.",
        about = "Gives plugins a way to run the open project's code directly, without compiling and installing it, which is what makes a live " +
            "preview possible. What a preview may do is limited by the restrictions you chose for previews.",
        adds = listOf(
            "A service that plugins use to run your project's code in a preview or a run of their own",
            "Respects the preview restrictions you set, so a preview cannot do more than you allowed",
        ),
        ifOff = "Features that run your code in a preview stop working until it is turned back on.",
    ),

    "ide-core-actions" to BuiltInPluginDocs(
        summary = "Run, Stop build and Re-index in the command palette.",
        about = "The commands for the actions you reach for most: starting a build, stopping one, and rebuilding the index when it seems out of date.",
        adds = listOf(
            "Run build",
            "Stop build",
            "Re-index the project",
        ),
        ifOff = "Those three commands disappear from the command palette. The Run button and the menus still work.",
    ),

    "editor-text-actions" to BuiltInPluginDocs(
        summary = "Comment, move and sort lines, and move a declaration to its own file.",
        about = "The editing actions that work on whole lines, plus one that tidies a file by moving a class or function into a file of its own.",
        adds = listOf(
            "Comment and uncomment lines",
            "Move lines up and down",
            "Sort lines",
            "Move a declaration to its own file",
        ),
        ifOff = "Those actions disappear from the editor.",
    ),

    "agent" to BuiltInPluginDocs(
        summary = "An AI coding assistant that can read your code, edit files and run tools, with your permission.",
        about = "A chat beside the editor, using an AI provider you set up with your own key. It can answer questions about your project and, " +
            "when you allow it, read and edit files and run tools. What it may do without asking is set by a permission policy you control.",
        adds = listOf(
            "A chat panel",
            "Ask AI: send the selected code, or the whole file, to the chat with your question",
            "A settings page to choose your AI provider and enter your key",
            "A permission policy that decides what the assistant may do on its own and what it must ask about",
        ),
        ifOff = "The chat, Ask AI and the AI settings disappear. Nothing is sent to any AI provider.",
    ),

    "vcs" to BuiltInPluginDocs(
        summary = "Git for your projects: changes, commits, branches, history and GitHub sign-in.",
        about = "Version control built into the IDE. See what you changed, commit it, switch and merge branches and browse history, and sign in " +
            "to GitHub to work with remote repositories.",
        adds = listOf(
            "A Changes view with the files you modified",
            "Commit, branches and history",
            "GitHub sign-in",
            "A settings page for your Git name and email",
        ),
        ifOff = "The Git screens and actions disappear. Your repositories are untouched, and you can still use Git from elsewhere.",
    ),

    "android-layout-editor" to BuiltInPluginDocs(
        summary = "Design Android layouts visually.",
        about = "A visual editor for layout XML. It shows a live render of the layout that you can select and change, next to the component " +
            "tree and every attribute the selected view accepts. The XML and the design are the same file, so edits in either appear in both.",
        adds = listOf(
            "A live render of your layout",
            "Select a view in the render or in the component tree",
            "Every attribute the selected view accepts, with its current value",
            "Edits made visually are written to the layout XML",
        ),
        ifOff = "The design view disappears; layout XML still opens in the code editor.",
    ),
)
