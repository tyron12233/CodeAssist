package dev.ide.model

import dev.ide.platform.ServiceKey
import java.nio.file.Path

/**
 * One ANDROID resource definition: a `@<rClass>/<name>` pair as some file declares it. The Android half of
 * [ModuleResources] deals in these; the module-type-neutral half deals in files and [ContentRole]s.
 *
 * [rClass] is the nested class name under `R` (`"string"`, `"drawable"`, `"dimen"`), which is also what a
 * `@type/name` reference spells. [value] is the inline text of a value resource (`"Hello"`, `"#FF0000"`,
 * `"16dp"`) and null for a file resource, whose content IS [file]. [qualifier] is the config the definition
 * belongs to (`"night"`, `"en-rUS"`, `"v21"`), empty for the default config, so the same [rClass]+[name] can
 * appear several times with different values.
 */
data class ResourceEntry(
    val rClass: String,
    val name: String,
    val value: String? = null,
    val file: Path? = null,
    val qualifier: String = "",
)

/**
 * What [ModuleResources.find] keeps. Every field is optional and they AND together; the default matches
 * everything.
 *
 * A module's resources are the merge of its own `res/`, its dependency modules' and its dependency AARs',
 * because that is the set its code can name. [moduleOnly] narrows to the ones the module itself declares,
 * which is what a plugin about to write should ask: overriding a library's `@string/app_name` is a legitimate
 * thing to do, so "already defined somewhere on the classpath" is not the same question as "already declared
 * here".
 *
 * Adding a field here costs a `PLUGIN_API_VERSION` bump, not just an SPI minor: Kotlin compiles a call
 * relying on the defaults into a synthetic constructor whose descriptor names every parameter, so a plugin
 * built against the smaller one calls a method the larger does not have.
 */
data class ResourceFilter(
    /** Only this R class (`"string"`), or every type. */
    val rClass: String? = null,
    /** Only this exact name. */
    val name: String? = null,
    /** Only names starting with this, case-sensitive. */
    val namePrefix: String? = null,
    /** Only names containing this, case-insensitive. */
    val nameContains: String? = null,
    /** Only definitions in this config (`""` is the default config), or every config. */
    val qualifier: String? = null,
    /** Drop everything the module does not declare itself (dependency modules, AARs). */
    val moduleOnly: Boolean = false,
    /** Keep every config-specific definition. Off, one entry per [rClass]+[name] wins (the first found). */
    val allConfigs: Boolean = false,
    /** Stop after this many entries. */
    val limit: Int = Int.MAX_VALUE,
) {
    companion object {
        val ALL = ResourceFilter()
    }
}

/** What a write does when what it was asked to create is already there. */
enum class ResourceConflict {
    /** Leave what is there alone and report [ResourceWrite.AlreadyExists]. */
    FAIL,

    /** Write under the first free `<name>_1`, `<name>_2`, … and report which one. For a file the suffix goes
     *  on the base name, so `report.json` becomes `report_1.json` and keeps the extension that decides how
     *  it is read. */
    RENAME,

    /** Overwrite the file, or rewrite the resource definition in place. */
    REPLACE,
}

/**
 * The outcome of a [ModuleResources] write.
 *
 * Sealed so the four outcomes are distinguishable, which the engine's own resource authoring was not: it
 * echoed the requested name back whether it had written it, renamed it, or found no `res/` root at all.
 * Give a `when` over this an `else`: an outcome added in a later SPI minor must not stop an existing plugin
 * from compiling.
 */
sealed interface ResourceWrite {
    /**
     * [name] is what was actually written, which differs from the requested one under
     * [ResourceConflict.RENAME]: the resource name for a resource, the file name for a file. [file] is
     * always the full path it landed in, so read that rather than rebuilding it from [name].
     */
    data class Written(val name: String, val file: Path) : ResourceWrite

    /** The resource or file is already there and [ResourceConflict.FAIL] was asked for. [file] is where,
     *  null only when the existing declaration could not be attributed to one. */
    data class AlreadyExists(val file: Path?) : ResourceWrite

    /** The module declares no content root of the role the write needed: `res/` on a module whose type is
     *  not Android's, or a role its type has no notion of. Not a failure, a mismatch. */
    data object NoResourceRoot : ResourceWrite

    /** The write was not attempted or did not land, with a reason meant for a log or a plugin's own error. */
    data class Failed(val reason: String) : ResourceWrite
}

