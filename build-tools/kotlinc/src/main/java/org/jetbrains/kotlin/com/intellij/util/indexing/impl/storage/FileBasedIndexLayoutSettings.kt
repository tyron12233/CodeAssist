package org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage

import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.FileBasedIndexLayoutProviderBean
import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil
import org.jetbrains.kotlin.com.intellij.util.io.DataOutputStream
import org.jetbrains.kotlin.com.intellij.util.io.EnumeratorStringDescriptor
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

internal object FileBasedIndexLayoutSettings {
  private val log = Logger.getInstance(FileBasedIndexLayoutSettings::class.java)

  @Synchronized
  fun setUsedLayout(bean: FileBasedIndexLayoutProviderBean?) {
    try {
      Files.createDirectories(indexLayoutSettingFile().parent)
      if (bean == null) {
        FileUtil.delete(indexLayoutSettingFile())
        return
      }
      DataOutputStream(indexLayoutSettingFile().outputStream().buffered()).use {
        EnumeratorStringDescriptor.INSTANCE.save(it, bean.id)
        DataInputOutputUtil.writeINT(it, bean.version)
      }
    }
    catch (e: IOException) {
      log.error(e)
    }
  }

  @Synchronized
  fun loadUsedLayout(): Boolean {
    if (!Files.exists(indexLayoutSettingFile())) {
      currentLayout = Ref.create()
      return false
    }
    else {
      val id: String
      val version: Int
      DataInputStream(indexLayoutSettingFile().inputStream().buffered()).use {
        id = EnumeratorStringDescriptor.INSTANCE.read(it)
        version = DataInputOutputUtil.readINT(it)
      }

//      // scan for exact layout id & version match
//      for (bean in DefaultIndexStorageLayout.availableLayouts) {
//        if (bean.id == id && bean.version == version) {
//          currentLayout = Ref.create(bean)
//          return false
//        }
//      }
//
//      // scan only matched id
//      for (bean in DefaultIndexStorageLayout.availableLayouts) {
//        if (bean.id == id) {
//          setUsedLayout(bean)
//          currentLayout = Ref.create(bean)
//          return true
//        }
//      }

      // fallback to default
      setUsedLayout(null)
      return true
    }
  }

  @Synchronized
  fun saveCurrentLayout() {
    setUsedLayout(getUsedLayout())
  }

  @Synchronized
  fun getUsedLayout(): FileBasedIndexLayoutProviderBean? {
    val layout = currentLayout ?: throw IllegalStateException("File-based index layout settings not loaded yet")
    return layout.get()
  }

  @Synchronized
  fun resetUsedLayout() {
    currentLayout = null
  }

  private var currentLayout : Ref<FileBasedIndexLayoutProviderBean?>? = null
  private fun indexLayoutSettingFile(): Path = PathManager.getIndexRoot().toPath().resolve("indices.layout")

}