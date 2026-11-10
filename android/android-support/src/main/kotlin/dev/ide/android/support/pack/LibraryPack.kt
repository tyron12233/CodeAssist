package dev.ide.android.support.pack

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Per-library build products made once on our machines and downloaded by devices instead of being produced on
 * them: a library's dex today, its index segments next. They live in a public repository laid out like Maven
 * Central ([DEFAULT_BASE_URL]), one file per library version, so a phone downloads exactly the libraries its
 * project uses and nothing else.
 *
 * Every file is a [SignedArchive]: the signature is the trust, because these files become code inside the user's
 * app (dex) or answers the editor gives (index), and a host or network path that swapped one must not get it
 * installed. The private key never enters this repository; [PUBLIC_KEY] is its public half.
 */
object LibraryPack {
    /** The repository root: `<base>/dex/<flavor>/<group path>/<artifact>/<version>/<artifact>-<version>.dex.zip`. */
    const val DEFAULT_BASE_URL = "https://raw.githubusercontent.com/tyron12233/codeassist-dex/main"

    /** The publisher's public key (X.509 DER, base64). */
    const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1DBFVmYHxns0Spdp9HHKg7Op28BRUNkUFTH15fBMhFUcFC2vMBgWgpmpMLNn1/vUKrvz9t15fd12KzVW3Zf6HA=="

    fun publicKey(base64: String = PUBLIC_KEY): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun sign(data: ByteArray, key: PrivateKey): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() })

    fun verify(data: ByteArray, signatureBase64: String, key: PublicKey): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(data); verify(Base64.getDecoder().decode(signatureBase64.trim()))
        }
    }.getOrDefault(false)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The files under [dir] by path relative to it, sorted; names starting with `.` (a consumer's own bookkeeping,
     *  such as a dex bucket's completeness sentinel) are left out at any level. */
    fun filesOf(dir: Path): List<Pair<String, Path>> =
        Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.collect(java.util.stream.Collectors.toList()) }
            .map { dir.relativize(it).toString().replace('\\', '/') to it }
            .filter { (rel, _) -> rel.split('/').none { it.startsWith(".") } }
            .sortedBy { it.first }

    /** How a client reads the repository: the bytes at a URL, or null when it isn't there or can't be read. */
    fun interface Net {
        fun get(url: String): ByteArray?
    }

    /** Plain HTTPS with short timeouts: a download is an optimization, and a slow or absent host must not hold up a build. */
    object HttpNet : Net {
        override fun get(url: String): ByteArray? = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000; readTimeout = 20_000; instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) null else conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}

/**
 * A zip that carries its own proof: `META-INF/codeassist/MANIFEST` lists free-form attribute lines and the
 * SHA-256 of every other entry, and `META-INF/codeassist/SIGNATURE` signs the manifest. [open] returns the
 * contents only if the signature verifies, every listed file is present with its hash, and nothing unlisted
 * rides along, so one download is a complete, checkable unit with no separate index to fetch.
 */
object SignedArchive {
    private const val FORMAT = "codeassist-signed-archive 1"
    private const val MANIFEST = "META-INF/codeassist/MANIFEST"
    private const val SIGNATURE = "META-INF/codeassist/SIGNATURE"

    class Opened(val attributes: List<String>, val files: Map<String, ByteArray>)

    /** Zip [files] (name to bytes) with a manifest of [attributes] and their hashes, signed with [key]. Deterministic. */
    fun write(attributes: List<String>, files: Map<String, ByteArray>, key: PrivateKey): ByteArray {
        require(attributes.none { '\n' in it || it.startsWith("file ") }) { "attribute lines are single lines, not `file`" }
        val sorted = files.toSortedMap()
        sorted.keys.forEach { require(isSafeName(it)) { "unsafe archive entry: $it" } }
        val manifest = buildString {
            append(FORMAT).append('\n')
            attributes.forEach { append(it).append('\n') }
            sorted.forEach { (name, bytes) -> append("file ").append(LibraryPack.sha256(bytes)).append(' ').append(name).append('\n') }
        }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0L }); zip.write(bytes); zip.closeEntry()
            }
            put(MANIFEST, manifest)
            put(SIGNATURE, LibraryPack.sign(manifest, key).toByteArray())
            sorted.forEach { (name, bytes) -> put(name, bytes) }
        }
        return out.toByteArray()
    }

    /** The verified contents of [archive], or null when anything about it doesn't check out. */
    fun open(archive: ByteArray, key: java.security.PublicKey): Opened? = runCatching {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (entries.put(e.name, zip.readBytes()) != null) return null      // a name twice: refuse, don't pick one
            }
        }
        val manifest = entries.remove(MANIFEST) ?: return null
        val signature = entries.remove(SIGNATURE) ?: return null
        if (!LibraryPack.verify(manifest, String(signature), key)) return null
        val lines = String(manifest).lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != FORMAT) return null
        val listed = HashMap<String, String>()
        val attributes = ArrayList<String>()
        for (line in lines.drop(1)) {
            if (line.startsWith("file ")) {
                val sha = line.substring(5).substringBefore(' ')
                val name = line.substring(5).substringAfter(' ')
                if (!isSafeName(name)) return null
                listed[name] = sha
            } else attributes += line
        }
        if (listed.keys != entries.keys) return null
        if (entries.any { (name, bytes) -> LibraryPack.sha256(bytes) != listed[name] }) return null
        Opened(attributes, entries)
    }.getOrNull()

    /** A relative path with no `..`, no absolute root and no backslashes. */
    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && name.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
