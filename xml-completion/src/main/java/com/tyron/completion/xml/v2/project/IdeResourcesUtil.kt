@file:JvmName("IdeResourcesUtil")
package com.tyron.completion.xml.v2.project

import com.android.resources.ResourceFolderType
import com.android.resources.ResourceType
import org.eclipse.lemminx.dom.DOMNode
import java.io.File

fun getFolderType(file: File): ResourceFolderType? = file.parentFile?.let { ResourceFolderType.getFolderType(it.name) }

fun getResourceTypeForResourceTag(tag: DOMNode): ResourceType? {
    return ResourceType.fromXmlTag(tag, { obj: DOMNode -> obj.nodeName }, { obj: DOMNode, qname: String? -> obj.getAttribute(qname) })
}
