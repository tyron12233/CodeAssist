package com.tyron.builder.gradle.internal.tasks.manifest

import com.android.manifmerger.ManifestProvider
import java.io.File

/* Used to pass to the merger manifest snippet that needs to be added during merge */
class ManifestProviderImpl(private val manifest: File, private val name: String) :
    ManifestProvider {
    override fun getManifest(): File {
        return manifest
    }

    override fun getName(): String {
        return name
    }
}