package dev.ide.plugin

/**
 * A location on the host's file system, as [PluginRegistration] hands one out.
 *
 * On the JVM this IS `java.nio.file.Path`: the alias changes no signature, so a plugin built against an earlier
 * SPI links unchanged and one written against this one uses the directory exactly as before. The name exists
 * because common code cannot say `java.nio.file.Path`, and the registration API is common code now that a
 * plugin's UI half can be built for a host with no JVM underneath it.
 */
expect interface HostPath
