package com.tyron.builder.gradle

import com.tyron.builder.gradle.api.BaseVariantOutput
import com.tyron.builder.gradle.api.TestVariant
import com.tyron.builder.gradle.api.UnitTestVariant
import com.tyron.builder.gradle.internal.ExtraModelInfo
import com.tyron.builder.gradle.internal.dependency.SourceSetManager
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.internal.tasks.factory.BootClasspathConfig
import org.gradle.api.DomainObjectSet
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.FileCollection

/**
 * Provides test components that are common to [AppExtension], [LibraryExtension], and
 * [FeatureExtension].
 *
 * To learn more about testing Android projects, read
 * [Test your app](https://developer.android.com/studio/test/index.html)
 */
abstract class TestedExtension(
    dslServices: DslServices,
    bootClasspathConfig: BootClasspathConfig,
    buildOutputs: NamedDomainObjectContainer<BaseVariantOutput>,
    sourceSetManager: SourceSetManager,
    extraModelInfo: ExtraModelInfo,
    isBaseModule: Boolean
) : BaseExtension(
    dslServices,
    bootClasspathConfig,
    buildOutputs,
    sourceSetManager,
    extraModelInfo,
    isBaseModule
), TestedAndroidConfig, com.tyron.builder.api.dsl.TestedExtension {

    private val testVariantList: DomainObjectSet<TestVariant> =
        dslServices.domainObjectSet(TestVariant::class.java)

    private val unitTestVariantList: DomainObjectSet<UnitTestVariant> =
        dslServices.domainObjectSet(UnitTestVariant::class.java)

    /**
     * A collection of Android test
     * [build variants](https://developer.android.com/studio/build/build-variants.html)
     *
     * To process elements in this collection, you should use
     * [`all`](https://docs.gradle.org/current/javadoc/org/gradle/api/DomainObjectCollection.html#all-org.gradle.api.Action-).
     * That's because the plugin populates this collection only after
     * the project is evaluated. Unlike the `each` iterator, using `all`
     * processes future elements as the plugin creates them.
     *
     * To learn more about testing Android projects, read
     * [Test your app](https://developer.android.com/studio/test/index.html)
     */
    override val testVariants: DomainObjectSet<TestVariant>
        get() = testVariantList

    fun addTestVariant(testVariant: TestVariant) {
        testVariantList.add(testVariant)
    }

    /**
     * Returns a collection of Android unit test
     * [build variants](https://developer.android.com/studio/build/build-variants.html).
     *
     * To process elements in this collection, you should use
     * [`all`](https://docs.gradle.org/current/javadoc/org/gradle/api/DomainObjectCollection.html#all-org.gradle.api.Action-).
     * That's because the plugin populates this collection only after
     * the project is evaluated. Unlike the `each` iterator, using `all`
     * processes future elements as the plugin creates them.
     *
     * To learn more about testing Android projects, read
     * [Test your app](https://developer.android.com/studio/test/index.html)
     */
    override val unitTestVariants: DomainObjectSet<UnitTestVariant>
        get() = unitTestVariantList

    fun addUnitTestVariant(testVariant: UnitTestVariant) {
        unitTestVariantList.add(testVariant)
    }

    abstract override var testBuildType: String

    fun getMockableAndroidJar(): FileCollection {
        return bootClasspathConfig.mockableJarArtifact
    }

    abstract override var testNamespace: String?
}
