package com.tyron.builder.api.component.impl

class ReadOnlyMutableSet<E>(
    private val mutableSet: MutableSet<E>,
    private val addErrorMessage: String,
): ReadOnlyMutableCollection<E>(mutableSet, addErrorMessage), MutableSet<E> by mutableSet