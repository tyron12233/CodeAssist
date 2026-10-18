package dev.ide.android.support

/**
 * The generated `BuildConfig` class, as AGP writes it: the build's own constants, then the module's
 * `buildConfigField`s. One computation for the build (which compiles the source [render] returns) and the
 * editor (which shows the same fields as a synthetic class before any build), so the two cannot disagree.
 */
object BuildConfigs {

    /** One constant in the class: its Java [type], [name] and initializer [value]. */
    data class Field(val type: String, val name: String, val value: String, val comment: String? = null)

    /**
     * [variant]'s fields, in AGP's order and precedence.
     *
     * Built-ins first: `DEBUG`, then `APPLICATION_ID` for an app or `LIBRARY_PACKAGE_NAME` for a library,
     * `BUILD_TYPE`, `FLAVOR` (and `FLAVOR_<dimension>` per dimension when there are several), and for an app
     * `VERSION_CODE`/`VERSION_NAME`. Then the custom fields: `defaultConfig`'s, overridden by name by each
     * flavor's (the first dimension wins between flavors), overridden by the build type's.
     *
     * [versionCode]/[versionName] are the variant's effective values, which the caller composes (a flavor's
     * versionName, the build type's suffix, a manifest-declared code when the DSL sets none).
     */
    fun fields(
        facet: AndroidFacet,
        variant: AndroidVariant,
        versionCode: Int,
        versionName: String,
    ): List<Field> {
        val buildType = facet.buildType(variant.buildTypeName)
        val debuggable = buildType?.debuggable ?: variant.buildTypeName.equals("debug", ignoreCase = true)
        val out = ArrayList<Field>()
        // `Boolean.parseBoolean` rather than a literal, as AGP writes it: `DEBUG` is then not a compile-time
        // constant, so a library's `if (BuildConfig.DEBUG)` is not folded into whichever variant compiled it.
        out += Field("boolean", "DEBUG", "Boolean.parseBoolean(\"$debuggable\")")
        if (facet.isApplication) out += Field("String", "APPLICATION_ID", quote(AndroidVariants.applicationId(facet, variant)))
        else out += Field("String", "LIBRARY_PACKAGE_NAME", quote(facet.namespace))
        out += Field("String", "BUILD_TYPE", quote(variant.buildTypeName))
        if (variant.flavorNames.isNotEmpty() || facet.isApplication) {
            out += Field("String", "FLAVOR", quote(flavorName(variant.flavorNames)))
        }
        if (variant.flavorNames.size > 1) {
            for (flavor in variant.flavorNames) {
                val dimension = facet.productFlavors.firstOrNull { it.name == flavor }?.dimension ?: continue
                out += Field("String", "FLAVOR_$dimension", quote(flavor))
            }
        }
        if (facet.isApplication) {
            out += Field("int", "VERSION_CODE", versionCode.toString())
            out += Field("String", "VERSION_NAME", quote(versionName))
        }

        // Custom fields, lowest precedence first so a later layer replaces an earlier one's entry by name.
        val custom = LinkedHashMap<String, Field>()
        fun layer(fields: List<BuildConfigField>, from: String) {
            for (f in fields) custom[f.name] = Field(f.type, f.name, f.value, "Field from $from.")
        }
        layer(facet.buildConfigFields, "default config")
        for (flavor in variant.flavorNames.reversed()) {
            facet.productFlavors.firstOrNull { it.name == flavor }?.let { layer(it.buildConfigFields, "product flavor: $flavor") }
        }
        buildType?.let { layer(it.buildConfigFields, "build type: ${it.name}") }
        // A custom field may redeclare a built-in (some projects override VERSION_NAME): the custom one wins.
        val customNames = custom.keys
        return out.filter { it.name !in customNames } + custom.values
    }

    /** The `BuildConfig.java` AGP would write for [packageName] and [fields]. */
    fun render(packageName: String, fields: List<Field>): String = buildString {
        appendLine("/**")
        appendLine(" * Automatically generated file. DO NOT MODIFY")
        appendLine(" */")
        appendLine("package $packageName;")
        appendLine()
        appendLine("public final class BuildConfig {")
        for (f in fields) {
            f.comment?.let { appendLine("  // $it") }
            appendLine("  public static final ${f.type} ${f.name} = ${f.value};")
        }
        appendLine("}")
    }

    /** `free` + `dev` is `freeDev`, as AGP names a multi-dimension flavor combination. */
    private fun flavorName(flavors: List<String>): String =
        flavors.mapIndexed { i, f -> if (i == 0) f else f.replaceFirstChar { it.uppercaseChar() } }.joinToString("")

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
