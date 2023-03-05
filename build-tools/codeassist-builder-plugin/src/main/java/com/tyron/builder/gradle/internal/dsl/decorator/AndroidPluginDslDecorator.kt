package com.tyron.builder.gradle.internal.dsl.decorator

//import com.tyron.builder.gradle.internal.dsl.ApplicationPublishingImpl
//import com.tyron.builder.gradle.internal.dsl.AssetPackBundleExtensionImpl
//import com.tyron.builder.gradle.internal.dsl.BundleOptions
//import com.tyron.builder.gradle.internal.dsl.BundleOptionsAbi
//import com.tyron.builder.gradle.internal.dsl.BundleOptionsCodeTransparency
//import com.tyron.builder.gradle.internal.dsl.BundleOptionsDensity
//import com.tyron.builder.gradle.internal.dsl.BundleOptionsDeviceTier
//import com.tyron.builder.gradle.internal.dsl.BundleOptionsLanguage
//import com.tyron.builder.gradle.internal.dsl.BundleOptionsStoreArchive
//import com.tyron.builder.gradle.internal.dsl.BundleOptionsTexture
//import com.tyron.builder.gradle.internal.dsl.CmakeOptions
//import com.tyron.builder.gradle.internal.dsl.DataBindingOptions
//import com.tyron.builder.gradle.internal.dsl.DensitySplitOptions
//import com.tyron.builder.gradle.internal.dsl.DependenciesInfoImpl
//import com.tyron.builder.gradle.internal.dsl.FusedLibraryExtensionImpl
//import com.tyron.builder.gradle.internal.dsl.ExternalNativeBuild as ExternalNativeBuildImpl
//import com.tyron.builder.gradle.internal.dsl.LibraryPublishingImpl
//import com.tyron.builder.gradle.internal.dsl.NdkBuildOptions
//import com.tyron.builder.gradle.internal.dsl.PrivacySandboxSdkBundleImpl
//import com.tyron.builder.gradle.internal.dsl.PrivacySandboxSdkExtensionImpl
//import com.tyron.builder.gradle.internal.dsl.ResourcesPackagingOptionsImpl
//import com.tyron.builder.gradle.internal.dsl.ViewBindingOptionsImpl
import com.tyron.builder.api.dsl.*
import com.tyron.builder.api.dsl.AnnotationProcessorOptions
import com.tyron.builder.api.dsl.JavaCompileOptions
import com.tyron.builder.api.dsl.PackagingOptions
import com.tyron.builder.api.dsl.SigningConfig
import com.tyron.builder.api.dsl.Splits

import com.tyron.builder.gradle.internal.dsl.*
import com.tyron.builder.gradle.internal.dsl.AaptOptions
import org.gradle.api.JavaVersion
import com.tyron.builder.gradle.internal.dsl.AnnotationProcessorOptions as AnnotationProcessorOptionsImpl
import com.tyron.builder.gradle.internal.dsl.JavaCompileOptions as JavaCompileOptionsImpl

