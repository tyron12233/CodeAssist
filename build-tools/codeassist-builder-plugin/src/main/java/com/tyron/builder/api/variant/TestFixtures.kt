package com.tyron.builder.api.variant

import org.gradle.api.Incubating
import org.gradle.api.provider.Provider

@Incubating
interface TestFixtures: GeneratesAar, HasAndroidResources, Component {
    /**
     * The namespace of the generated R class. Also, the namespace used to resolve
     * any relative class names that are declared in the AndroidManifest.xml.
     */
    val namespace: Provider<String>
}