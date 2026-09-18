package dev.ide.templates

/** A writable directory for a test to scaffold a project in. */
expect fun scratchDir(name: String): String

/** Remove [path] and everything under it. Best effort: a test that cannot clean up still passed. */
expect fun deleteTree(path: String)
