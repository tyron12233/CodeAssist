@file:OptIn(ExperimentalUnsignedTypes::class)
package com.android.tools.profgen

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

private const val HEADER_SIZE = 0x70
private const val MAGIC_PREFIX = "dex\n" // 0x64 0x65 0x78 0x0a
private const val MAGIC_SUFFIX = "\u0000"
private const val ENDIAN_TAG_OFFSET = 40
private val MAGIC_SUPPORTED_VERSIONS = listOf(
    "035",  // Android < N. Very pervasive and standard.
    "037",  // Android N. Adds support for default methods.
    "038",  // Android O. Adds support for new bytecodes and data for method handles.
    "039",  // Android P+. Adds support for const-method-handle and const-method-type bytecodes.
)

/**
 * Parses a Dex (Dalvik Executable) from a ByteArray. More information on the specification for this encoding can be
 * found here: https://source.android.com/devices/tech/dalvik/dex-format
 */
internal fun parseDexFile(
    bytes: ByteArray,
    name: String,
): DexFile {
    val crc32 = CRC32().apply { update(bytes) }
    val crc32Checksum = crc32.value
    val byteBuffer = ByteBuffer.wrap(bytes)
    return parseDexFile(byteBuffer, crc32Checksum, name)
}

internal fun parseDexFile(
    buffer: ByteBuffer,
    checksum: Long,
    name: String,
): DexFile {
    val dexHeader = parseHeader(buffer)
    val dexFile = DexFile(dexHeader, checksum, name)
    parseStringPool(buffer, dexFile)
    parseTypePool(buffer, dexFile)
    parsePrototypePool(buffer, dexFile)
    parseMethodPool(buffer, dexFile)
    parseClassDefinitionPool(buffer, dexFile)
    return dexFile
}

private fun parseHeader(src: ByteBuffer): DexHeader {
    // Determine the byte order of the file.
    val endian = Endian.forNumber(
        src
            .order(ByteOrder.LITTLE_ENDIAN)
            .getInt(ENDIAN_TAG_OFFSET)
    )
    src.order(endian.order)

    val rawMagic = ByteArray(8)
    src.get(rawMagic)
    val magic = String(rawMagic, StandardCharsets.UTF_8)
    checkMagic(magic)
    /* val checksum = */src.int
    val signature = ByteArray(20)
    src.get(signature)
    /* val fileSize = */src.int
    val headerSize = src.int
    if (headerSize != HEADER_SIZE) {
        invalidDexFile("Header is wrong size. Got $headerSize, Want $HEADER_SIZE")
    }

    // Skip the endian tag as we read it earlier.
    src.int
    /* val link = */parseSpan(src)
    /* val mapOff = */src.int
    val stringIds = parseSpan(src)
    val typeIds = parseSpan(src)
    val protoIds = parseSpan(src)
    /* val fieldIds = */parseSpan(src)
    val methodIds = parseSpan(src)
    val classDefs = parseSpan(src)
    val data = parseSpan(src)
    return DexHeader(
        stringIds = stringIds,
        typeIds = typeIds,
        prototypeIds = protoIds,
        methodIds = methodIds,
        classDefs = classDefs,
        data = data,
    )
}

private fun parseStringPool(buffer: ByteBuffer, dexFile: DexFile) {
    // Ensure the state is as expected for parsing the section.
    buffer.position(dexFile.header.stringIds.offset)
    val data = buffer.asReadOnlyBuffer().order(buffer.order())
    for (i in 0 until dexFile.header.stringIds.size) {
        val offset = buffer.int
        data.position(offset)
        val encodedSize = data.leb128
        val result = data.mutf8(encodedSize)
        dexFile.stringPool.add(result)
    }
}

private fun parseTypePool(buffer: ByteBuffer, dexFile: DexFile) {
    buffer.position(dexFile.header.typeIds.offset)
    for (i in 0 until dexFile.header.typeIds.size) {
        val offset = buffer.int
        val type = dexFile.stringPool[offset]
        dexFile.typePool.add(type)
    }
}

private fun parsePrototypePool(buffer: ByteBuffer, dexFile: DexFile) {
    buffer.position(dexFile.header.prototypeIds.offset)
    val data = buffer.asReadOnlyBuffer().order(buffer.order())
    for (i in 0 until dexFile.header.prototypeIds.size) {
        /* val shortyIdx = */buffer.int
        val returnTypeIdx = buffer.int
        val parametersOffset = buffer.int
        dexFile.protoPool.add(
            DexPrototype(
                dexFile.typePool[returnTypeIdx],
                getTypeList(dexFile, data, parametersOffset.toLong())
            )
        )
    }
}

private fun parseMethodPool(buffer: ByteBuffer, dexFile: DexFile) {
    buffer.position(dexFile.header.methodIds.offset)
    for (i in 0 until dexFile.header.methodIds.size) {
        val classIdx = buffer.ushort
        val protoIdx = buffer.ushort
        val nameIdx = buffer.int
        val clsType = dexFile.typePool[classIdx]
        val proto = dexFile.protoPool[protoIdx]
        val name = dexFile.stringPool[nameIdx]
        dexFile.methodPool.add(DexMethod(clsType, name, proto))
    }
}

private fun parseClassDefinitionPool(buffer: ByteBuffer, dexFile: DexFile) {
    buffer.position(dexFile.header.classDefs.offset)
    for (i in 0 until dexFile.header.classDefs.size) {
        val classIdx = buffer.int
        /* val accessFlags = */buffer.int
        /* val superClassIdx = */buffer.int
        /* val interfacesOffs = */buffer.int
        /* val sourceFileIdx = */buffer.int
        /* val annotationsOffset = */buffer.int
        /* val classDataOffset = */buffer.int
        /* val staticValuesOffset = */buffer.int
        dexFile.classDefPool[i] = classIdx
    }
}

private fun getTypeList(dexFile: DexFile, buffer: ByteBuffer, offset: Long): List<String> {
    if (offset == 0L) {
        return emptyList()
    }
    val listOffset = offset.toIntSaturated()
    if (!dexFile.header.data.includes(listOffset.toLong())) {
        invalidDexFile("offset invalid: offset=$offset, data=${dexFile.header.data}")
    }
    // Move the data buffer to the start of the string.
    buffer.position(listOffset)

    // Read list size
    val size = buffer.int
    val result = mutableListOf<String>()
    for (i in 0 until size) {
        val typeId = buffer.ushort
        result.add(dexFile.typePool[typeId])
    }
    return result
}

private fun checkMagic(magic: String?) {
    if (magic == null || !magic.startsWith(MAGIC_PREFIX) || !magic.endsWith(MAGIC_SUFFIX)) {
        invalidDexFile("Unexpected magic number: $magic")
    }
    val versionTag = magic.substring(
        MAGIC_PREFIX.length,
        magic.length - MAGIC_SUFFIX.length
    )
    if (!MAGIC_SUPPORTED_VERSIONS.contains(versionTag)) {
        invalidDexFile("Unsupported DEX version tag: $versionTag")
    }
}

private fun parseSpan(src: ByteBuffer): Span {
    val size = src.int
    val offset = src.int
    return Span(size, offset)
}

internal fun invalidDexFile(message: String): Nothing = error(message)
