@file:OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)

package dev.ide.ios

import dev.ide.deps.impl.ArtifactFetcher
import dev.ide.kotlin.classfile.Crc32
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A fixture Maven repository, served at the REAL repository URLs this host resolves from.
 *
 * Serving Maven Central's own base rather than an invented one keeps [IosDependencies.REPOSITORIES]
 * under test: a typo in a repository URL would make every one of these miss, which is exactly the
 * failure a fixture at `https://fixture.invalid` would hide.
 */
internal class FixtureMaven : ArtifactFetcher {
    private val byUrl = HashMap<String, ByteArray>()

    /**
     * Whether there is a network at all.
     *
     * False makes [fetch] THROW, which is what a real fetcher does when the socket fails — and is the
     * distinction that matters here: a 404 returns null and is evidence the artifact does not exist (and
     * is negative-cached for a week), while a dead socket is evidence of nothing and must not be.
     */
    var reachable: Boolean = true

    override fun fetch(url: String): ByteArray? {
        if (!reachable) throw IllegalStateException("The Internet connection appears to be offline")
        return byUrl[url]
    }

    /** Publish `group:name:version`, optionally with the given jar bytes and transitive dependencies. */
    fun publish(
        group: String,
        name: String,
        version: String,
        jar: ByteArray = "not-really-a-jar".encodeToByteArray(),
        deps: List<Triple<String, String, String>> = emptyList(),
        packaging: String = "jar",
        base: String = CENTRAL,
    ) {
        val rel = "${group.replace('.', '/')}/$name/$version/$name-$version"
        byUrl["$base/$rel.pom"] = buildString {
            append("<?xml version=\"1.0\"?>\n<project>\n")
            append("<groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version>\n")
            append("<packaging>$packaging</packaging>\n")
            if (deps.isNotEmpty()) {
                append("<dependencies>\n")
                for ((g, a, v) in deps) {
                    append("<dependency><groupId>$g</groupId><artifactId>$a</artifactId>")
                    append("<version>$v</version></dependency>\n")
                }
                append("</dependencies>\n")
            }
            append("</project>\n")
        }.encodeToByteArray()
        byUrl["$base/$rel.$packaging"] = jar
    }

    /**
     * Publish an Android library: an `.aar` carrying [classesJar] inside it, served from GOOGLE.
     *
     * Google's Maven is where every `androidx.*` artifact actually lives and it is not mirrored to Central,
     * so publishing there keeps the second half of [IosDependencies.REPOSITORIES] under test too.
     */
    fun publishAar(
        group: String,
        name: String,
        version: String,
        classesJar: ByteArray,
        deps: List<Triple<String, String, String>> = emptyList(),
    ) = publish(group, name, version, aarBytes(classesJar), deps, packaging = "aar", base = GOOGLE)

    /** The real 2.6KB stdlib fixture, published where a real resolve would look for it. */
    @OptIn(ExperimentalEncodingApi::class)
    fun publishStdlib() = publish(
        "org.jetbrains.kotlin", "kotlin-stdlib", "2.4.0", Base64.decode(StdlibFixture.JAR_BASE64),
    )

    private companion object {
        const val CENTRAL = "https://repo1.maven.org/maven2"
        const val GOOGLE = "https://dl.google.com/dl/android/maven2"
    }
}

/**
 * A zip archive built in memory, so a test can publish a real `.aar` with no zip writer on the platform.
 *
 * Every entry is STORED, which is what a real AAR does with its `classes.jar` anyway: a jar is already
 * compressed, and deflating it again buys nothing. A copy of `:deps-impl`'s test helper, which is
 * file-private there for the same reason it is here.
 */
internal fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ArrayList<Byte>()
    fun u16(value: Int) {
        out.add((value and 0xFF).toByte())
        out.add(((value ushr 8) and 0xFF).toByte())
    }

    fun u32(value: Long) {
        u16((value and 0xFFFF).toInt())
        u16(((value ushr 16) and 0xFFFF).toInt())
    }

    fun raw(bytes: ByteArray) = bytes.forEach { out.add(it) }

    class Record(val name: ByteArray, val crc: Long, val size: Int, val offset: Int)

    val records = ArrayList<Record>()
    for ((name, content) in entries) {
        val nameBytes = name.encodeToByteArray()
        val offset = out.size
        val crc = Crc32.of(content)
        u32(0x04034b50L)
        u16(20); u16(0); u16(0); u16(0); u16(0)
        u32(crc); u32(content.size.toLong()); u32(content.size.toLong())
        u16(nameBytes.size); u16(0)
        raw(nameBytes); raw(content)
        records += Record(nameBytes, crc, content.size, offset)
    }

    val directoryOffset = out.size
    for (record in records) {
        u32(0x02014b50L)
        u16(20); u16(20); u16(0); u16(0); u16(0); u16(0)
        u32(record.crc); u32(record.size.toLong()); u32(record.size.toLong())
        u16(record.name.size); u16(0); u16(0)
        u16(0); u16(0); u32(0)
        u32(record.offset.toLong())
        raw(record.name)
    }

    val directorySize = out.size - directoryOffset
    u32(0x06054b50L)
    u16(0); u16(0); u16(records.size); u16(records.size)
    u32(directorySize.toLong()); u32(directoryOffset.toLong()); u16(0)
    return out.toByteArray()
}

/** An `.aar` the way one is actually shaped: code in `classes.jar`, plus the resources iOS never reads. */
internal fun aarBytes(classesJar: ByteArray): ByteArray = storedZip(
    listOf(
        "AndroidManifest.xml" to "<manifest package=\"com.example\" />".encodeToByteArray(),
        "classes.jar" to classesJar,
        "res/values/values.xml" to "<resources />".encodeToByteArray(),
    ),
)
