package dev.ide.android.support.tools

import dev.ide.model.impl.format.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * One registered signing keystore: the file (copied into the registry dir so it's self-contained) plus the
 * alias and passwords needed to sign. A build type references it by [id]; the secrets live HERE (app-home),
 * never in the project's `module.toml`.
 */
data class KeystoreEntry(
    val id: String,
    val name: String,
    val file: String,
    val storePass: String,
    val keyAlias: String,
    val keyPass: String,
)

/**
 * The app-global keystore registry: created/imported keystores live under [dir] (the launcher's home dir,
 * shared across projects), described by `registry.json`. Created keystores are minted via [KeystoreCrypto];
 * imported ones are validated then copied in. A build resolves a build type's `signingConfig` id to a
 * [SigningConfig] through [signingConfigFor]. Mutations are atomic (temp file + rename) and `@Synchronized`.
 */
class KeystoreRegistry(private val dir: Path) {

    private val registryFile: Path = dir.resolve("registry.json")

    @Synchronized
    fun all(): List<KeystoreEntry> = load()

    @Synchronized
    fun get(id: String): KeystoreEntry? = load().firstOrNull { it.id == id }

    /** The build [SigningConfig] for keystore [id], or null when the id is unknown or its file is gone. */
    @Synchronized
    fun signingConfigFor(id: String): SigningConfig? {
        val e = get(id) ?: return null
        val f = Paths.get(e.file)
        if (!Files.isRegularFile(f)) return null
        return SigningConfig(f, e.storePass, e.keyAlias, e.keyPass)
    }

    /** Generate a new keystore from [spec] and register it under [name]. */
    @Synchronized
    fun create(name: String, spec: KeystoreCreateSpec): Result<KeystoreEntry> {
        val id = freshId(name.ifBlank { spec.keyAlias })
        val file = dir.resolve("$id.jks")
        val r = KeystoreCrypto.create(file, spec)
        if (!r.success) return Result.failure(IllegalStateException(r.message))
        // Created PKCS12 keystores use one password for both the store and the key (see KeystoreCreateSpec).
        val entry = KeystoreEntry(id, name.ifBlank { id }, file.toAbsolutePath().toString(), spec.storePass, spec.keyAlias, spec.storePass)
        save(load() + entry)
        return Result.success(entry)
    }

    /**
     * Import the existing keystore at [source]: verify [storePass] opens it, pick [keyAlias] (or the sole
     * alias), copy the file into the registry dir, and register it. [keyPass] defaults to [storePass] (the
     * PKCS12 norm) when blank.
     */
    @Synchronized
    fun import(name: String, source: Path, storePass: String, keyAlias: String, keyPass: String): Result<KeystoreEntry> {
        val validation = KeystoreCrypto.validate(source, storePass)
        if (!validation.valid) return Result.failure(IllegalStateException(validation.error ?: "Could not open the keystore."))
        if (keyAlias.isNotBlank() && keyAlias !in validation.aliases) {
            return Result.failure(IllegalStateException("Alias '$keyAlias' not found. Available: ${validation.aliases.joinToString()}"))
        }
        val alias = keyAlias.ifBlank {
            validation.aliases.firstOrNull() ?: return Result.failure(IllegalStateException("Keystore has no key entries."))
        }
        val baseName = name.ifBlank { source.fileName.toString().substringBeforeLast('.') }
        val id = freshId(baseName)
        val ext = source.fileName.toString().substringAfterLast('.', "jks")
        val file = dir.resolve("$id.$ext")
        Files.createDirectories(dir)
        Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING)
        val entry = KeystoreEntry(id, baseName, file.toAbsolutePath().toString(), storePass, alias, keyPass.ifBlank { storePass })
        save(load() + entry)
        return Result.success(entry)
    }

    /** Remove keystore [id] from the registry and delete its file. */
    @Synchronized
    fun delete(id: String): Boolean {
        val entries = load()
        val e = entries.firstOrNull { it.id == id } ?: return false
        runCatching { Files.deleteIfExists(Paths.get(e.file)) }
        save(entries.filterNot { it.id == id })
        return true
    }

    // ---- persistence ----

    private fun load(): List<KeystoreEntry> {
        if (!Files.isRegularFile(registryFile)) return emptyList()
        val rows = runCatching { Json.parse(registryFile.readText()) }.getOrNull() as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            val file = m["file"] as? String ?: return@mapNotNull null
            KeystoreEntry(
                id = id,
                name = m["name"] as? String ?: id,
                file = file,
                storePass = m["storePass"] as? String ?: "",
                keyAlias = m["keyAlias"] as? String ?: "",
                keyPass = m["keyPass"] as? String ?: "",
            )
        }
    }

    private fun save(entries: List<KeystoreEntry>) {
        Files.createDirectories(dir)
        val list = entries.map {
            linkedMapOf(
                "id" to it.id, "name" to it.name, "file" to it.file,
                "storePass" to it.storePass, "keyAlias" to it.keyAlias, "keyPass" to it.keyPass,
            )
        }
        val tmp = registryFile.resolveSibling("registry.json.tmp")
        tmp.writeText(Json.write(list))
        runCatching { Files.move(tmp, registryFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(tmp, registryFile, StandardCopyOption.REPLACE_EXISTING) }
    }

    /** A filesystem-safe, unique id from [name] (`My Release Key` → `my-release-key`, `-2` on collision). */
    private fun freshId(name: String): String {
        val base = name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .trim('-').replace(Regex("-+"), "-").ifBlank { "keystore" }
        val existing = load().mapTo(HashSet()) { it.id }
        if (base !in existing) return base
        var n = 2
        while ("$base-$n" in existing) n++
        return "$base-$n"
    }
}
