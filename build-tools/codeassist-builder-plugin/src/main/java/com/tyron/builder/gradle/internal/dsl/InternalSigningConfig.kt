package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ApkSigningConfig
import com.tyron.builder.api.dsl.SigningConfig
import org.gradle.api.Named

/** Serves the same purpose as [InternalBuildType] */
interface InternalSigningConfig:
    SigningConfig,
    ApkSigningConfig,
    Named,
    com.tyron.builder.model.SigningConfig