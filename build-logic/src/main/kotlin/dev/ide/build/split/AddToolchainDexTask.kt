package dev.ide.build.split

import com.android.apksig.ApkSigner
import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.variant.BuiltArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import com.android.zipflinger.BytesSource
import com.android.zipflinger.ZipArchive
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.Deflater

/**
 * Adds the toolchain's dex ([DexToolchainTask]) to the APK after the dex R8 wrote, then signs the APK again.
 *
 * ART loads `classes.dex`, `classes2.dex`, ... until a number is missing, so the toolchain's files are
 * numbered on from the last one R8 wrote. Re-signing uses the build type's own key and v1-v3 settings; the
 * signer also aligns stored entries (4 bytes, and 16 KB pages for native libraries) as AGP's packager does.
 */
abstract class AddToolchainDexTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val apkIn: DirectoryProperty
    @get:OutputDirectory abstract val apkOut: DirectoryProperty
    @get:Internal abstract val transformationRequest: Property<ArtifactTransformationRequest<AddToolchainDexTask>>

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val dexDir: DirectoryProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val storeFile: RegularFileProperty
    @get:Input @get:Optional abstract val storeType: Property<String>
    @get:Internal abstract val storePassword: Property<String>
    @get:Input abstract val keyAlias: Property<String>
    @get:Internal abstract val keyPassword: Property<String>
    @get:Input abstract val v1: Property<Boolean>
    @get:Input abstract val v2: Property<Boolean>
    @get:Input abstract val v3: Property<Boolean>
    @get:Input abstract val minSdk: Property<Int>

    @TaskAction
    fun run() {
        val dex = dexDir.get().asFile.listFiles { f -> f.name.endsWith(".dex") }.orEmpty()
            .sortedBy { it.name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1 }
        transformationRequest.get().submit(this) { artifact: BuiltArtifact ->
            val input = File(artifact.outputFile)
            val output = apkOut.get().file(input.name).asFile
            val unsigned = File(temporaryDir, "unsigned-${input.name}")
            input.copyTo(unsigned, overwrite = true)
            val added = appendDex(unsigned, dex)
            sign(unsigned, output)
            unsigned.delete()
            logger.lifecycle("${output.name}: added the toolchain as $added")
            output
        }
    }

    /**
     * Copies [dex] into [apk] as the next `classesN.dex` entries; returns the range it used.
     *
     * With zipflinger, AGP's own archive editor: it edits in place and writes entries the way AGP's packager
     * does (sizes in the local headers, no data descriptors), and the dex go in uncompressed like AGP's, so
     * ART can map them straight from the APK.
     */
    private fun appendDex(apk: File, dex: List<File>): String {
        ZipArchive(apk.toPath()).use { zip ->
            val names = zip.listEntries()
            // AGP's v1 signature no longer matches once entries change, and with v1 off the signer would leave
            // it in place. (The APK Signing Block goes when the signer writes the output.)
            names.filter { it.startsWith("META-INF/") && V1_SIGNATURE.matches(it.removePrefix("META-INF/")) }
                .forEach(zip::delete)
            val existing = names.count { DEX_NAME.matches(it) }
            dex.forEachIndexed { i, f ->
                zip.add(BytesSource(f.toPath(), "classes${existing + 1 + i}.dex", Deflater.NO_COMPRESSION))
            }
            return "classes${existing + 1}.dex..classes${existing + dex.size}.dex"
        }
    }

    private fun sign(input: File, output: File) {
        val ks = KeyStore.getInstance(storeType.orNull ?: KeyStore.getDefaultType())
        storeFile.get().asFile.inputStream().use { ks.load(it, storePassword.get().toCharArray()) }
        val key = ks.getKey(keyAlias.get(), keyPassword.get().toCharArray()) as PrivateKey
        val certs = ks.getCertificateChain(keyAlias.get()).map { it as X509Certificate }
        val signer = ApkSigner.SignerConfig.Builder(keyAlias.get(), key, certs).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk.get())
            .setV1SigningEnabled(v1.get())
            .setV2SigningEnabled(v2.get())
            .setV3SigningEnabled(v3.get())
            .setLibraryPageAlignmentBytes(16 * 1024)
            .build()
            .sign()
    }

    private companion object {
        val DEX_NAME = Regex("classes\\d*\\.dex")
        val V1_SIGNATURE = Regex("MANIFEST\\.MF|.+\\.(SF|RSA|DSA|EC)")
    }
}
