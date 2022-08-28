package com.tyron.builder.gradle.internal.utils

import com.tyron.builder.common.AndroidVersion

fun validatePreviewTargetValue(value: String): String? =
    if (AndroidVersion.PREVIEW_PATTERN.matcher(value).matches()) {
        value
    } else null