package dev.codeassist.csharp.sdk

import android.util.Log
import dev.codeassist.csharp.project.nuget.NuGet
import dev.codeassist.csharp.util.TAG
import dev.ide.platform.notify.UserMessages
import dev.ide.plugin.PluginRegistration
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
import java.util.zip.ZipInputStream

/**
 * The .NET runtime and compiler this plugin carries, and the layout they are run from.
 *
 * Two halves. What is executed is the launcher, in the package's native library
 * directory, the one place Android lets an app run a file from.
 * What is only read, the runtime's libraries,
 * the Roslyn compiler and the reference assemblies, is `assets/dotnet-sdk.zip`, unpacked on first use.
 *
 * Why a launcher and not `dotnet`: the `dotnet` muxer refuses to run under any name but "dotnet"
 * ("cannot execute dotnet when renamed"),
 * and a native library has to be named `lib*.so`.
 * The launcher calls hostfxr itself.
 *
 * Unpacked into the IDE's INTERNAL storage, PluginRegistration.privateDataDir,
 * not PluginRegistration.dataDir: the runtime maps its libraries executable, and external storage refuses that.
 * Being the plugin's own directory, the IDE offers to delete it once the plugin is uninstalled;
 * the path this plugin picked for itself before (`files/dotnet-plugin`) outlived it.
 *
 * Safe to use from several processes: a file lock makes one unpack while the other waits.
 */
