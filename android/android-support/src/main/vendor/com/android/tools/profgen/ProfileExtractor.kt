/*
 * Copyright (C) 2022 The Android Open Source Project
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

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

fun extractProfileAsDm(
  apkFile: File,
  profileSerializer: ArtProfileSerializer,
  metadataSerializer: ArtProfileSerializer,
  outputStream: OutputStream,
) {
  ZipFile(apkFile).use { zipFile ->
    val profEntry = zipFile.entries().asSequence().firstOrNull { it.name == "assets/dexopt/baseline.prof" }
    val profmEntry = zipFile.entries().asSequence().firstOrNull { it.name == "assets/dexopt/baseline.profm" }

    // Theoretically we could support if the output version matches current version,
    // but this would complicate user experience, so we don't bother for now.
    check(profEntry != null && profmEntry != null) {
      "Apk must contain profile at assets/dexopt/baseline.prof " + "and assets/dexopt/baseline.profm"
    }
    val profInputStream = zipFile.getInputStream(profEntry)!!
    val profmInputStream = zipFile.getInputStream(profmEntry)!!
    val prof = ArtProfile(profInputStream)
    check(prof != null) { "Unable to read profile from apk ${apkFile.absolutePath}" }
    val profmSerializer = profmInputStream.readProfileVersion()
    check(profmSerializer != null) { "Unable to read profile metadata from apk ${apkFile.absolutePath}" }
    val metadata = ArtProfile(profmSerializer.read(profmInputStream!!))
    val merged = prof.addMetadata(metadata, profmSerializer.metadataVersion!!)
    outputStream.writeDm(merged, metadata, profileSerializer, metadataSerializer)
  }
}

private fun ZipOutputStream.put(name: String, block: (OutputStream) -> Unit) {
  putNextEntry(ZipEntry(name))
  val stream = ByteArrayOutputStream()
  block(stream)
  write(stream.toByteArray())
  closeEntry()
}

internal fun OutputStream.writeDm(
  prof: ArtProfile,
  profm: ArtProfile?,
  profileVersion: ArtProfileSerializer,
  metadataVersion: ArtProfileSerializer,
) {
  ZipOutputStream(this).use {
    it.setLevel(Deflater.NO_COMPRESSION)
    it.put("primary.prof") { zipFileOutStream -> prof.save(zipFileOutStream, profileVersion) }
    profm?.apply { it.put("primary.profm") { zipFileOutStream -> save(zipFileOutStream, metadataVersion) } }
  }
}
