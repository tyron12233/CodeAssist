package com.android.aaptcompiler

import com.android.aapt.Resources
import com.google.common.base.Preconditions

class XmlResourceBuilder(val file: ResourceFile, val skipWhitespaceText: Boolean = true) {
  /** The stack of the current element and all ancestors. */
  private val elementStack = mutableListOf<Resources.XmlElement.Builder>()
  private val elementSourceStack = mutableListOf<Resources.SourcePosition>()

  /** Storage for the completed XmlResource when it is finished building. */
  private var result: XmlResource? = null

  /**
   * The text in the current text state, since consecutive text elements should be joined to be
   * in line with aapt2.
   */
  private var currentTextState = ""
  private lateinit var textSource: Resources.SourcePosition

  /**
   * Tracks the active namespaces of all active elements.
   */
  val namespaceContext = NamespaceContext()

  fun build(): XmlResource {
    Preconditions.checkState(isFinished())
    return result!!
  }

  fun isFinished(): Boolean = result != null

  fun startElement(
    name: String,
    namespace: String,
    lineNumber: Int = 0,
    columnNumber: Int = 0): XmlResourceBuilder {

    Preconditions.checkState(!isFinished())
    finishTextState()
    val newElement = Resources.XmlElement.newBuilder()
      .setName(name)
      .setNamespaceUri(namespace)
    elementStack.add(newElement)
    elementSourceStack.add(
      Resources.SourcePosition.newBuilder()
        .setLineNumber(lineNumber)
        .setColumnNumber(columnNumber)
        .build()
    )
    return this
  }

  fun endElement(): XmlResourceBuilder {
    Preconditions.checkState(elementStack.isNotEmpty())
    finishTextState()

    // Build the xml Node.
    val node = Resources.XmlNode.newBuilder()
      .setElement(elementStack.last())
      .setSource(elementSourceStack.last())
    elementStack.removeAt(elementStack.lastIndex)
    elementSourceStack.removeAt(elementSourceStack.lastIndex)

    // Clean up namespaces
    for (namespace in node.element.namespaceDeclarationList) {
      NamespaceContextAccessor.pop(namespaceContext, namespace.prefix)
    }

    if (elementStack.isEmpty()) {
      // We've finished the top level element so store it to be fetched later.
      result = XmlResource(file, node.build())
      return this
    }
    // If not, then this is a child element and should be added to the next stack element.
    elementStack.last().addChild(node)
    return this
  }

  fun addNamespaceDeclaration(
    uri: String, prefix: String, lineNumber: Int = 0, columnNumber: Int = 0): XmlResourceBuilder {

    Preconditions.checkState(elementStack.isNotEmpty())
    val currentElement = elementStack.last()
    currentElement.addNamespaceDeclaration(
      Resources.XmlNamespace.newBuilder()
        .setUri(uri)
        .setPrefix(prefix)
        .setSource(
          Resources.SourcePosition.newBuilder()
            .setLineNumber(lineNumber)
            .setColumnNumber(columnNumber)))
    // Manage the namespace context.
    NamespaceContextAccessor.push(namespaceContext, prefix, uri)
    return this
  }

  fun addAttribute(
    name: String,
    namespace: String,
    value: String,
    lineNumber: Int = 0,
    columnNumber: Int = 0
  ): XmlResourceBuilder {
    Preconditions.checkState(elementStack.isNotEmpty())
    val currentElement = elementStack.last()
    currentElement.addAttribute(
      Resources.XmlAttribute.newBuilder()
        .setName(name)
        .setNamespaceUri(namespace)
        .setValue(value)
        .setSource(
          Resources.SourcePosition.newBuilder()
            .setLineNumber(lineNumber)
            .setColumnNumber(columnNumber)))
    return this
  }

  fun findAttribute(name: String, namespace: String): String? {
    val attrList = elementStack.last().attributeList
    return attrList.find {it.name == name && it.namespaceUri == namespace}?.value
  }

  /**
   * Adds a text element to the current XML element.
   *
   * The text is not immediately added, but is held by the builder so that consecutive text elements
   * can be accumulated as a single element for the protocol buffer. Whenever the current XML tag is
   * finished or a non-text element is encountered such as a comment or new tag, the current text
   * state is flushed, and the text element is added.
   */
  fun addText(text: String, lineNumber: Int = 0, columnNumber: Int = 0): XmlResourceBuilder {
    if ((skipWhitespaceText && text.isBlank()) || text.isEmpty()) return this

    if (currentTextState.isEmpty()) {
      textSource = Resources.SourcePosition.newBuilder()
        .setLineNumber(lineNumber)
        .setColumnNumber(columnNumber)
        .build()
      currentTextState = text
      return this
    }
    currentTextState += text
    return this
  }

  /**
   * Tells the proto builder that a comment was encountered in the XML.
   *
   * Comments are not written to the protocol buffer, but they do indicate that any previously
   * recorded text is finished. I.e.
   *
   *     <element>
   *       Some text.
   *       <!--comment-->
   *       Some other text.
   *     </element>
   *
   * should be treated differently than:
   *
   *    <element>
   *       Some text
   *       that is continued here.
   *     </element>
   *
   */
  fun addComment(): XmlResourceBuilder {
    Preconditions.checkState(elementStack.isNotEmpty())
    finishTextState()
    return this
  }

  private fun finishTextState() {
    if (currentTextState.isEmpty()) return
    val currentElement = elementStack.last()
    currentElement.addChild(
      Resources.XmlNode.newBuilder()
        .setText(currentTextState)
        .setSource(textSource))
    currentTextState = ""
  }
}