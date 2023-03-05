package com.tyron.builder.gradle.internal.core

interface MergedOptions<T> {

    fun reset()

    fun append(option: T)
}