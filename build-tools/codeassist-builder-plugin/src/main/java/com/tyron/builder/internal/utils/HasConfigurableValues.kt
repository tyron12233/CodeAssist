package com.tyron.builder.internal.utils

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.*

fun ConfigurableFileCollection.fromDisallowChanges(vararg arg: Any) {
    from(*arg)
    disallowChanges()
}

fun <T> Property<T>.setDisallowChanges(value: T?) {
    set(value)
    disallowChanges()
}

fun <T> Property<T>.setDisallowChanges(value: Provider<out T>) {
    set(value)
    disallowChanges()
}

fun <T> ListProperty<T>.setDisallowChanges(value: Provider<out Iterable<T>>) {
    set(value)
    disallowChanges()
}

fun <T> ListProperty<T>.setDisallowChanges(value: Iterable<T>?) {
    set(value)
    disallowChanges()
}

fun <K, V> MapProperty<K, V>.setDisallowChanges(map: Provider<Map<K,V>>) {
    set(map)
    disallowChanges()
}

fun <K, V> MapProperty<K, V>.setDisallowChanges(map: Map<K,V>?) {
    set(map)
    disallowChanges()
}

fun <T> SetProperty<T>.setDisallowChanges(value: Provider<out Iterable<T>>) {
    set(value)
    disallowChanges()
}

fun <T> SetProperty<T>.setDisallowChanges(value: Iterable<T>?) {
    set(value)
    disallowChanges()
}

fun <T> ListProperty<T>.setDisallowChanges(
    value: Provider<out Iterable<T>>?,
    handleNullable: ListProperty<T>.() -> Unit
) {
    value?.let {
        set(value)
    } ?: handleNullable()
    disallowChanges()
}

fun <K, V> MapProperty<K, V>.setDisallowChanges(
    map: Provider<Map<K,V>>?,
    handleNullable: MapProperty<K, V>.() -> Unit
) {
    map?.let {
        set(map)
    } ?: handleNullable()
    disallowChanges()
}