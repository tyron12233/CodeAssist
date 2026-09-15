package dev.codeassist.ndk

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.FacetData
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateParameter

/**
 * What the two native templates share: the parameters they both ask for, and the model they both build.
 *
 * They are two templates rather than one with a switch because the two things are not variants of each
 * other. A C++ library is a `java-lib` module with no manifest, no resources and no Android in it; a native
 * activity is an Android application whose only code is C. Folding them together produced a template whose
 * every generated file depended on a radio button, which is the shape a gallery is supposed to replace.
 */
internal object NdkTemplateSupport {

    const val LIBRARY_NAME = "libraryName"
    const val STANDARD = "standard"
    const val LANGUAGE = "language"
    const val MIN_SDK = "minSdk"

    const val LANG_CPP = "cpp"
    const val LANG_C = "c"

    /** What the newest platform the IDE bundles is (`AndroidApiLevels.LATEST`), which a plugin cannot ask. */
    const val COMPILE_SDK = 36

    fun libraryNameParam(help: String) = TemplateParameter.Text(
        key = LIBRARY_NAME,
        label = "Library name",
        default = "native-lib",
        placeholder = "native-lib",
        help = help,
    )

    /** The `-std=` value, offered for both languages because the plain template generates either. */
    fun standardParam(cppOnly: Boolean) = TemplateParameter.Choice(
        key = STANDARD,
        label = "Language standard",
        options = buildList {
            add(TemplateParameter.Choice.Option("c++17", "C++17"))
            add(TemplateParameter.Choice.Option("c++20", "C++20"))
            add(TemplateParameter.Choice.Option("c++14", "C++14"))
            if (!cppOnly) {
                add(TemplateParameter.Choice.Option("c17", "C17"))
                add(TemplateParameter.Choice.Option("c11", "C11"))
            }
        },
        help = "Passed to clang as -std. A C standard applies to .c files and a C++ one to the rest, so a " +
            "project can hold both and each is compiled as what it is.",
    )

    /**
     * The library name, reduced to what a file name and a `-l` argument can both carry.
     *
     * It is used unescaped as a path segment, so a separator has to go: the name reaches
     * `writeText("app/src/main/cpp/$library.cpp")`, and a `/` in it would write outside the module. Leading
     * dots go for the same reason -- a name of `..` is a directory, and one starting with `.` is a hidden
     * file nobody will find in the navigator.
     */
    fun libraryName(args: TemplateArgs): String =
        args.string(LIBRARY_NAME, "native-lib").trim()
            .replace(Regex("[^A-Za-z0-9_.+-]"), "-")
            .trim('-', '.')
            .ifEmpty { "native-lib" }

    /**
     * A C identifier derived from the library name, so the generated header and the generated source agree
     * on the symbols without either of them naming the other's spelling.
     *
     * `native-lib` gives `native_lib`: a hyphen is fine in a file name and in `-lnative-lib`, and is not a
     * character a function name may contain.
     */
    fun symbolPrefix(library: String): String {
        val cleaned = library.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        return if (cleaned.isEmpty() || cleaned.first().isDigit()) "lib_$cleaned" else cleaned
    }

    /** The facet the chosen standard implies, leaving the other language's standard at its default. */
    fun withStandard(facet: NdkFacet, standard: String): NdkFacet =
        if (standard.startsWith("c++")) facet.copy(cppStandard = standard) else facet.copy(cStandard = standard)

    /**
     * Create the project and its single `app` module, with the native source directory declared.
     *
     * Declaring `src/main/cpp` as a content root is the load-bearing line, not a nicety: the IDE resolves a
     * file to its module through the DECLARED roots, so a `.cpp` under an undeclared directory belongs to no
     * module, gets no analysis target and is invisible in the navigator, which renders the declared roots
     * rather than the file system. The plugin repairs an existing project at its first build
     * ([NdkSourceRoots]); a project made here never needs that.
     *
     * The facets go in as [FacetData] rather than through `putFacet`, which would look their codecs up in
     * the store's registry. The `[ndk]` table is this plugin's own, so encoding it directly is the same
     * bytes with no lookup; the `[android]` table belongs to a plugin this one does not link against, and a
     * table it writes by name is the only way to reach it. Both are read back by their owners' codecs.
     */
    fun scaffoldModule(
        scaffold: ProjectScaffold,
        projectName: String,
        moduleTypeId: String,
        ndk: NdkFacet,
        android: Map<String, Any?>? = null,
    ) {
        scaffold.workspace.beginModification().apply {
            addProject(projectName, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == projectName }.beginModification().apply {
            addModule(MODULE, scaffold.moduleType(moduleTypeId)).apply {
                languageLevel = scaffold.languageLevel
                for (dir in ndk.sourceDirs) addContentRoot("main", dir, setOf(ContentRole.SOURCE))
                putFacetData(FacetData("ndk", NdkFacetCodec.encode(ndk)))
                android?.let { putFacetData(FacetData("android", it)) }
            }
            commit()
        }
    }

    /** The module both templates generate into. One module, because a starter with two is a lesson in neither. */
    const val MODULE = "app"
}
