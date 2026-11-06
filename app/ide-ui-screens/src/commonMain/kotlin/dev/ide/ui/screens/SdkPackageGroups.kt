package dev.ide.ui.screens

import dev.ide.ui.backend.UiSdkPackage

/**
 * One SDK Manager section split for display: the rows worth seeing up front ([shown]) and the rest, which sit
 * behind a "Show all versions" expander ([more]). [latest] and [needed] name the paths that earn a tag.
 */
internal data class SdkPackageGroup(
    val shown: List<UiSdkPackage>,
    val more: List<UiSdkPackage>,
    val latest: String?,
    val needed: Set<String>,
)

/**
 * Splits one category's [packages] (newest first) into what a user needs to see and what they rarely do.
 *
 * Shown: anything installed, half-installed, downloading or failed (its state matters), every package whose
 * API level is in [neededLevels] (the open project's compileSdk levels), and the newest package. The rest
 * goes behind the expander, except when only one package would hide there: an expander for a single row
 * costs as much space as the row.
 */
internal fun groupSdkPackages(
    packages: List<UiSdkPackage>,
    neededLevels: Set<Int>,
    activeIds: Set<String>,
    failedIds: Set<String>,
): SdkPackageGroup {
    val sorted = packages.sortedWith(compareByDescending<UiSdkPackage> { sdkLevelOrder(it.path) }.thenByDescending { it.path })
    val latest = sorted.firstOrNull()?.path
    val needed = sorted.filter { sdkMajorLevel(it.path) in neededLevels }.mapTo(HashSet()) { it.path }
    val (shown, more) = sorted.partition { p ->
        p.installed || p.incomplete || p.path in activeIds || p.path in failedIds || p.path in needed || p.path == latest
    }
    return if (more.size <= 1) SdkPackageGroup(sorted, emptyList(), latest, needed)
    else SdkPackageGroup(shown, more, latest, needed)
}

/** The major API level of an SDK package id: `platforms;android-36.1` and `sources;android-36` are 36. Null for
 *  an id with no level (`platform-tools`) or a codename (`android-UpsideDownCake`). */
internal fun sdkMajorLevel(path: String): Int? {
    if (!path.contains("android-")) return null
    return path.substringAfterLast("android-").substringBefore('.').toIntOrNull()
}

/** Orders `android-36.1` above `android-36` above `android-9`: by level, then minor. */
internal fun sdkLevelOrder(path: String): Double =
    path.substringAfterLast("android-", "").toDoubleOrNull() ?: 0.0
