package dev.ide.ui

/**
 * Compile-time defaults for in-progress features that ship dark. A flag stays off until its feature is ready
 * for users; flip it to true here to enable it everywhere, or override it at runtime through the matching
 * `feature.*` preference (so it can be turned on for testing without a rebuild).
 */
object FeatureFlags {
    /**
     * The home-screen Projects Store + bottom navigation (Projects / Store / Learn). Work in progress, hidden
     * from users: while off, the project picker renders on its own with no bottom navigation bar. Runtime
     * override: preference `feature.projectsStore` = "true".
     */
    const val PROJECTS_STORE = true
}