/**
 * A module's resources: what it holds, and how to add to it. WORKSPACE-scoped.
 *
 * Two halves, and which one applies is decided by the module, not by this interface.
 *
 * **Any module type.** [resourceRoots] and [putResourceFile] are phrased in [ContentRole], so they work
 * wherever a module declares a root: `src/main/resources` on a JVM module ([ContentRole.RESOURCE]),
 * `src/main/assets` ([ContentRole.ASSETS]), `res/` ([ContentRole.ANDROID_RES]), or a role a plugin defined
 * for a module type of its own. This is the half that answers "generate a file into the user's module and
 * have the IDE notice", which is otherwise not something a plugin can do: writing the file is easy, and
 * getting the build, the index and the editor to see it is not.
 *
 * **Android's resource model.** [types], [names], [has], [find], [putValueResource] and [createResourceFile]
 * speak `@type/name`, `R` classes, `res/values/` entries and config qualifiers, which are Android's and have
 * no counterpart on a plain JVM module. They read the merged, buffer-aware repository the IDE's own
 * reference resolution and synthetic `R` read, so a plugin sees what the editor sees, including a `<string>`
 * typed into an open `res/` buffer and not yet saved. A module with no Android resources answers empty from
 * every query and [ResourceWrite.NoResourceRoot] from every write, rather than throwing: a plugin that runs
 * across a mixed project does not have to ask what kind of module it landed on first.
 *
 * The Android half is phrased in `String` R-class ids rather than a resource-type enum for the same reason
 * [ContentRole] is open: the vocabulary belongs to the platform being modelled, not to the SPI.
 *
 * Every write goes to disk and publishes the file event that refreshes the synthetic classes and re-indexes
 * the file, so what it wrote resolves without a rebuild. It writes THROUGH any open editor buffer of the same
 * file rather than through it, so prefer an editor-tier edit when the target is a file the user is looking at.
 */
interface ModuleResources {

    // ---- Any module type ------------------------------------------------------------------------------

    /**
     * [module]'s own content roots carrying [role], ignoring dependencies. Empty when it declares none, which
     * is the honest answer for a role its module type has no notion of (`ANDROID_RES` on a `java-lib`).
     *
     * A write goes under the FIRST, so the `main` source set's roots lead and a variant's (`src/debug/res`)
     * follow: a file authored into a variant is missing from every build that does not select it.
     */
    fun resourceRoots(module: Module, role: ContentRole): List<Path>

    /**
     * Write [content] to `<root>/[relativePath]` under [module]'s first [role] root, creating parent
     * directories, and publish the change so the build and the index see the file.
     *
     * The module-type-neutral write: a JVM module's `src/main/resources/application.properties`, an Android
     * module's `src/main/assets/data.json`, a file under a role a plugin defined itself. [relativePath] is
     * relative to the root and may name directories; one that escapes the root is refused.
     *
     * The Android resource model is NOT applied here, deliberately: this writes a file where it is told,
     * where [putValueResource] and [createResourceFile] know what `res/` means. Use those for `res/`.
     */
    fun putResourceFile(
        module: Module,
        role: ContentRole,
        relativePath: String,
        content: String,
        onConflict: ResourceConflict = ResourceConflict.FAIL,
    ): ResourceWrite

    /** [putResourceFile] for content that is not text: an icon, a font, a packaged binary. */
    fun putResourceFile(
        module: Module,
        role: ContentRole,
        relativePath: String,
        content: ByteArray,
        onConflict: ResourceConflict = ResourceConflict.FAIL,
    ): ResourceWrite

    // ---- Android's resource model ---------------------------------------------------------------------
    //
    // Empty / NoResourceRoot for a module that has none, which is every module whose type is not Android's.

    /** The R classes [module] can name any resource of (`"string"`, `"drawable"`, …), its dependencies'
     *  included. Empty for a module with no Android resources. */
    fun types(module: Module): Set<String>

    /** Distinct names of `@<rClass>/…` visible to [module], in declaration order: what `R.<rClass>` exposes.
     *  Empty for an unknown [rClass]. */
    fun names(module: Module, rClass: String): List<String>

    /** Whether `@<rClass>/<name>` resolves for [module]. False for an unknown [rClass]. */
    fun has(module: Module, rClass: String, name: String): Boolean

    /** [module]'s Android resource definitions matching [filter], in declaration order. Empty for a module
     *  with no Android resources. */
    fun find(module: Module, filter: ResourceFilter = ResourceFilter.ALL): List<ResourceEntry>

    /** Whether [rClass] is written as an entry in `res/values/…` (string/color/dimen/bool/integer/id/…),
     *  which is what [putValueResource] writes. */
    fun isValueType(rClass: String): Boolean

    /** Whether [rClass] is written as a standalone file under `res/<rClass>/`
     *  (layout/drawable/menu/anim/…), which is what [createResourceFile] creates. */
    fun isFileType(rClass: String): Boolean

    /**
     * Write `<rClass name="[name]">[value]</rClass>` into [module]'s `res/values/`, in the file that type
     * conventionally lives in (`strings.xml`, `colors.xml`, `dimens.xml`, else `values.xml`), creating it if
     * it is not there. [value] is escaped.
     *
     * [onConflict] is judged against what [module] declares itself, not against the merged set, so shadowing
     * a library resource is allowed and redeclaring your own is not.
     */
    fun putValueResource(
        module: Module,
        rClass: String,
        name: String,
        value: String,
        onConflict: ResourceConflict = ResourceConflict.FAIL,
    ): ResourceWrite

    /**
     * Create `res/<rClass>/<name>.xml` in [module] holding [content], or a minimal valid document for that
     * type when [content] is null.
     *
     * [onConflict] compares against the file on disk: [ResourceConflict.REPLACE] overwrites it, so read it
     * first if the user's edits matter.
     */
    fun createResourceFile(
        module: Module,
        rClass: String,
        name: String,
        content: String? = null,
        onConflict: ResourceConflict = ResourceConflict.FAIL,
    ): ResourceWrite
}

/** WORKSPACE-scoped [ModuleResources] for the open project. */
val MODULE_RESOURCES = ServiceKey<ModuleResources>("platform.moduleResources")
