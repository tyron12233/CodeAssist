/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.profgen

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.min

class Apk(val dexes: List<DexFile>, val name: String = "")

fun Apk(file: File, name: String = ""): Apk {
  return ZipFile(file).use { zipFile ->
    val dexes = mutableListOf<DexFile>()
    val entries = zipFile.entries()
    while (entries.hasMoreElements()) {
      val zipEntry = entries.nextElement()
      // Check if the file name is one of the DEX files, but for AAB it can be within a subdirectory
      val fileName = zipEntry.name

      // Fast path to skip any non-dex files
      if (!fileName.endsWith(".dex")) {
        continue
      }

      // Match the whole pattern and remember the file name part
      val fileNameMatches = dexClassesPattern.matchEntire(fileName)
      if (fileNameMatches == null) {
        continue
      }
      // Take just the filename without the path, which is later compared with the one from the
      // profile itself
      val (dexFileName) = fileNameMatches.destructured
      zipFile.getInputStream(zipEntry)!!.use { inputStream ->
        val dex = parseDexFile(inputStream.readBytes(), dexFileName)
        dexes.add(dex)
      }
    }
    Apk(dexes, name)
  }
}

fun Apk(bytes: ByteArray, name: String = ""): Apk {
  val file = File.createTempFile("profgen", ".apk")
  try {
    file.writeBytes(bytes)
    return Apk(file, name)
  } finally {
    file.delete()
  }
}

/**
 * Pattern for finding DEX files in a file name with any prefix. Matches
 * - classes(N).dex
 * - base/dex/classes(N).dex // for AAB dex files
 */
private val dexClassesPattern = Regex("^(?:base/dex/|)(classes[0-9]*\\.dex)$")

/**
 * Slimmed-down in-memory representation of a Dex file. This data structure contains the minimal amount of information that profgen needs in
 * order to generate a profile. This means that a lot of information is missing, such as the field pool, all code points, and various bits
 * of information of the class defs.
 */
class DexFile internal constructor(internal val header: DexHeader, val dexChecksum: Long, val name: String) {

  internal val stringPool = ArrayList<String>(header.stringIds.size)
  internal val typePool = ArrayList<String>(header.typeIds.size)
  internal val protoPool = ArrayList<DexPrototype>(header.prototypeIds.size)
  internal val methodPool = ArrayList<DexMethod>(header.methodIds.size)
  internal val definedMethods = HashSet<Int>()

  // we don't really care about any of the details of classes, just what index it corresponds to in
  // the
  // type pool, and we can use the type pool to determine its descriptor, so in this case we only
  // need an IntArray.
  internal val classDefPool = IntArray(header.classDefs.size)

  companion object : Comparator<DexFile> {

    override fun compare(o1: DexFile, o2: DexFile): Int {
      return o1.name.compareTo(o2.name)
    }
  }
}

fun DexFile(src: InputStream, name: String): DexFile = parseDexFile(src.readBytes(), name)

internal class DexHeader(
  val stringIds: Span,
  val typeIds: Span,
  val prototypeIds: Span,
  val methodIds: Span,
  val classDefs: Span,
  val data: Span,
) {
  internal companion object {
    val Empty =
      DexHeader(
        stringIds = Span.Empty,
        typeIds = Span.Empty,
        prototypeIds = Span.Empty,
        methodIds = Span.Empty,
        classDefs = Span.Empty,
        data = Span.Empty,
      )
  }
}

internal data class DexMethod(val parent: String, val name: String, val prototype: DexPrototype) : Comparator<DexMethod> by Comparator {
  companion object {
    val Comparator: java.util.Comparator<DexMethod> =
      compareBy<DexMethod>({ it.parent }, { it.name }).thenBy(DexPrototype.Comparator) { it.prototype }
  }

  val returnType: String
    get() = prototype.returnType

  val parameters: String = prototype.parameters.joinToString("")

  fun print(os: Appendable): Appendable =
    with(os) {
      append(parent)
      append("->")
      append(name)
      append('(')
      append(parameters)
      append(')')
      append(returnType)
    }

  override fun toString(): String = buildString { print(this) }
}

/**
 * Dex files store the "prototype" or signature of a function separate from the function itself to save on space. As a result, we allocate
 * this data structure separately from the [DexMethod].
 */
internal data class DexPrototype(val returnType: String, val parameters: List<String>) : Comparator<DexPrototype> by Comparator {
  companion object {

    val Comparator = compareBy<DexPrototype, List<String>>(listComparator()) { it.parameters }.thenBy { it.returnType }
  }
}

internal fun <T : Comparable<T>> listComparator(): Comparator<List<T>> {
  return object : Comparator<List<T>> {
    override fun compare(list: List<T>, other: List<T>): Int {
      val minSize = min(list.size, other.size)
      for (i in 0 until minSize) {
        val comparison = compareValues(list[i], other[i])
        if (comparison != 0) {
          return comparison
        }
      }
      return list.size - other.size
    }
  }
}

