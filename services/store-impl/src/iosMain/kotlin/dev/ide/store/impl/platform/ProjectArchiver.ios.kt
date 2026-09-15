package dev.ide.store.impl.platform

/**
 * No zip writer on iOS, so no publishing from it.
 *
 * `java.util.zip` is what packages a submission on the other two hosts, and Foundation has no compressor
 * a Kotlin/Native target reaches without a cinterop of its own. That is a real gap rather than a hidden
 * one: `StoreService.submissionsAvailable()` answers false and every publish entry point is already drawn
 * conditionally on it, so the iOS store browses, installs, signs in and moderates without offering a
 * button that could only fail at the last step.
 */
internal actual fun defaultArchiver(): ProjectArchiver = ProjectArchiver.Unsupported
