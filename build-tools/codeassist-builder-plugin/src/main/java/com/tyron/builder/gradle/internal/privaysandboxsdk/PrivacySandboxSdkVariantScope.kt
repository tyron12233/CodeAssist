package com.tyron.builder.gradle.internal.privaysandboxsdk

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.dsl.FusedLibraryExtension
import com.tyron.builder.api.dsl.PrivacySandboxSdkExtension
import com.tyron.builder.gradle.internal.dsl.PrivacySandboxSdkBundleImpl
import com.tyron.builder.gradle.internal.fusedlibrary.FusedLibraryConfigurations
import com.tyron.builder.gradle.internal.fusedlibrary.FusedLibraryDependencies
import com.tyron.builder.gradle.internal.fusedlibrary.FusedLibraryVariantScopeImpl
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.tasks.factory.BootClasspathConfig
import com.tyron.builder.gradle.internal.utils.validatePreviewTargetValue
import com.tyron.builder.model.ApiVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec

interface PrivacySandboxSdkVariantScope {
    val layout: ProjectLayout
    val artifacts: ArtifactsImpl
    val incomingConfigurations: FusedLibraryConfigurations
    val outgoingConfigurations: FusedLibraryConfigurations
    val dependencies: FusedLibraryDependencies
    val extension: PrivacySandboxSdkExtension
    val mergeSpec: Spec<ComponentIdentifier>
    val compileSdkVersion: String
    val minSdkVersion: ApiVersion
    val bootClasspath: Provider<List<RegularFile>>
    val bundle: PrivacySandboxSdkBundleImpl
    val services: TaskCreationServices
}
