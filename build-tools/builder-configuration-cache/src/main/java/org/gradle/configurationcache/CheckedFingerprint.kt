package org.gradle.configurationcache

import org.gradle.util.Path


sealed class CheckedFingerprint {
    // No fingerprint, which means no cache entry
    object NotFound : CheckedFingerprint()

    // Everything is up-to-date
    object Valid : CheckedFingerprint()

    // The entry cannot be reused at all and should be recreated from scratch
    class EntryInvalid(val reason: String) : CheckedFingerprint()

    // The entry can be reused, however the values for certain projects cannot be reused and should be recreated
    class ProjectsInvalid(val reason: String, val invalidProjects: Set<Path>) : CheckedFingerprint()
}
