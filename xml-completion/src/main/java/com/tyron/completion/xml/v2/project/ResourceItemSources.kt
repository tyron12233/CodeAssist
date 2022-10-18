package com.tyron.completion.xml.v2.project

import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceFolderType
import com.android.resources.ResourceFolderType.getFolderType
import com.android.utils.Base128OutputStream
import com.google.common.collect.ArrayListMultimap
import com.tyron.completion.xml.v2.base.BasicResourceItem
import com.tyron.completion.xml.v2.base.RepositoryConfiguration
import com.tyron.completion.xml.v2.base.ResourceSourceFile
import it.unimi.dsi.fastutil.objects.Object2IntMap
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil.getRelativePath
import java.io.File
import java.io.IOException


/**
 * Represents a resource file from which [ResourceItem]s are created by [ResourceFolderRepository].
 *
 * This is a common interface implemented by [DomResourceFile] and [VfsResourceFile].
 */
internal interface ResourceItemSource<T : ResourceItem> : Iterable<T> {
    val virtualFile: File?
    val configuration: RepositoryConfiguration
    val folderType: ResourceFolderType?

    val repository: ResourceFolderRepository
        get() = configuration.repository as ResourceFolderRepository

    val folderConfiguration: FolderConfiguration
        get() = configuration.folderConfiguration

    fun addItem(item: T)
}

/**
 * The [ResourceItemSource] of [BasicResourceItem]s.
 */
internal class VfsResourceFile(
    override val virtualFile: File?, override val configuration: RepositoryConfiguration
) : ResourceSourceFile, ResourceItemSource<BasicResourceItem> {

    private val items = ArrayList<BasicResourceItem>()

    override val folderType get() = getFolderType(virtualFile!!.name ?: "")

    override val repository: ResourceFolderRepository
        get() = configuration.repository as ResourceFolderRepository

    override fun iterator(): Iterator<BasicResourceItem> = items.iterator()

    override fun addItem(item: BasicResourceItem) {
        items.add(item)
    }

    override val relativePath: String?
        get() = virtualFile?.let { getRelativePath(it, repository.resourceDir) }

    fun isValid(): Boolean = virtualFile != null

    /**
     * Serializes the object to the given stream without the contained resource items.
     */
    @Throws(IOException::class)
    override fun serialize(stream: Base128OutputStream, configIndexes: Object2IntMap<String>) {
        stream.writeString(relativePath)
        stream.writeInt(configIndexes.getInt(configuration.folderConfiguration.qualifierString))
        stream.write(FileTimeStampLengthHasher.hash(virtualFile))
    }
}

/** The [ResourceItemSource] of [_root_ide_package_.com.tyron.completion.xml.v2.project.DomResourceItem]s. */
internal class DomResourceFile(
    private var _psiFile: File,
    items: Iterable<DomResourceItem>,
    private var _resourceFolderType: ResourceFolderType?,
    override var configuration: RepositoryConfiguration
) : ResourceItemSource<DomResourceItem> {

    private val _items = ArrayListMultimap.create<String, DomResourceItem>()

    init {
        items.forEach(this::addItem)
    }

    override val folderType get() = _resourceFolderType
    override val virtualFile: File? get() = _psiFile
    override fun iterator(): Iterator<DomResourceItem> = _items.values().iterator()
    fun isSourceOf(item: ResourceItem): Boolean = (item as? DomResourceItem)?.sourceFile == this

    override fun addItem(item: DomResourceItem) {
        // Setting the source first is important, since an item's key gets the folder configuration from the source (i.e. this).
        item.sourceFile = this
        _items.put(item.key, item)
    }

    fun removeItem(item: DomResourceItem) {
        _items.remove(item.key, item)
        item.sourceFile = null
    }

    val name = _psiFile.name
    val psiFile get() = _psiFile

    fun setPsiFile(psiFile: File, configuration: RepositoryConfiguration) {
        this._psiFile = psiFile
        this._resourceFolderType = getFolderType(psiFile)
        this.configuration = configuration
    }
}