package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * The shared Android debug keystore (the same well-known `androiddebugkey`/`android` convention Android
 * Studio uses). Created once with `keytool` from the running JVM and reused, so debug builds are signed
 * without any user setup. Release signing supplies its own [SigningConfig].
 */
object DebugKeystore {
    const val STORE_PASS = "android"
    const val KEY_ALIAS = "androiddebugkey"
    const val KEY_PASS = "android"

    fun getOrCreate(keystore: Path, keytool: Path): SigningConfig {
        if (!Files.isRegularFile(keystore)) {
            keystore.parent?.let { Files.createDirectories(it) }
            val r = Subprocess.run(
                listOf(
                    keytool.toString(), "-genkeypair",
                    "-keystore", keystore.toString(),
                    "-storepass", STORE_PASS,
                    "-keypass", KEY_PASS,
                    "-alias", KEY_ALIAS,
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "10000",
                    "-dname", "CN=Android Debug,O=Android,C=US",
                ),
            )
            check(r.success && Files.isRegularFile(keystore)) {
                "failed to create debug keystore:\n${r.log.joinToString("\n")}"
            }
        }
        return SigningConfig(keystore, STORE_PASS, KEY_ALIAS, KEY_PASS)
    }
}