/** The list of all the supported property types for the production AGP */
val AGP_SUPPORTED_PROPERTY_TYPES: List<SupportedPropertyType> = listOf(
    SupportedPropertyType.Var.String,
    SupportedPropertyType.Var.Boolean,
    SupportedPropertyType.Var.NullableBoolean,
    SupportedPropertyType.Var.Int,
    SupportedPropertyType.Var.NullableInt,
    SupportedPropertyType.Var.File,
    SupportedPropertyType.Var.Enum(JavaVersion::class.java),

    SupportedPropertyType.Collection.List,
    SupportedPropertyType.Collection.Set,
    SupportedPropertyType.Collection.Map,

    SupportedPropertyType.Block(AarMetadata::class.java, AarMetadataImpl::class.java),
    SupportedPropertyType.Block(AbiSplit::class.java, AbiSplitOptions::class.java),
    SupportedPropertyType.Block(AndroidResources::class.java, AaptOptions::class.java),
    SupportedPropertyType.Block(AnnotationProcessorOptions::class.java, AnnotationProcessorOptionsImpl::class.java),
    SupportedPropertyType.Block(ApplicationPublishing::class.java, ApplicationPublishingImpl::class.java),
//    SupportedPropertyType.Block(AssetPackBundleExtension::class.java, AssetPackBundleExtensionImpl::class.java),
    SupportedPropertyType.Block(Bundle::class.java, BundleOptions::class.java),
    SupportedPropertyType.Block(BundleAbi::class.java, BundleOptionsAbi::class.java),
    SupportedPropertyType.Block(BundleDensity::class.java, BundleOptionsDensity::class.java),
    SupportedPropertyType.Block(BundleDeviceTier::class.java, BundleOptionsDeviceTier::class.java),
    SupportedPropertyType.Block(BundleLanguage::class.java, BundleOptionsLanguage::class.java),
    SupportedPropertyType.Block(BundleTexture::class.java, BundleOptionsTexture::class.java),
    SupportedPropertyType.Block(BundleCodeTransparency::class.java, BundleOptionsCodeTransparency::class.java),
    SupportedPropertyType.Block(BundleStoreArchive::class.java, BundleOptionsStoreArchive::class.java),
//    SupportedPropertyType.Block(Cmake::class.java, CmakeOptions::class.java),
    SupportedPropertyType.Block(CompileOptions::class.java, com.tyron.builder.gradle.internal.CompileOptions::class.java),
    SupportedPropertyType.Block(DataBinding::class.java, DataBindingOptions::class.java),
    SupportedPropertyType.Block(DensitySplit::class.java, DensitySplitOptions::class.java),
    SupportedPropertyType.Block(DexPackagingOptions::class.java, DexPackagingOptionsImpl::class.java),
    SupportedPropertyType.Block(DependenciesInfo::class.java, DependenciesInfoImpl::class.java),
//    SupportedPropertyType.Block(ExternalNativeBuild::class.java, ExternalNativeBuildImpl::class.java),
    SupportedPropertyType.Block(JavaCompileOptions::class.java, JavaCompileOptionsImpl::class.java),
    SupportedPropertyType.Block(JniLibsPackagingOptions::class.java, JniLibsPackagingOptionsImpl::class.java),
    SupportedPropertyType.Block(KeepRules::class.java, com.tyron.builder.gradle.internal.dsl.KeepRulesImpl::class.java),
    SupportedPropertyType.Block(LibraryPublishing::class.java, LibraryPublishingImpl::class.java),
    SupportedPropertyType.Block(Lint::class.java, LintImpl::class.java),
//    SupportedPropertyType.Block(NdkBuild::class.java, NdkBuildOptions::class.java),
    SupportedPropertyType.Block(PackagingOptions::class.java, com.tyron.builder.gradle.internal.dsl.PackagingOptions::class.java),
    SupportedPropertyType.Block(Optimization::class.java, com.tyron.builder.gradle.internal.dsl.OptimizationImpl::class.java),
    SupportedPropertyType.Block(ResourcesPackagingOptions::class.java, ResourcesPackagingOptionsImpl::class.java),
    SupportedPropertyType.Block(SigningConfig::class.java, com.tyron.builder.gradle.internal.dsl.SigningConfigImpl::class.java),
    SupportedPropertyType.Block(Split::class.java, SplitOptions::class.java),
    SupportedPropertyType.Block(Splits::class.java, com.tyron.builder.gradle.internal.dsl.Splits::class.java),
    SupportedPropertyType.Block(ViewBinding::class.java, ViewBindingOptionsImpl::class.java),

    // FusedLibrary Extensions.
//    SupportedPropertyType.Block(FusedLibraryExtension::class.java, FusedLibraryExtensionImpl::class.java),
//    SupportedPropertyType.Block(PrivacySandboxSdkExtension::class.java, PrivacySandboxSdkExtensionImpl::class.java),
//    SupportedPropertyType.Block(PrivacySandboxSdkBundle::class.java, PrivacySandboxSdkBundleImpl::class.java)
)

/**
 * The DSL decorator in this classloader in AGP.
 *
 * This is a static field, rather than a build service as it shares its lifetime with
 * the classloader that AGP is loaded in.
 */
val androidPluginDslDecorator = DslDecorator(AGP_SUPPORTED_PROPERTY_TYPES)