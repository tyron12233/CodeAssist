package com.android.tools.profgen

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

internal val MAGIC = byteArrayOf('p', 'r', 'o', '\u0000')


class ArtProfile internal constructor(
    val profileData: Map<DexFile, DexFileData>,
    private val apkName: String = ""
) {
    fun print(os: PrintStream, obf: ObfuscationMap) {
        for ((dexFile, data) in profileData) {
            for (typeIndex in data.typeIndexes) {
                val type = dexFile.typePool[typeIndex]
                obf.deobfuscate(type).forEach { os.println(it) }
            }
            for ((methodIndex, methodData) in data.methods) {
                val method = dexFile.methodPool[methodIndex]
                val deobfuscated = obf.deobfuscate(method)
                methodData.print(os)
                deobfuscated.print(os)
                os.println()
            }
        }
    }

    /**
     * Serializes the profile in the given output stream.
     *
     * @param os the output stream
     * @param version the serialization format/version to use
     */
    fun save(os: OutputStream, version: ArtProfileSerializer) {
        with(os) {
            write(version.magicBytes)
            write(version.versionBytes)
            version.write(os, profileData, apkName)
        }
    }

    private fun extractKey(key: String): String {
        val result =  key.substringAfter('!').substringAfter(':')
        assert(result.indexOf(':') == -1)
        assert(result.indexOf('!') == -1)
        return result
    }

    internal fun addMetadata(other: ArtProfile, version: MetadataVersion): ArtProfile {
        val keys = mutableSetOf<String>()
        val files = mutableMapOf<String, DexFile>()
        val outFiles = mutableMapOf<String, DexFile>()
        val outValues = mutableMapOf<String, DexFileData>()
        for ((file, value) in profileData) {
            val key = extractKey(file.name)
            keys += key
            files[key] = file
            outValues[key] = value
        }
        for ((file, value) in other.profileData) {
            val key = extractKey(file.name)
            keys += key
            val source = files[key]
            outFiles += if (source != null) {
                key to source.addMedata(file, version)
            } else {
                key to file
            }
            outValues[key] = value + outValues[key]
        }
        // Create new profile
        val combinedMap = mutableMapOf<DexFile, DexFileData>()
        for (key in keys) {
            val file = outFiles[key]
            val value = outValues[key]
            if (file != null && value != null) {
                combinedMap += (file to value)
            }
        }
        return ArtProfile(combinedMap, apkName)
    }

    private fun DexFile.addMedata(other: DexFile, version: MetadataVersion): DexFile {
        return when (version) {
            MetadataVersion.V_001 -> this
            MetadataVersion.V_002 -> {
                // Merge headers by swapping empty spans only for V002
                // Otherwise fall back to the original profile and treat it as the source of truth.

                // Ensure it's the same DexFile
                if (name == other.name) {
                    DexFile(
                        header = this.header.addMedata(other.header),
                        name = name,
                        dexChecksum = dexChecksum
                    )
                } else {
                    this
                }
            }
        }
    }

    private fun DexHeader.addMedata(other: DexHeader): DexHeader {
        return DexHeader(
            nonEmptySpan(stringIds, other.stringIds),
            nonEmptySpan(typeIds, other.typeIds),
            nonEmptySpan(prototypeIds, other.prototypeIds),
            nonEmptySpan(methodIds, other.methodIds),
            nonEmptySpan(classDefs, other.classDefs),
            nonEmptySpan(data, other.data)
        )
    }

    private fun nonEmptySpan(first: Span, second: Span): Span {
        if (first != Span.Empty) return first
        return second
    }
}

fun ArtProfile(hrp: HumanReadableProfile, obf: ObfuscationMap, apk: Apk): ArtProfile {
    return ArtProfile(hrp, obf, apk.dexes, apk.name)
}

fun ArtProfile(
        hrp: HumanReadableProfile,
        obf: ObfuscationMap,
        dexes: List<DexFile>,
        apkName: String = ""
): ArtProfile {
    val profileData = HashMap<DexFile, DexFileData>()
    for (iDex in dexes.indices) {
        val dex = dexes[iDex]
        val methods = dex.methodPool
        val types = dex.typePool
        val classDefs = dex.classDefPool

        val profileTypeIndexes = mutableSetOf<Int>()
        val profileClassIndexes = mutableSetOf<Int>()
        val profileMethods = mutableMapOf<Int, MethodData>()

        for (iMethod in methods.indices) {
            val method = methods[iMethod]
            val deobfuscated = obf.deobfuscate(method)
            val flags = hrp.match(deobfuscated)
            if (flags != 0) {
                profileMethods[iMethod] = MethodData(flags)
            }
        }

        for (classIndex in classDefs.indices) {
            val typeIndex = classDefs[classIndex]
            val type = types[typeIndex]
            if (obf.deobfuscate(type).any { hrp.match(it) != 0 }) {
                profileTypeIndexes.add(typeIndex)
                profileClassIndexes.add(classIndex)
            }
        }

        if (profileTypeIndexes.isNotEmpty() || profileMethods.isNotEmpty()) {
            profileData[dex] = DexFileData(
                    profileTypeIndexes,
                    profileClassIndexes,
                    profileMethods
            )
        }
    }
    return ArtProfile(profileData, apkName)
}

fun ArtProfile(src: InputStream): ArtProfile? {
    val version = src.readProfileVersion() ?: return null
    val profileData = version.read(src)
    return ArtProfile(profileData)
}

fun ArtProfile(file: File): ArtProfile? = file.inputStream().use { ArtProfile(it) }

fun ArtProfile.save(file: File, version: ArtProfileSerializer) {
    file.outputStream().use {
        save(it, version)
    }
}

internal fun InputStream.readProfileVersion(): ArtProfileSerializer? {
    val fileMagic = read(ArtProfileSerializer.size)
    val version = read(ArtProfileSerializer.size)
    if (ArtProfileSerializer.values().none { it.magicBytes.contentEquals(fileMagic) }) {
        error("Invalid magic")
    }
    return ArtProfileSerializer.values().firstOrNull {
        it.magicBytes.contentEquals(fileMagic) &&
        it.versionBytes.contentEquals(version)
    }
}