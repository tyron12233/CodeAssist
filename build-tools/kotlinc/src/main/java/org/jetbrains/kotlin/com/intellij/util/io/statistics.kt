package org.jetbrains.kotlin.com.intellij.util.io

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolute

object StorageStatsRegistrar {
    private val maps = ConcurrentHashMap<Path, PersistentMapImpl<*, *>>()
    private val enumerators = ConcurrentHashMap<Path, PersistentBTreeEnumerator<*>>()

    fun registerMap(path: Path, map: PersistentMapImpl<*, *>) {
        maps.put(path.absolute(), map)
    }

    fun unregisterMap(path: Path) {
        maps.remove(path.absolute())
    }

    fun registerEnumerator(path: Path, enumerator: PersistentBTreeEnumerator<*>) {
        enumerators.put(path.absolute(), enumerator)
    }

    fun unregisterEnumerator(path: Path) {
        enumerators.remove(path.absolute())
    }

    fun dumpStatsForOpenMaps(): Map<Path, PersistentHashMapStatistics> =
        maps.mapValues { it.value.statistics }

    fun dumpStatsForOpenEnumerators(): Map<Path, PersistentEnumeratorStatistics> =
        enumerators.mapValues { it.value.statistics }
}

data class BTreeStatistics(
    val pages: Int,
    val elements: Int,
    val height: Int,
    val moves: Int,
    val leafPages: Int,
    val maxSearchStepsInRequest: Int,
    val searchRequests: Int,
    val searchSteps: Int,
    val pageCapacity: Int,
    val sizeInBytes: Long
)


data class PersistentEnumeratorStatistics(
    val bTreeStatistics: BTreeStatistics,
    val collisions: Int,
    val values: Int,
    val dataFileSizeInBytes: Long,
    val storageSizeInBytes: Long
)


data class PersistentHashMapStatistics(
    val persistentEnumeratorStatistics: PersistentEnumeratorStatistics,
    val valueStorageSizeInBytes: Long
)

data class CachedChannelsStatistics(
    val hit: Int,
    val miss: Int,
    val load: Int,
    val capacity: Int
)

data class FilePageCacheStatistics(
    val cachedChannelsStatistics: CachedChannelsStatistics,
    val uncachedFileAccess: Int,
    val maxRegisteredFiles: Int,
    val maxCacheSizeInBytes: Long,
    val totalCachedSizeInBytes: Long,
    val pageHits: Int,
    val pageFastCacheHits: Int,
    val pageLoadsAboveSizeThreshold: Int,
    val regularPageLoads: Int,
    val disposedBuffers: Int,
    val totalPageDisposalUs: Long,
    val totalPageLoadUs: Long,
    val totalPagesLoaded: Long,
    val capacityInBytes: Long
) {
    fun dumpInfoImportantForBuildProcess(): String {
        return "pageHits=$pageHits, " +
                "pageFastCacheHits=$pageFastCacheHits, " +
                "regularPageLoads=$regularPageLoads, " +
                "pageLoadsAboveSizeThreshold=$pageLoadsAboveSizeThreshold, " +
                "pageLoadUs=$totalPageLoadUs, " +
                "pageDisposalUs=$totalPageDisposalUs, " +
                "capacityInBytes=$capacityInBytes, " +
                "disposedBuffers=$disposedBuffers " +
                "maxRegisteredFiles=$maxRegisteredFiles " +
                "maxCacheSizeInBytes=$maxCacheSizeInBytes" +
                "totalSizeCachedBytes=$totalCachedSizeInBytes"
    }
}