package com.tyron.builder.api.dsl

interface BundleStoreArchive {

    /**
     * Archive is an app state that allows an official app store to reclaim device storage and
     * disable app functionality temporarily until the user interacts with the app again. Upon
     * interaction the latest available version of the app will be restored while leaving user data
     * unaffected.
     *
     * <p> Enabled by default.
     */
    var enable: Boolean?
}