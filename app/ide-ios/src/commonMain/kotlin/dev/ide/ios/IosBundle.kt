package dev.ide.ios

import platform.Foundation.NSBundle

/** What the app bundle says about itself. */
internal object IosBundle {

    /**
     * `CFBundleVersion`, the build number iOS increments per submission.
     *
     * The counterpart of Android's `versionCode`, and what the store's build filtering is expressed in:
     * an item may declare a minimum build, and a client that reported its marketing version instead would
     * be answered as though it were a far newer release than it is.
     */
    fun buildNumber(): Int? =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull()
}
