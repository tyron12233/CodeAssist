package com.tyron.builder.api.component.impl
import com.tyron.builder.gradle.internal.LoggerWrapper
import org.gradle.api.provider.MapProperty

/**
 * Minimal implementation of [MutableMap] based on a [MapProperty]
 *
 * Because some methods like [MutableMap.entries] will require reading from the [MapProperty],
 * instantiation of this class should probably be guarded by
 * [com.tyron.builder.gradle.options.BooleanOption.ENABLE_LEGACY_API]
 */
class MutableMapBackedUpWithMapProperty<K, V>(
    private val mapProperty: MapProperty<K, V>,
    private val propertyName: String,
): java.util.AbstractMap<K, V>(), MutableMap<K, V> {

    private val logger = LoggerWrapper.getLogger(MutableMapBackedUpWithMapProperty::class.java)

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = ReadOnlyMutableSet(_get().entries,
   "Cannot add elements using the $propertyName entries() returned Set," +
           " use the original collection")



    override val keys: MutableSet<K>
        get() = ReadOnlyMutableSet(_get().keys,
                   "Cannot add elements using the $propertyName keys() returned Set," +
                           " use the original collection")

    override val values: MutableCollection<V>
        get() = ReadOnlyMutableCollection(
            _get().values,
            "Cannot add elements using the $propertyName values() returned collection, " +
                    "use the original collection")

    override fun clear() {
        mapProperty.empty()
    }

    override fun remove(key: K): V? {
        throw NotImplementedError("Cannot remove elements from the $propertyName map, first do a clear() " +
                "and add back selectively the elements you want to keep")
    }

    override fun putAll(from: Map<out K, V>) {
        mapProperty.putAll(from)
    }

    override fun put(key: K, value: V): V? {
        mapProperty.put(key, value)
        return null
    }

    private fun _get(): MutableMap<K, V> {
        logger.warning("Values of variant API $propertyName are queried and may return non final values, " +
                               "this is unsupported")
        return mapProperty.get()
    }
}
