package dev.ide.android.support.tools

import dev.ide.android.support.tasks.DexArchives
import java.nio.file.Files
import java.nio.file.Path

/**
 * Prepares library jars for the bundled in-process D8 ([D8InProcessDexer]) OUTSIDE the APK-build task graph —
 * currently the on-device Compose-preview library loader.
 *
 * The bundled r8/D8 (8.13.x) ships a `kotlin-metadata-jvm` older than this repo's Kotlin (2.4): when it can't
 * rewrite a class's `@kotlin.Metadata` it throws internally, logs only a warning, returns "success", and
 * **drops the whole invocation's per-class dex output** — silently omitting classes like
 * material-icons-extended's `<Icon>Kt` facades, which then fail to load at render ("cannot load facade
 * …RemoveKt"). The APK build's library dexer already strips `@Metadata` before dexing for exactly this reason;
 * this exposes the same preparation to callers outside the task graph.
 */
object DexInputPrep {

    /**
     * Return [jars] with each KOTLIN jar replaced by a `@kotlin.Metadata`-stripped copy written under [workDir]
     * (pure-Java jars pass through unchanged). Stripping only removes the annotation used by kotlin-reflect —
     * not anything execution/Compose needs — so the dexed classes still run. A jar that can't be stripped falls
     * back to the original (dexing it may still drop it, but that's no worse than today).
     */
    fun stripKotlinMetadata(jars: List<Path>, workDir: Path): List<Path> {
        Files.createDirectories(workDir)
        return jars.mapIndexed { i, jar ->
            runCatching { DexArchives.strippedJar(jar, workDir.resolve("stripped-$i-${jar.fileName}")) }.getOrDefault(jar)
        }
    }
}
