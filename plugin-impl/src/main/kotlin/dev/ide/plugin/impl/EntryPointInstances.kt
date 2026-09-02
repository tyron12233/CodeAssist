package dev.ide.plugin.impl

import java.lang.reflect.Modifier

/**
 * The entry-point objects of ONE plugin: one instance per class it names, however many of the manifest's
 * lists name it.
 *
 * A plugin's two facets are separate *types* only because a `@Composable` body cannot live in the engine
 * module. Nothing stops one class from implementing both, and naming that class in `entryPoints` and in
 * `uiEntryPoints` is how a plugin says so. Instantiating it once per list would give the two halves separate
 * copies of every field while looking like it had worked, so instances are keyed by class name and the second
 * list is handed the object the first one made. An author who wants the halves independent still gets that,
 * by writing two classes.
 *
 * A Kotlin `object` is taken from its `INSTANCE` field rather than its constructor, which is private:
 * a stateless registrar is the shape most authors reach for, and reflection cannot call that constructor
 * without breaking access.
 *
 * Not thread-safe, and does not need to be: a plugin's entry points are created on the loading thread,
 * before anything else can see the plugin.
 */
class EntryPointInstances(
    /** The plugin's own classloader. Using it is what lets a plugin's facets share statics and call each other. */
    val classLoader: ClassLoader,
) {

    private val byName = LinkedHashMap<String, Any>()

    /**
     * The instance of [fqcn] for this plugin, created on first ask and reused after. Throws whatever loading
     * the class or running its constructor throws; every caller reports that against the plugin's own row.
     */
    fun of(fqcn: String): Any = byName.getOrPut(fqcn) { create(classLoader.loadClass(fqcn)) }

    private fun create(cls: Class<*>): Any = singletonOf(cls) ?: cls.getDeclaredConstructor().newInstance()

    /**
     * The singleton a Kotlin `object` exposes (a public static `INSTANCE` field of the class's own type), or
     * null for an ordinary class. Reading the field is what runs the initializer, so a failing `object` fails
     * here exactly as a failing constructor would.
     */
    private fun singletonOf(cls: Class<*>): Any? {
        val field = runCatching { cls.getDeclaredField("INSTANCE") }.getOrNull() ?: return null
        if (!Modifier.isStatic(field.modifiers) || !Modifier.isPublic(field.modifiers)) return null
        if (field.type != cls) return null
        return field.get(null)
    }
}
