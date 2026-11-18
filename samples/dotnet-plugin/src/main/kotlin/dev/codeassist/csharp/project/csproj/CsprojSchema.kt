package dev.codeassist.csharp.project.csproj

/**
 * Describes the project properties and items supported by this plugin.
 *
 * Used by completion and [CsprojCheck] to provide allowed values and validation rules.
 * This is not the complete MSBuild vocabulary. Properties not listed here are simply
 * not offered by completion or checked by this plugin.
 */
internal object CsprojSchema {

    /** Describes a property, its allowed values, validation kind, and completion documentation. */
    class Property(
        val name: String,
        val doc: String,
        val values: List<String> = emptyList(),
        val kind: Kind = Kind.TEXT,
    )

    enum class Kind { TEXT, BOOLEAN, ENUM, INTEGER, LANG_VERSION, FRAMEWORK }

    /** Creates a boolean property that accepts `true` or `false`. */
    private fun bool(name: String, doc: String) =
        Property(name, doc, listOf("true", "false"), Kind.BOOLEAN)

    // Properties

    val PROPERTIES: List<Property> = listOf(
        Property("OutputType", "Exe runs, Library is built only", listOf("Exe", "Library", "WinExe"), Kind.ENUM),
        Property("TargetFramework", "The framework the project builds for, such as net10.0", kind = Kind.FRAMEWORK),
        Property("AssemblyName", "The name of the built assembly"),
        Property("RootNamespace", "The namespace new files get"),
        Property("LangVersion", "The C# language version: latest, preview, 12, 13 ...", kind = Kind.LANG_VERSION),
        Property("Nullable", "Nullable reference types", listOf("enable", "disable", "warnings", "annotations"), Kind.ENUM),
        Property("ImplicitUsings", "The usings every file gets", listOf("enable", "disable", "true", "false"), Kind.ENUM),
        Property("StartupObject", "The class whose Main starts the program (Module Settings > Run wins)"),
        bool("AllowUnsafeBlocks", "Allow unsafe code"),
        bool("Optimize", "Compile with optimizations"),
        bool("TreatWarningsAsErrors", "Every warning is an error"),
        bool("CheckForOverflowUnderflow", "Integer arithmetic overflow throws"),
        bool("EnableDefaultCompileItems", "false: only the Compile items are compiled"),
        bool("GenerateDocumentationFile", "Write the XML documentation file"),
        bool("Deterministic", "The same input gives the same output"),
        bool("AppendTargetFrameworkToOutputPath", "Put the framework name in the output folder"),
        bool("InvariantGlobalization", "Do not use culture data"),
        Property("WarningsAsErrors", "Warnings that are errors, separated by `;`"),
        Property("WarningsNotAsErrors", "Warnings that stay warnings, separated by `;`"),
        Property("NoWarn", "Warnings to hide, separated by `;`"),
        Property("WarningLevel", "How many warnings: 0 to 9", kind = Kind.INTEGER),
        Property("DefineConstants", "Symbols for #if, separated by `;`"),
        Property("OutDir", "The output folder"),
        Property(
            "DebugType",
            "Debug symbols: portable, embedded, full, pdbonly or none",
            listOf("portable", "embedded", "full", "pdbonly", "none"),
            Kind.ENUM,
        ),
        Property(
            "PlatformTarget",
            "The CPU the DLL is for",
            listOf("AnyCPU", "x86", "x64", "arm", "arm64", "anycpu32bitpreferred"),
            Kind.ENUM,
        ),
        bool("GenerateAssemblyInfo", "false: the project writes its own assembly attributes"),
        Property("Version", "The package and assembly version"),
        Property("AssemblyVersion", "The assembly version, four numbers"),
        Property("FileVersion", "The file version, four numbers"),
        Property("InformationalVersion", "The version shown to people, any text"),
        Property("Authors", "The authors (the company, when none is set)"),
        Property("Product", "The product in the assembly information"),
        Property("Copyright", "The copyright in the assembly information"),
        Property("AssemblyTitle", "The title in the assembly information"),
        Property("Company", "The company in the assembly information"),
        Property("Description", "The description in the assembly information"),
    )

    /** Describes an item, its supported attributes, and its completion documentation. */
    class Item(
        val name: String,
        val doc: String,
        val attributes: List<String>,
    )

    val ITEMS: List<Item> = listOf(
        Item("PackageReference", "A NuGet package", listOf("Include", "Version")),
        Item("ProjectReference", "Another project, built first and compiled against", listOf("Include")),
        Item("Reference", "A DLL", listOf("Include", "HintPath", "Private")),
        Item("Compile", "A source file or a pattern of them", listOf("Include", "Remove", "Exclude")),
        Item("Using", "A using every file gets", listOf("Include", "Remove", "Alias", "Static")),
        Item("EmbeddedResource", "A file embedded in the assembly", listOf("Include", "Remove")),
        Item("None", "A file shown in the project and not built", listOf("Include", "Remove")),
    )

    /** Elements that hold properties or items, plus other elements allowed directly under Project. */
    val GROUPS = listOf("PropertyGroup", "ItemGroup", "Import", "Target", "Choose")

    // Lookups

    fun property(name: String): Property? = PROPERTIES.firstOrNull { it.name == name }

    fun item(name: String): Item? = ITEMS.firstOrNull { it.name == name }
}