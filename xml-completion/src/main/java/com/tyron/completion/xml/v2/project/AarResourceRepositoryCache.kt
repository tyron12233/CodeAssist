package com.tyron.completion.xml.v2.project

import com.android.projectmodel.ResourceFolder
import com.android.utils.concurrency.getAndUnwrap
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.hash.Hashing
import com.tyron.builder.project.ExternalAndroidLibrary
import com.tyron.common.ApplicationPaths
import com.tyron.completion.xml.v2.aar.*
import org.jetbrains.kotlin.utils.ThreadSafe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executor

/**
 * Cache of AAR resource repositories.
 */
@ThreadSafe
class AarResourceRepositoryCache private constructor() {
    private val myProtoRepositories = CacheBuilder.newBuilder().softValues().build<Path, AarProtoResourceRepository>()
    private val mySourceRepositories = CacheBuilder.newBuilder().softValues().build<ResourceFolder, AarSourceResourceRepository>()

    /**
     * Returns a cached or a newly created source resource repository.
     *
     * @param library the AAR library
     * @return the resource repository
     * @throws IllegalArgumentException if `library` doesn't contain resources or its resource folder doesn't point
     *     to a local file system directory
     */
    fun getSourceRepository(library: ExternalAndroidLibrary): AarSourceResourceRepository {
        val resFolder = library.resFolder ?: throw IllegalArgumentException("No resources for ${library.libraryName()}")

        if (resFolder.root.toPath() == null) {
            throw IllegalArgumentException("Cannot find resource directory ${resFolder.root} for ${library.libraryName()}")
        }
        return getRepository(resFolder, mySourceRepositories) {
            AarSourceResourceRepository.create(resFolder.root, resFolder.resources, library.libraryName(), createCachingData(library))
        }
    }

    /**
     * Returns a cached or a newly created proto resource repository.
     *
     * @param library the AAR library
     * @return the resource repository
     * @throws IllegalArgumentException if `library` doesn't contain res.apk or its res.apk isn't a file on the local file system
     */
    fun getProtoRepository(library: ExternalAndroidLibrary): AarProtoResourceRepository {
        val resApkPath = library.resApkFile ?: throw IllegalArgumentException("No res.apk for ${library.libraryName()}")

        val resApkFile = resApkPath.toPath() ?: throw IllegalArgumentException("Cannot find $resApkPath for ${library.libraryName()}")

        return getRepository(resApkFile, myProtoRepositories) { AarProtoResourceRepository.create(resApkFile, library.libraryName()) }
    }

    fun removeProtoRepository(resApkFile: Path) {
//        myProtoRepositories.invalidate(resApkFile)
    }

    fun removeSourceRepository(resourceFolder: ResourceFolder) {
        mySourceRepositories.invalidate(resourceFolder)
    }

    fun clear() {
        myProtoRepositories.invalidateAll()
        mySourceRepositories.invalidateAll()
    }

    private fun createCachingData(library: ExternalAndroidLibrary): CachingData? {
        val resFolder = library.resFolder
        if (resFolder == null || resFolder.resources != null) {
            return null // No caching if the library contains no resources or the list of resource files is specified explicitly.
        }
        // Compute content version as a maximum of the modification times of the res directory and the .aar file itself.
        var modificationTime = try {
            Files.getLastModifiedTime(resFolder.root.toPath()!!)
        }
        catch (e: NoSuchFileException) {
            return null // No caching if the resource directory doesn't exist.
        }
        library.location?.let {
            try {
                val libraryPath = it.toPath()
                if (libraryPath == null) {
//                    thisLogger().error("Library ${library.libraryName()} has an invalid location: \"$it\"");
                } else {
                    modificationTime = modificationTime.coerceAtLeast(Files.getLastModifiedTime(libraryPath))
                }
            }
            catch (ignore: NoSuchFileException) {
            }
        }
        val contentVersion = modificationTime.toString()

        val codeVersion = "7.4"//getAndroidPluginVersion() ?: return null

        val path = resFolder.root
        val pathHash = Hashing.farmHashFingerprint64().hashUnencodedChars(path.portablePath).toString()
        val filename = String.format("%s_%s.dat", library.location?.fileName ?: "", pathHash)
        val cacheFile = Paths.get(ApplicationPaths.getCacheDir().absolutePath, RESOURCE_CACHE_DIRECTORY, filename)
        val executor = Executor(Runnable::run)
        return CachingData(cacheFile, contentVersion, codeVersion, executor)
    }

    companion object {

        /**
         * Returns the cache.
         */
        @JvmStatic
        val instance: AarResourceRepositoryCache = AarResourceRepositoryCache()

        private fun <K, T : AarResourceRepository> getRepository(key: K, cache: Cache<K, T>, factory: () -> T): T {
            return cache.getAndUnwrap(key) { factory() }
        }
    }
}