/** A simple tuple of Integers indicating a range of data in a binary file. */
internal class Span(
  /** The size of the span, in bytes. */
  val size: Int,
  /** The offset/location of the span, in bytes. */
  val offset: Int,
) {

  fun includes(value: Long): Boolean {
    return value >= offset && value < offset + size
  }

  internal companion object {

    val Empty = Span(0, 0)
  }
}

class DexFileData(val typeIndexes: Set<Int>, val classIndexes: Set<Int>, val methods: Map<Int, MethodData>)

internal operator fun DexFileData.plus(other: DexFileData?): DexFileData {
  if (other == null) return this
  return DexFileData(typeIndexes + other.typeIndexes, classIndexes + other.classIndexes, methods + other.methods)
}

internal class MutableDexFileData(
  val classIdSetSize: Int,
  val typeIdSetSize: Int,
  val numMethodIds: Int,
  val dexFile: DexFile,
  var hotMethodRegionSize: Int,
  val classIdSet: MutableSet<Int>,
  val typeIdSet: MutableSet<Int>,
  val methods: MutableMap<Int, MethodData>,
) {

  fun asDexFileData() = DexFileData(typeIdSet, classIdSet, methods)
}

data class MethodData(var flags: Int) {

  inline val isHot: Boolean
    get() = MethodFlags.isHot(flags)

  @Suppress("NOTHING_TO_INLINE") inline fun isFlagSet(flag: Int): Boolean = MethodFlags.isFlagSet(flags, flag)

  fun print(os: Appendable) = MethodData.printFlags(flags, os)

  companion object {

    internal fun printFlags(flags: Int, os: Appendable) =
      with(os) {
        if (MethodFlags.isHot(flags)) os.append(HOT)
        if (MethodFlags.isStartup(flags)) os.append(STARTUP)
        if (MethodFlags.isPostStartup(flags)) os.append(POST_STARTUP)
      }
  }
}

// TODO(lmr): refactor to not use iteration and first/last flag strategy for this
object MethodFlags {
  // Implementation note: DO NOT CHANGE THESE VALUES without adjusting the parsing.
  // To simplify the implementation we use the MethodHotness flag values as indexes into the
  // internal bitmap representation. As such, they should never change unless the profile version
  // is updated and the implementation changed accordingly.
  /** Marker flag used to simplify iterations. */
  const val FIRST_FLAG = 1 shl 0

  /** The method is profile-hot (this is implementation specific, e.g. equivalent to JIT-warm) */
  const val HOT = 1 shl 0

  /** Executed during the app startup as determined by the runtime. */
  const val STARTUP = 1 shl 1

  /** Executed after app startup as determined by the runtime. */
  const val POST_STARTUP = 1 shl 2

  /** Marker flag used to simplify iterations. */
  const val LAST_FLAG_REGULAR = 1 shl 2

  /** Combined value of flags */
  const val ALL = HOT or STARTUP or POST_STARTUP

  fun isHot(flags: Int): Boolean = isFlagSet(flags, MethodFlags.HOT)

  fun isStartup(flags: Int): Boolean = isFlagSet(flags, MethodFlags.STARTUP)

  fun isPostStartup(flags: Int): Boolean = isFlagSet(flags, MethodFlags.POST_STARTUP)

  fun isFlagSet(flags: Int, flag: Int): Boolean {
    return (flags and flag) == flag
  }
}

internal fun splitParameters(parameters: String): List<String> {
  val result = mutableListOf<String>()
  val currentParam = StringBuilder(parameters.length)
  var inClassName = false
  for (c in parameters) {
    currentParam.append(c)
    inClassName = if (inClassName) c != ';' else c == 'L'
    // add a parameter if we're no longer in class and not in array start
    if (!inClassName && c != '[') {
      result.add(currentParam.toString())
      currentParam.clear()
    }
  }
  return result
}

/** A list of known [ReadableFileSection] types. */
internal enum class FileSectionType(val value: Long) {

  /** Represents a dex file section. This is a required file section type. */
  DEX_FILES(0L),

  /**
   * Optional file sections. The only ones we care about are [CLASSES] and [METHODS]. Listing [EXTRA_DESCRIPTORS] & [AGGREGATION_COUNT] for
   * completeness.
   */
  EXTRA_DESCRIPTORS(1L),
  CLASSES(2L),
  METHODS(3L),
  AGGREGATION_COUNT(4L);

  companion object {

    fun parse(value: Long): FileSectionType {
      val type = values().firstOrNull { it.value == value }

      return type ?: throw IllegalArgumentException("Unsupported FileSection type $value")
    }
  }
}

/** A Readable Profile Section for ART profiles on Android 12. */
internal data class ReadableFileSection(val type: FileSectionType, val span: Span, val inflateSize: Int) {

  fun isCompressed(): Boolean {
    return inflateSize != 0
  }
}

/** A Writable Profile Section for ART profiles on Android 12. */
internal class WritableFileSection(
  val type: FileSectionType,
  val expectedInflateSize: Int,
  val contents: ByteArray,
  val needsCompression: Boolean,
)
