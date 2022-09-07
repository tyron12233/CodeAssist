package com.tyron.builder.gradle.internal.dsl

//import com.tyron.builder.gradle.internal.dsl.ExternalNativeBuild as ExternalNativeBuildImpl
//import com.tyron.builder.gradle.internal.dsl.LintOptions as LintOptionsImpl
import com.tyron.builder.api.dsl.*
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import com.tyron.builder.gradle.internal.CompileOptions as CompileOptionsImpl
import com.tyron.builder.gradle.internal.coverage.JacocoOptions as JacocoOptionsImpl
import com.tyron.builder.gradle.internal.dsl.AaptOptions as AaptOptionsImpl
import com.tyron.builder.gradle.internal.dsl.AdbOptions as AdbOptionsImpl
import com.tyron.builder.gradle.internal.dsl.DataBindingOptions as DataBindingOptionsImpl
import com.tyron.builder.gradle.internal.dsl.PackagingOptions as PackagingOptionsImpl
import com.tyron.builder.gradle.internal.dsl.Splits as SplitsImpl
//import com.tyron.builder.gradle.internal.dsl.TestOptions as TestOptionsImpl

/**
 * Internal extension of the DSL interface that overrides the properties to use the implementation
 * types, in order to enable the use of kotlin delegation from the original DSL classes
 * to the new implementations.
 */
interface InternalCommonExtension<
        BuildFeaturesT : com.tyron.builder.api.dsl.BuildFeatures,
        BuildTypeT : com.tyron.builder.api.dsl.BuildType,
        DefaultConfigT : com.tyron.builder.api.dsl.DefaultConfig,
        ProductFlavorT : com.tyron.builder.api.dsl.ProductFlavor> :
    CommonExtension<
            BuildFeaturesT,
            BuildTypeT,
            DefaultConfigT,
            ProductFlavorT>, Lockable {

    override val aaptOptions: AaptOptionsImpl

    override val adbOptions: AdbOptionsImpl
    override val compileOptions: CompileOptionsImpl

    override val dataBinding: DataBindingOptionsImpl
    override val viewBinding: ViewBindingOptionsImpl
    override val jacoco: JacocoOptionsImpl
//    override val lintOptions: LintOptionsImpl
    override val packagingOptions: PackagingOptionsImpl
//    override val externalNativeBuild: ExternalNativeBuildImpl
//    override val testOptions: TestOptionsImpl
    override val splits: SplitsImpl
    override val signingConfigs: NamedDomainObjectContainer<SigningConfig>

    var compileSdkVersion: String?

    // See GroovyExtensionsTest
    fun buildTypes(action: Action<in NamedDomainObjectContainer<BuildType>>)
    fun productFlavors(action: Action<NamedDomainObjectContainer<ProductFlavor>>)
    fun defaultConfig(action: Action<DefaultConfig>)
    fun signingConfigs(action: Action<NamedDomainObjectContainer<SigningConfig>>)
    fun aaptOptions(action: Action<AaptOptionsImpl>)
    fun adbOptions(action: Action<AdbOptionsImpl>)
    fun androidResources(action: Action<AndroidResources>)
    fun buildFeatures(action: Action<BuildFeaturesT>)
    fun compileOptions(action: Action<CompileOptionsImpl>)
    fun composeOptions(action: Action<ComposeOptions>)
    fun dataBinding(action: Action<DataBindingOptionsImpl>)
    fun viewBinding(action: Action<ViewBindingOptionsImpl>)
//    fun externalNativeBuild(action: Action<ExternalNativeBuildImpl>)
    fun installation(action: Action<Installation>)
    fun jacoco(action: Action<JacocoOptionsImpl>)
    fun lint(action: Action<Lint>)
//    fun lintOptions(action: Action<LintOptionsImpl>)
    fun packagingOptions(action: Action<PackagingOptionsImpl>)
    fun sourceSets(action: Action<NamedDomainObjectContainer<com.tyron.builder.gradle.api.AndroidSourceSet>>)
    fun splits(action: Action<SplitsImpl>)
    fun testCoverage(action: Action<TestCoverage>)
//    fun testOptions(action: Action<TestOptionsImpl>)
    fun setFlavorDimensions(flavorDimensions: List<String>)
}