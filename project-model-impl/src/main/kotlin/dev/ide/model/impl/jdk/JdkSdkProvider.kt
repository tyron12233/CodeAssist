package dev.ide.model.impl.jdk

import dev.ide.model.impl.SdkData
import java.nio.file.Files
import java.nio.file.Path

/**
 * Discovers the platform classpath the project model should expose as a boot-classpath [SdkData],
 * covering the three cases the IDE must support:
 *  1. a classic JRE/JDK with a `rt.jar` (Java 8 and earlier) -> that jar;
 *  2. a modular runtime image (Java 9+ / a JLINK image), detected by `lib/modules` or `jmods`
 *     -> the JDK home itself (the language backend reads its `jrt:` image as bytecode);
 *  3. otherwise a synthetic stub platform ([SyntheticJdk]) so resolution/completion of core
 *     `java.lang` types still works with no JDK installed (e.g. a fresh device).
 *
 * The resulting [SdkData] flows into the model's SDK table; a module's `CompilationContext` then
 * takes its boot classpath from there. The analyzer never touches the host JVM.
 */
object JdkSdkProvider {

    /** Inspect [javaHome] (defaults to the running JDK) and produce the platform SDK. */
    fun detect(javaHome: Path = Path.of(System.getProperty("java.home"))): SdkData {
        val rtJar = javaHome.resolve("lib").resolve("rt.jar")
        val jreRtJar = javaHome.resolve("jre").resolve("lib").resolve("rt.jar")
        val modules = javaHome.resolve("lib").resolve("modules")
        val jmods = javaHome.resolve("jmods")
        return when {
            Files.isRegularFile(rtJar) -> SdkData("jdk", listOf(rtJar.toString()), buildToolsPath = null)
            Files.isRegularFile(jreRtJar) -> SdkData("jre", listOf(jreRtJar.toString()), buildToolsPath = null)
            Files.exists(modules) || Files.isDirectory(jmods) ->
                SdkData("jdk", listOf(javaHome.toAbsolutePath().normalize().toString()), buildToolsPath = null)
            else -> synthetic()
        }
    }

    /** A synthetic platform: minimal `java.lang` source stubs written under [dir]. */
    fun synthetic(dir: Path = Files.createTempDirectory("codeassist-synthetic-jdk")): SdkData {
        SyntheticJdk.writeInto(dir)
        return SdkData("synthetic", listOf(dir.toAbsolutePath().normalize().toString()), buildToolsPath = null)
    }
}