class DotnetSdk(
    private val reg: PluginRegistration,
    private val messages: UserMessages?,
) {
    /** Whether the runtime can be used: Ready once unpacked, else why not. */
    sealed interface Status {
        data class Ready(val version: String) : Status
        data class Unavailable(val reason: String) : Status
    }

    /**
     * Where everything this plugin writes for itself lives: the unpacked tree, a home and a temp dir.
     * The plugin's PluginRegistration.privateDataDir;
     * on a host older than SPI 3.4.0, which has none, the path this plugin used before it existed.
     */
    val home: Path by lazy {
        privateHome() ?: internalHome() ?: reg.dataDir
    }

    /** `DOTNET_ROOT`: the unpacked tree (`host/`, `shared/`, `sdk/Roslyn/`, `ref/`). */
    val root: Path get() = home.resolve("dotnet")

    /** Starts a .NET program: `launcher <app.dll | csc.dll> args...`. */
    val launcher: Path?
        get() = reg.nativeLibrary("dotnetlaunch")?.takeIf { Files.isRegularFile(it) }

    /** The Roslyn C# compiler, a managed program run through launcher. */
    val csc: Path get() = root.resolve("sdk/Roslyn/csc.dll")

    /** NuGet packages, downloaded on first use into `<home>/nuget/`, and kept across runs. */
    internal val nuget: NuGet by lazy { NuGet(home.resolve("nuget")) }

    @Volatile
    private var ready: Status.Ready? = null

    /** Make the tree usable: unpack it if this version has not been.
     * Cheap after the first call in a process. */
    @Synchronized
    fun prepare(): Status {
        ready?.let { return it }
        if (launcher == null) return Status.Unavailable(NO_DOTNET)

        return try {
            Files.createDirectories(home)
            withFileLock(home.resolve(".lock")) { unpackIfNeeded() }
            for (dir in listOf("home", "tmp")) Files.createDirectories(home.resolve(dir))
            Status.Ready(VERSION).also { ready = it }
        } catch (e: Exception) {
            Log.d(TAG, "could not prepare the .NET tree", e)
            Status.Unavailable("The .NET tree could not be prepared: ${e.message}")
        }
    }

    /** The environment every .NET process gets. */
    fun environment(): Map<String, String> = mapOf(
        "DOTNET_ROOT" to root.toString(),
        "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT" to "1",
        // The runtime looks for OpenSSL under several names, the device's own `libssl.so` among them, which
        // is a different library. This names the one carried in the tree's `lib/`.
        "DOTNET_OPENSSL_VERSION_OVERRIDE" to "3",
        "LD_LIBRARY_PATH" to listOfNotNull(
            root.resolve("lib").toString(),
            System.getenv("LD_LIBRARY_PATH")?.takeIf { it.isNotEmpty() },
        ).joinToString(":"),
        "HOME" to home.resolve("home").toString(),
        "TMPDIR" to home.resolve("tmp").toString(),
        "TERM" to "dumb",
    )

    // References

    /** The file names (lowercase) of the reference assemblies: what the framework provides, so no package must. */
    fun frameworkDllNames(): Set<String> =
        references().map { it.fileName.toString().lowercase() }.toSet()

    /** The reference assemblies the compiler is given with `-r:`, sorted. Empty until [prepare] has unpacked. */
    fun references(): List<Path> {
        val dir = root.resolve("ref")
        if (!Files.isDirectory(dir)) return emptyList()

        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".dll") }
                .collect(Collectors.toList())
        }.sortedBy { it.fileName.toString() }
    }

    /** The plugin's private directory, after removing the legacy tree; null on a host that has none. */
    private fun privateHome(): Path? {
        // NoSuchMethodError (or AbstractMethodError) on a host from before the property.
        val dir = try {
            reg.privateDataDir
        } catch (e: LinkageError) {
            return null
        }

        internalHome()?.let { legacy ->
            runCatching {
                dropLegacyHome(legacy, dir)
            }.onFailure {
                Log.d(TAG, "could not remove the .NET tree at $legacy; it stays there", it)
            }
        }
        return dir
    }

    /**
     * `<app data>/files/dotnet-plugin`. ART points `java.io.tmpdir` at the app's own cache directory in every
     * process of the app, so its parent is the app's data directory.
     * Null off Android, where the plugin's own data directory serves.
     */
    private fun internalHome(): Path? {
        val tmp = System.getProperty("java.io.tmpdir")
            ?.takeIf { it.startsWith("/data/") }
            ?: return null

        val appData = Paths.get(tmp).parent ?: return null
        return appData.resolve("files").resolve("dotnet-plugin")
            .takeIf { Files.isDirectory(appData, LinkOption.NOFOLLOW_LINKS) }
    }

    /**
     * Removes legacy, where an earlier version of this plugin kept the unpacked tree, once the plugin has a
     * private directory of its own. Nothing is carried over: the tree, compiler host, symbol tool and editor's
     * scratch files are rebuilt from the asset.
     */
    private fun dropLegacyHome(legacy: Path, dir: Path) {
        if (legacy == dir || dir.startsWith(legacy)) return
        if (!Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)) return

        val root = dir.parent ?: return
        withFileLock(root.resolve(".dotnet-plugin-cleanup.lock")) {
            if (!Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)) return@withFileLock
            legacy.toFile().deleteRecursively()
            Log.d(TAG, "removed the .NET tree an earlier version kept at $legacy")
        }
    }

    /** Unpacks the asset into root unless the stamp says this VERSION is already there. */
    private fun unpackIfNeeded() {
        val stamp = root.resolve(".unpacked")
        val unpackedVersion = runCatching {
            String(Files.readAllBytes(stamp)).trim()
        }.getOrNull()

        if (unpackedVersion == VERSION) return

        val stream = javaClass.classLoader?.getResourceAsStream(ASSET)
            ?: throw IllegalStateException("$ASSET is not packaged in this plugin")

        val progress = messages?.startProgress("Preparing .NET")
        val started = System.nanoTime()

        try {
            // A partial unpack from an interrupted run must not read as complete.
            if (Files.exists(root)) root.toFile().deleteRecursively()
            Files.createDirectories(root)

            var files = 0
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = root.resolve(entry.name).normalize()
                    require(target.startsWith(root)) {
                        "entry escapes the .NET directory: ${entry.name}"
                    }

                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                        files++
                        if (files % 25 == 0) {
                            progress?.detail = entry.name.substringAfterLast('/')
                        }
                    }
                    zip.closeEntry()
                }
            }

            Files.write(stamp, VERSION.toByteArray())
            Log.d(TAG, "unpacked $files .NET files in ${(System.nanoTime() - started) / 1_000_000} ms")
        } finally {
            progress?.finish()
        }
    }

    /** Runs [block] holding an exclusive lock on [file], across processes. */
    private inline fun <T> withFileLock(file: Path, block: () -> T): T =
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.channel.lock().use { block() }
        }

    companion object {
        /** The runtime version staged into the asset; also the unpack stamp, so a new tree unpacks afresh. */
        const val VERSION = "10.0.15-3"

        /** What the user is told when there is no launcher for this device's architecture. */
        const val NO_DOTNET =
            "This plugin has no .NET for this device (it carries one for Android arm64 only)."

        private const val ASSET = "assets/dotnet-sdk.zip"
    }
}