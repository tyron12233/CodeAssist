package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.AndroidVersion
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import java.io.Serializable

/**
 * Implementation of [AndroidVersion]
 */
class AndroidVersionImpl(
    override val apiLevel: Int,
    override val codename: String? = null
): AndroidVersion