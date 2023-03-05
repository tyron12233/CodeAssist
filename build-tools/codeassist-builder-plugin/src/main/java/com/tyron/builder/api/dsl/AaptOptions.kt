package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

@Incubating
@Deprecated("Renamed to AndroidResources", replaceWith = ReplaceWith("AndroidResources"))
interface AaptOptions : AndroidResources