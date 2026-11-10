package dev.ide.buildcli

import java.net.HttpURLConnection
import java.net.URL

/**
 * The newest stable versions of a Maven artifact, read from its `maven-metadata.xml` on Google's Maven repository
 * or Maven Central (the two the IDE resolves from by default). Stable means no pre-release qualifier.
 */
internal object MavenVersions {
    private val REPOSITORIES = listOf("https://dl.google.com/android/maven2", "https://repo1.maven.org/maven2")
    private val PRE_RELEASE = Regex("""(?i)(alpha|beta|rc|cr|dev|eap|preview|snapshot|milestone|-m\d|\.m\d)""")

    /** The [count] newest stable versions of `group:artifact`, oldest first; empty when no repository has it. */
    fun latestStable(group: String, artifact: String, count: Int): List<String> {
        for (repo in REPOSITORIES) {
            val xml = get("$repo/${group.replace('.', '/')}/$artifact/maven-metadata.xml") ?: continue
            val versions = Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1].trim() }
                .filterNot { PRE_RELEASE.containsMatchIn(it) }.distinct().toList()
            if (versions.isNotEmpty()) return versions.sortedWith(::compare).takeLast(count)
        }
        return emptyList()
    }

    /** Maven-ish ordering for plain release versions: numeric parts compared as numbers, the rest as text. */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.', '-'); val pb = b.split('.', '-')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return -1
            val y = pb.getOrNull(i) ?: return 1
            val c = when {
                x.toLongOrNull() != null && y.toLongOrNull() != null -> x.toLong().compareTo(y.toLong())
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return 0
    }

    private fun get(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        try { if (conn.responseCode == 200) conn.inputStream.use { it.readBytes().decodeToString() } else null } finally { conn.disconnect() }
    }.getOrNull()
}
