package dev.ide.plugin.impl

import dev.ide.plugin.PLUGIN_SPI_VERSION

/**
 * Turns what an installed plugin threw while loading into the sentence its row in the Plugins screen shows.
 *
 * The case worth naming is a [LinkageError] against an SPI type. A plugin compiled against an older SPI can
 * satisfy `apiVersion` and still fail here, because Kotlin's synthetic constructor for a class with default
 * parameters carries every parameter in its descriptor: adding one field to [dev.ide.plugin.PluginManifest]
 * changes the method an already-compiled plugin calls. The JVM's own words for that are a descriptor and a
 * dex path, which tell the person reading the Plugins screen nothing about what to do. What they need is
 * that the plugin is built against a different SPI than this IDE ships, so the raw error goes last and the
 * instruction goes first.
 */
object PluginLoadFailure {

    fun describe(t: Throwable): String {
        val cause = generateSequence(t) { it.cause }.last()
        val detail = cause.message?.takeIf { it.isNotBlank() }
        val summary = if (detail != null) "${cause::class.java.simpleName}: ${detail.lineSequence().first()}"
        else cause::class.java.name
        if (cause !is LinkageError || !mentionsSpi(detail)) return summary
        return "built against a different version of the plugin SPI; this IDE ships $PLUGIN_SPI_VERSION, " +
            "so rebuild the plugin against it ($summary)"
    }

    /** Whether the link failed on one of our own types, which is what makes it a version mismatch and not
     *  a class the plugin forgot to package. */
    private fun mentionsSpi(message: String?): Boolean =
        message != null && ("dev/ide/" in message || "dev.ide." in message)
}
