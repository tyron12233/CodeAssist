package dev.ide.android.support.tools

import com.android.tools.profgen.ArtProfile
import com.android.tools.profgen.ArtProfileSerializer
import com.android.tools.profgen.DexFile
import com.android.tools.profgen.DexFileData
import com.android.tools.profgen.HumanReadableProfile
import com.android.tools.profgen.ObfuscationMap
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Baseline Profiles: the `baseline-prof.txt` rules an app and its libraries ship, compiled into the binary
 * `baseline.prof` (+ `baseline.profm`) that ART uses to AOT-compile the listed classes and methods at install
 * time instead of interpreting them on first launch. This is AGP's `merge`/`compile<Variant>ArtProfile` pair,
 * over the vendored profgen (`src/main/vendor`).
 *
 * The binary profile names methods by their index in a specific dex file and records that file's checksum, so
 * it can only be produced against the exact dex that ships: a `.prof` from another build (even of the same
 * sources) is rejected by ART. That is why this runs after dexing, over the dex files in packaging order.
 */
object ArtProfiles {

    /** The rules file a module or AAR ships; AGP reads the module's from `src/main/`. */
    const val RULES_FILE = "baseline-prof.txt"

    /** The R8 dex-layout file that shares the `baselineProfiles/` folder but is not profile rules. */
    private const val STARTUP_RULES_FILE = "startup-prof.txt"

    /** Where an APK carries the compiled profile. `androidx.profileinstaller` reads it from here, and only
     *  when the entries are STORED (it opens them as file descriptors). */
    const val APK_PROFILE = "assets/dexopt/baseline.prof"
    const val APK_PROFILE_METADATA = "assets/dexopt/baseline.profm"

    /** Where a bundle carries it: bundletool metadata, which Play turns into per-device profiles. */
    const val BUNDLE_METADATA_DIR = "com.android.tools.build.profiles"
    const val PROFILE_NAME = "baseline.prof"
    const val PROFILE_METADATA_NAME = "baseline.profm"

    /**
     * The rules files a module's own sources contribute for the source sets [sourceSets] (lowest priority
     * first): `src/main/baseline-prof.txt`, the location AGP has always read, then every `.txt` under each
     * set's `baselineProfiles/` folder, and under `generated/baselineProfiles/`, which is where the
     * `androidx.baselineprofile` Gradle plugin writes the profile it generated on a device.
     */
    fun sourceRules(srcDir: Path, sourceSets: Collection<String>): List<Path> {
        val out = LinkedHashSet<Path>()
        srcDir.resolve("main").resolve(RULES_FILE).takeIf { Files.isRegularFile(it) }?.let(out::add)
        for (set in sourceSets) {
            val setDir = srcDir.resolve(set)
            for (dir in listOf(setDir.resolve("baselineProfiles"), setDir.resolve("generated").resolve("baselineProfiles"))) {
                if (!Files.isDirectory(dir)) continue
                Files.walk(dir).use { s ->
                    s.filter {
                        Files.isRegularFile(it) && it.fileName.toString().endsWith(".txt") &&
                            it.fileName.toString() != STARTUP_RULES_FILE
                    }.sorted().collect(Collectors.toList())
                }.forEach(out::add)
            }
        }
        return out.toList()
    }

    /** A rules file and whose it is: [own] rules come from the project, the rest from its libraries. */
    data class RulesFile(val path: Path, val own: Boolean)

    sealed class Result {
        /** The profile was written, covering [methods] methods and [classes] classes. */
        data class Compiled(val methods: Int, val classes: Int, val warnings: List<String>) : Result()

        /** No rules to compile (or none matched the dex), so there is no profile to package. */
        data class Empty(val warnings: List<String>) : Result()

        /** A rules file of the project's own does not parse; the build should stop and say where. */
        data class Failed(val errors: List<RuleError>) : Result()
    }

    /** A rule that does not parse: [line] and [column] are 1-based; [message] carries the line and a caret. */
    data class RuleError(val file: Path, val line: Int, val column: Int, val message: String) {
        override fun toString() = "$file:$line:$column: $message"
    }

