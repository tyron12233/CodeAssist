package com.tyron.code.ui.project

import com.tyron.builder.model.AndroidProject
import com.tyron.builder.model.Variant
import java.io.Serializable

/**
 * Represents a composite model for AndroidProject model plus Variant models.
 * When AndroidProject is requested with parameterized APIs, the AndroidProject model doesn't
 * contain Variant, and Variant model is requested separately.
 */
data class ParameterizedAndroidProject(
        val androidProject: AndroidProject,
        val variants: List<Variant>) : Serializable