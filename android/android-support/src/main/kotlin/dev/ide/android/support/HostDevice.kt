package dev.ide.android.support

/**
 * What the machine this code runs on is, when it is an Android device.
 *
 * This module is plain JVM code that also runs on ART (the IDE on a phone), and has no Android classes to
 * compile against, so the one framework value it needs is read by name.
 */
object HostDevice {
    /** `Build.VERSION.SDK_INT` on an Android device; null anywhere else (a desktop JVM has no such class). */
    fun apiLevel(): Int? = level

    private val level: Int? by lazy {
        runCatching {
            Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null).takeIf { it > 0 }
        }.getOrNull()
    }
}