    /**
     * Merge [rules] and compile them against [dexFiles] (packaged entry name to file, in packaging order).
     * [mapping] is R8's `mapping.txt` when the dex is obfuscated, so rules written against source names still
     * find their methods. Writes the merged text to [mergedOut] and the binary pair to [profileOut] /
     * [metadataOut]; on [Result.Empty] or [Result.Failed] those two are deleted, so a stale profile from an
     * earlier build is never packaged.
     *
     * Like AGP, a rule that does not parse in the project's own file fails the build. Unlike AGP, one in a
     * LIBRARY's file only skips that file with a warning: the library's rules are not the user's to fix, and a
     * missing profile costs startup time, not correctness.
     */
    fun compile(
        rules: List<RulesFile>,
        dexFiles: List<Pair<String, Path>>,
        mapping: Path?,
        mergedOut: Path,
        profileOut: Path,
        metadataOut: Path,
    ): Result {
        Files.deleteIfExists(profileOut)
        Files.deleteIfExists(metadataOut)
        val warnings = ArrayList<String>()
        val errors = ArrayList<RuleError>()
        val texts = ArrayList<String>()
        for (file in rules) {
            val text = String(Files.readAllBytes(file.path), Charsets.UTF_8)
            val problems = ArrayList<RuleError>()
            parse(text) { line, column, message -> problems += RuleError(file.path, line + 1, column + 1, message) }
            when {
                problems.isEmpty() -> texts += text
                file.own -> errors += problems
                else -> warnings += "skipped library Baseline Profile ${file.path}: ${problems.first()}"
            }
        }
        if (errors.isNotEmpty()) return Result.Failed(errors)
        if (texts.isEmpty()) return Result.Empty(warnings)

        // Joined the way AGP's MergeFileTask joins them, so the merged file reads the same as AGP's.
        val merged = texts.joinToString("\n")
        mergedOut.parent?.let { Files.createDirectories(it) }
        Files.write(mergedOut, merged.toByteArray(Charsets.UTF_8))
        val hrp = parse(merged) { _, _, _ -> } ?: return Result.Empty(warnings)
        val obf = mapping?.takeIf { Files.isRegularFile(it) }?.let { ObfuscationMap(it.toFile()) } ?: ObfuscationMap.Empty

        // One dex at a time: a parsed DexFile holds its whole string/type/method pool, which for a large app's
        // ten-odd dex files is more than the build process should keep at once. Only the matched indices are
        // kept, against a header-only copy of each file (all the serializer reads).
        val data = LinkedHashMap<DexFile, DexFileData>()
        for ((name, path) in dexFiles) {
            val dex = Files.newInputStream(path).buffered().use { DexFile(it, name) }
            val matched = ArtProfile(hrp, obf, listOf(dex)).profileData[dex] ?: continue
            data[DexFile(dex.header, dex.dexChecksum, dex.name)] = matched
        }
        if (data.isEmpty()) return Result.Empty(warnings)

        val profile = ArtProfile(data)
        profileOut.parent?.let { Files.createDirectories(it) }
        metadataOut.parent?.let { Files.createDirectories(it) }
        // The P format is what AGP writes too: Play and profileinstaller transcode it for other ART versions,
        // using the metadata file.
        Files.newOutputStream(profileOut).use { profile.save(it, ArtProfileSerializer.V0_1_0_P) }
        Files.newOutputStream(metadataOut).use { profile.save(it, ArtProfileSerializer.METADATA_0_0_2) }
        return Result.Compiled(
            methods = data.values.sumOf { it.methods.size },
            classes = data.values.sumOf { it.classIndexes.size },
            warnings = warnings,
        )
    }

    private fun parse(text: String, onError: (Int, Int, String) -> Unit): HumanReadableProfile? =
        InputStreamReader(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), Charsets.UTF_8).use {
            HumanReadableProfile(it, onError)
        }
}
