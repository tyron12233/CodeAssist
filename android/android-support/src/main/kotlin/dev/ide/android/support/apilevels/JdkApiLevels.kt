package dev.ide.android.support.apilevels

/**
 * The JDK's API-level table: the Java release that added, deprecated or removed each public JDK API, from
 * Java 8 (meaning "8 or earlier") to the release it was generated from. Generated from a JDK's `lib/ct.sym`
 * by `JdkApiLevelsGenerator` in the same format as the Android table, so [AndroidApiDatabase] reads it; the
 * levels in its answers are Java releases, not API levels.
 */
object JdkApiLevels {
    /** The resource the table ships as, next to `android-api-levels.bin`. */
    const val RESOURCE = "/jdk-api-levels.bin"

    @Volatile private var loaded: AndroidApiDatabase? = null
    @Volatile private var missing = false

    /** The bundled table, or null when the resource is absent. Loaded once. */
    fun bundled(): AndroidApiDatabase? {
        loaded?.let { return it }
        if (missing) return null
        synchronized(this) {
            loaded?.let { return it }
            val stream = JdkApiLevels::class.java.getResourceAsStream(RESOURCE)
            if (stream == null) {
                missing = true
                return null
            }
            return stream.use { AndroidApiDatabase.read(it) }.also { loaded = it }
        }
    }
}
