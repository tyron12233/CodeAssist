package dev.ide.model.impl

/** A writable directory for a test to build a workspace in. */
expect fun scratchDir(name: String): String

/** Remove [path] and everything under it. Best effort: a test that cannot clean up still passed. */
expect fun deleteTree(path: String)
