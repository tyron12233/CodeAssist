package dev.ide.model

/**
 * Library-table entries are per module: a declared coordinate's entry is stored as `<coordinate>@module:<id>`.
 *
 * An entry holds the share of its module's resolved graph that the declaration claimed, and that share depends on
 * the module, two ways:
 *
 *  * **Platform.** A Kotlin Multiplatform library resolves to different artifacts for an Android module
 *    (`ui-android`) and a JVM one (`ui-desktop`), and a Gradle project routinely declares one coordinate from
 *    both (the KMP wizard's `shared` and `desktopApp`). With one entry per coordinate, an Android build was
 *    dexed against desktop jars ("Duplicate class androidx.compose.ui.Actual_jvmAndAndroidKt…").
 *  * **The rest of the module's declarations.** `ui-tooling-preview` declared after `foundation` claims almost
 *    nothing, because `foundation` already reached the rest; declared alone, it claims its whole closure. With
 *    one entry, whichever module resolved last decided for all, and the other compiled against too little.
 *
 * A classpath reads each library as its DECLARING module resolved it. A module with no entry of its own for a
 * coordinate (its share was empty, or the project was resolved before entries were per module, or the library is
 * a local jar) reads the plain name, which is exactly what it read before.
 */
object LibraryVariants {

    /** The table name of [moduleId]'s own resolution of the declared library [name]. */
    fun scopedName(name: String, moduleId: String): String = "$name$MODULE_INFIX$moduleId"

    /** Whether [tableName] is a module's own entry rather than a shared one. */
    fun isScoped(tableName: String): Boolean = MODULE_INFIX in tableName

    /** The names to try for [name] as declared by [moduleId], best first. */
    fun lookupOrder(name: String, moduleId: String): List<String> = listOf(scopedName(name, moduleId), name)

    private const val MODULE_INFIX = "@module:"
}
