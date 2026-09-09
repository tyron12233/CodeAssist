package dev.ide.index

/** Provenance of an indexed entry — drives ranking proximity and the completion origin label. [LIBRARY_SOURCE]
 *  is an attached library/SDK SOURCE archive (`-sources.jar`, JDK `src.zip`, Android `sources/`): immutable like
 *  a library (so it's segment-cached, not the edit-sensitive in-memory [SOURCE] path) but carries source text. */
enum class IndexOrigin { SDK, LIBRARY, SOURCE, LIBRARY_SOURCE }