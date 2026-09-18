package dev.ide.deps.impl

/** A writable path for a test fixture, under whatever this platform calls its temporary directory. */
internal expect fun scratchPath(name: String): String

/** A per-run suffix, so two runs (or two tests) never share a scratch directory. */
internal expect fun nowSuffix(): String
