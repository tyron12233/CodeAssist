package dev.ide.platform

/**
 * Writes [bytes] to a scratch file and returns its path, or null when this platform has nowhere to put one.
 *
 * Exists so that [FileSourceTest] can run on the phone as well as the desktop. The file seam is the only
 * platform-specific code in the module, so it is the one piece whose test has to actually execute on both
 * sides rather than merely compile for them.
 */
expect fun writeTempFile(name: String, bytes: ByteArray): String?
