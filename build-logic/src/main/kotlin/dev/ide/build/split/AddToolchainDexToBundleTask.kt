package dev.ide.build.split

import com.android.zipflinger.BytesSource
import com.android.zipflinger.ZipArchive
import jdk.security.jarsigner.JarSigner
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.KeyStore
import java.util.zip.Deflater
import java.util.zip.ZipFile

/**
 * The bundle counterpart of [AddToolchainDexTask]: adds the toolchain's dex to the base module of the
 * `.aab` (`base/dex/`, numbered on from R8's), then signs the bundle again.
 *
 * A bundle carries a JAR (v1) signature only, made with the upload key; Play checks it on upload and signs
 * the APKs it generates with the app signing key. AGP's signature no longer matches once the dex are added,
 * so it is removed and the bundle signed again with the same key through the JDK's own JarSigner.
 */
abstract class AddToolchainDexToBundleTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val bundleIn: RegularFileProperty
    @get:OutputFile abstract val bundleOut: RegularFileProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val dexDir: DirectoryProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val storeFile: RegularFileProperty
    @get:Input @get:Optional abstract val storeType: Property<String>
    @get:Internal abstract val storePassword: Property<String>
    @get:Input abstract val keyAlias: Property<String>
    @get:Internal abstract val keyPassword: Property<String>

    @TaskAction
    fun run() {
        val dex = dexDir.get().asFile.listFiles { f -> f.name.endsWith(".dex") }.orEmpty()
            .sortedBy { it.name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1 }
        val unsigned = temporaryDir.resolve("unsigned.aab")
        bundleIn.get().asFile.copyTo(unsigned, overwrite = true)

        val range = ZipArchive(unsigned.toPath()).use { zip ->
            val names = zip.listEntries()
            names.filter { it.startsWith("META-INF/") && V1_SIGNATURE.matches(it.removePrefix("META-INF/")) }
                .forEach(zip::delete)
            val existing = names.count { BASE_DEX.matches(it) }
            dex.forEachIndexed { i, f ->
                zip.add(BytesSource(f.toPath(), "base/dex/classes${existing + 1 + i}.dex", Deflater.DEFAULT_COMPRESSION))
            }
            "base/dex/classes${existing + 1}.dex..classes${existing + dex.size}.dex"
        }

        val ks = KeyStore.getInstance(storeType.orNull ?: KeyStore.getDefaultType())
        storeFile.get().asFile.inputStream().use { ks.load(it, storePassword.get().toCharArray()) }
        val entry = ks.getEntry(keyAlias.get(), KeyStore.PasswordProtection(keyPassword.get().toCharArray()))
            as KeyStore.PrivateKeyEntry
        val out = bundleOut.get().asFile
        out.parentFile.mkdirs()
        ZipFile(unsigned).use { zip ->
            out.outputStream().buffered().use { os ->
                // The .SF/.RSA names jarsigner would pick for this alias: its first 8 characters, upper-cased.
                val signerName = keyAlias.get().uppercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                    .take(8).ifEmpty { "SIGNER" }
                JarSigner.Builder(entry).signerName(signerName).digestAlgorithm("SHA-256").build().sign(zip, os)
            }
        }
        unsigned.delete()
        logger.lifecycle("${out.name}: added the toolchain as $range")
    }

    private companion object {
        val BASE_DEX = Regex("base/dex/classes\\d*\\.dex")
        val V1_SIGNATURE = Regex("MANIFEST\\.MF|.+\\.(SF|RSA|DSA|EC)")
    }
}
