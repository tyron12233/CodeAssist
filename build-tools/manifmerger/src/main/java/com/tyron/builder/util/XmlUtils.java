package com.tyron.builder.util;

import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.tyron.builder.compiler.manifest.SdkConstants;
import com.tyron.builder.compiler.manifest.blame.SourceFile;
import com.tyron.builder.compiler.manifest.blame.SourceFilePosition;
import com.tyron.builder.compiler.manifest.blame.SourcePosition;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openjdk.javax.xml.parsers.DocumentBuilder;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;
import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.openjdk.javax.xml.parsers.SAXParser;
import org.openjdk.javax.xml.parsers.SAXParserFactory;
import org.openjdk.javax.xml.stream.XMLInputFactory;
import org.openjdk.javax.xml.stream.XMLStreamException;
import org.openjdk.javax.xml.stream.XMLStreamReader;
import org.w3c.dom.Attr;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XML Utilities.
 * <p>
 * For Kotlin usage, many of these are exposed as more convenient extension
 * methods in DomExtensions
 */
public class XmlUtils {
    public static final String XML_COMMENT_BEGIN = "<!--"; //$NON-NLS-1$
    public static final String XML_COMMENT_END = "-->";    //$NON-NLS-1$
    public static final String XML_PROLOG =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";  //$NON-NLS-1$

    public static final String SAX_PARSER_FACTORY =
            "org.openjdk.com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl";

    /**
     * Separator for xml namespace and localname
     */
    public static final char NS_SEPARATOR = ':';                  //$NON-NLS-1$

    private static final String SOURCE_FILE_USER_DATA_KEY = "sourcefile";

    // XML parser features
    private static final String NAMESPACE_PREFIX_FEATURE =
            "http://xml.org/sax/features/namespace-prefixes";
    private static final String PROVIDE_XMLNS_URIS =
            "http://xml.org/sax/features/xmlns-uris";
    private static final String LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String DISALLOW_DOCTYPE_DECL =
            "http://apache.org/xml/features/disallow-doctype-decl";

    /** The first byte of a proto XML file is always 0x0A. */
    private static final byte PROTO_XML_LEAD_BYTE = 0x0A;

    private static final int MAXIMUM_XML_DEPTH = 500;

    /**
     * Returns the namespace prefix matching the requested namespace URI.
     * If no such declaration is found, returns the default "android" prefix for
     * the Android URI, and "app" for other URI's. By default the app namespace
     * will be created. If this is not desirable, call
     * {@link #lookupNamespacePrefix(Node, String, boolean)} instead.
     *
     * @param node The current node. Must not be null.
     * @param nsUri The namespace URI of which the prefix is to be found,
     *              e.g. {@link SdkConstants#ANDROID_URI}
     * @return The first prefix declared or the default "android" prefix
     *              (or "app" for non-Android URIs)
     */
    @NotNull
    public static String lookupNamespacePrefix(@NotNull Node node, @NotNull String nsUri) {
        String defaultPrefix = SdkConstants.ANDROID_URI.equals(nsUri) ? SdkConstants.ANDROID_NS_NAME : SdkConstants.APP_PREFIX;
        return lookupNamespacePrefix(node, nsUri, defaultPrefix, true /*create*/);
    }

    /**
     * Returns the namespace prefix matching the requested namespace URI. If no
     * such declaration is found, returns the default "android" prefix for the
     * Android URI, and "app" for other URI's.
     *
     * @param node The current node. Must not be null.
     * @param nsUri The namespace URI of which the prefix is to be found, e.g.
     *            {@link SdkConstants#ANDROID_URI}
     * @param create whether the namespace declaration should be created, if
     *            necessary
     * @return The first prefix declared or the default "android" prefix (or
     *         "app" for non-Android URIs)
     */
    @NotNull
    public static String lookupNamespacePrefix(@NotNull Node node, @NotNull String nsUri,
                                               boolean create) {
        String defaultPrefix = SdkConstants.ANDROID_URI.equals(nsUri) ? SdkConstants.ANDROID_NS_NAME : SdkConstants.APP_PREFIX;
        return lookupNamespacePrefix(node, nsUri, defaultPrefix, create);
    }

    /**
     * Returns the namespace prefix matching the requested namespace URI. If no
     * such declaration is found, returns the default "android" prefix.
     *
     * @param node The current node. Must not be null.
     * @param nsUri The namespace URI of which the prefix is to be found, e.g.
     *            {@link SdkConstants#ANDROID_URI}
     * @param defaultPrefix The default prefix (root) to use if the namespace is
     *            not found. If null, do not create a new namespace if this URI
     *            is not defined for the document.
     * @param create whether the namespace declaration should be created, if
     *            necessary
     * @return The first prefix declared or the provided prefix (possibly with a
     *            number appended to avoid conflicts with existing prefixes.
     */
    public static String lookupNamespacePrefix(
            @Nullable Node node, @Nullable String nsUri, @Nullable String defaultPrefix,
            boolean create) {
        // Note: Node.lookupPrefix is not implemented in wst/xml/core NodeImpl.java
        // The following code emulates this simple call:
        //   String prefix = node.lookupPrefix(NS_RESOURCES);

        // if the requested URI is null, it denotes an attribute with no namespace.
        if (nsUri == null) {
            return null;
        }

        // per XML specification, the "xmlns" URI is reserved
        if (SdkConstants.XMLNS_URI.equals(nsUri)) {
            return SdkConstants.XMLNS;
        }

        HashSet<String> visited = new HashSet<>();
        Document doc = node == null ? null : node.getOwnerDocument();

        // Ask the document about it. This method may not be implemented by the Document.
        String nsPrefix;
        try {
            nsPrefix = doc != null ? doc.lookupPrefix(nsUri) : null;
            if (nsPrefix != null) {
                return nsPrefix;
            }
        } catch (Throwable t) {
            // ignore
        }

        // If that failed, try to look it up manually.
        // This also gathers prefixed in use in the case we want to generate a new one below.
        for (; node != null && node.getNodeType() == Node.ELEMENT_NODE;
             node = node.getParentNode()) {
            NamedNodeMap attrs = node.getAttributes();
            for (int n = attrs.getLength() - 1; n >= 0; --n) {
                Node attr = attrs.item(n);
                if (SdkConstants.XMLNS.equals(attr.getPrefix())) {
                    String uri = attr.getNodeValue();
                    nsPrefix = attr.getLocalName();
                    // Is this the URI we are looking for? If yes, we found its prefix.
                    if (nsUri.equals(uri)) {
                        return nsPrefix;
                    }
                    visited.add(nsPrefix);
                } else if (attr.getPrefix() == null
                        && attr.getNodeName().startsWith(SdkConstants.XMLNS_PREFIX)) {
                    // It seems to be possible for the attribute to not have the namespace prefix
                    // i.e. attr.getPrefix() returns null and getLocalName returns null, but the
                    // node name is xmlns:foo. This is a ugly workaround, but it works.
                    String uri = attr.getNodeValue();
                    nsPrefix = attr.getNodeName().substring(SdkConstants.XMLNS_PREFIX.length());
                    // Is this the URI we are looking for? If yes, we found its prefix.
                    if (nsUri.equals(uri)) {
                        return nsPrefix;
                    }
                    visited.add(nsPrefix);
                }
            }
        }

        // Failed the find a prefix. Generate a new sensible default prefix, unless
        // defaultPrefix was null in which case the caller does not want the document
        // modified.
        if (defaultPrefix == null) {
            return null;
        }

        //
        // We need to make sure the prefix is not one that was declared in the scope
        // visited above. Pick a unique prefix from the provided default prefix.
        String prefix = defaultPrefix;
        String base = prefix;
        for (int i = 1; visited.contains(prefix); i++) {
            prefix = base + Integer.toString(i);
        }
        // Also create and define this prefix/URI in the XML document as an attribute in the
        // first element of the document.
        if (doc != null) {
            node = doc.getFirstChild();
            while (node != null && node.getNodeType() != Node.ELEMENT_NODE) {
                node = node.getNextSibling();
            }
            if (node != null && create) {
                // This doesn't work:
                //Attr attr = doc.createAttributeNS(XMLNS_URI, prefix);
                //attr.setPrefix(XMLNS);
                //
                // Xerces throws
                //org.w3c.dom.DOMException: NAMESPACE_ERR: An attempt is made to create or
                // change an object in a way which is incorrect with regard to namespaces.
                //
                // Instead pass in the concatenated prefix. (This is covered by
                // the UiElementNodeTest#testCreateNameSpace() test.)
                Attr attr = doc.createAttributeNS(SdkConstants.XMLNS_URI, SdkConstants.XMLNS_PREFIX + prefix);
                attr.setValue(nsUri);
                node.getAttributes().setNamedItemNS(attr);
            }
        }

        return prefix;
    }

    /**
     * Converts the given attribute value to an XML-attribute-safe value, meaning that
     * single and double quotes are replaced with their corresponding XML entities.
     *
     * @param attrValue the value to be escaped
     * @return the escaped value
     */
    @NotNull
    public static String toXmlAttributeValue(@NotNull String attrValue) {
        for (int i = 0, n = attrValue.length(); i < n; i++) {
            char c = attrValue.charAt(i);
            if (c == '"' || c == '\'' || c == '<' || c == '>' || c == '&' || c == '\n') {
                StringBuilder sb = new StringBuilder(2 * attrValue.length());
                appendXmlAttributeValue(sb, attrValue);
                return sb.toString();
            }
        }

        return attrValue;
    }

    /**
     * Converts the given XML-attribute-safe value to a java string
     *
     * @param escapedAttrValue the escaped value
     * @return the unescaped value
     */
    @NotNull
    public static String fromXmlAttributeValue(@NotNull String escapedAttrValue) {
        // See https://www.w3.org/TR/2000/WD-xml-c14n-20000119.html#charescaping
        if (escapedAttrValue.indexOf('&') == -1) {
            return escapedAttrValue;
        }
        String workingString = escapedAttrValue.replace(SdkConstants.QUOT_ENTITY, "\"");
        workingString = workingString.replace(SdkConstants.LT_ENTITY, "<");
        workingString = workingString.replace(SdkConstants.APOS_ENTITY, "'");
        workingString = workingString.replace(SdkConstants.AMP_ENTITY, "&");
        workingString = workingString.replace(SdkConstants.GT_ENTITY, ">");
        workingString = workingString.replace(SdkConstants.NEWLINE_ENTITY, "\n");

        return workingString;
    }

    /**
     * Converts the given attribute value to an XML-text-safe value, meaning that
     * less than and ampersand characters are escaped.
     *
     * @param textValue the text value to be escaped
     * @return the escaped value
     */
    @NotNull
    public static String toXmlTextValue(@NotNull String textValue) {
        for (int i = 0, n = textValue.length(); i < n; i++) {
            char c = textValue.charAt(i);
            if (c == '<' || c == '&') {
                StringBuilder sb = new StringBuilder(2 * textValue.length());
                appendXmlTextValue(sb, textValue);
                return sb.toString();
            }
        }

        return textValue;
    }

    /**
     * Appends text to the given {@link StringBuilder} and escapes it as required for a
     * DOM attribute node.
     *
     * @param sb the string builder
     * @param attrValue the attribute value to be appended and escaped
     */
    public static void appendXmlAttributeValue(@NotNull StringBuilder sb,
                                               @NotNull String attrValue) {
        appendXmlAttributeValue(sb, attrValue, 0, attrValue.length());
    }

    /**
     * Appends text to the given {@link StringBuilder} and escapes it as required for a
     * DOM attribute node.
     *
     * @param sb the string builder
     * @param attrValue the attribute value to be appended and escaped
     * @param start the starting offset in the text string
     * @param end the ending offset in the text string
     */
    public static void appendXmlAttributeValue(
            @NotNull StringBuilder sb, @NotNull String attrValue, int start, int end) {
        // See https://www.w3.org/TR/2000/WD-xml-c14n-20000119.html#charescaping
        // &, ", ' and < are illegal in attributes; see http://www.w3.org/TR/REC-xml/#NT-AttValue
        // (' legal in a " string and " is legal in a ' string but here we'll stay on the safe
        // side)
        char prev = 0;
        for (int i = start; i < end; i++) {
            char c = attrValue.charAt(i);
            if (c == '"') {
                sb.append(SdkConstants.QUOT_ENTITY);
            } else if (c == '<') {
                sb.append(SdkConstants.LT_ENTITY);
            } else if (c == '\'') {
                sb.append(SdkConstants.APOS_ENTITY);
            } else if (c == '&') {
                sb.append(SdkConstants.AMP_ENTITY);
            } else if (c == '\n') {
                sb.append(SdkConstants.NEWLINE_ENTITY);
            } else if (c == '>' && prev == ']') {
                // '>' doesn't have to be escaped in attributes, but it can be, and it *must*
                // be if it's the end of the character sequence ]]>. (See b.android.com/231003)
                sb.append(SdkConstants.GT_ENTITY);
            } else {
                sb.append(c);
            }
            prev = c;
        }
    }

    /**
     * Appends text to the given {@link StringBuilder} and escapes it as required for a
     * DOM text node.
     *
     * @param sb the string builder
     * @param textValue the text value to be appended and escaped
     */
    public static void appendXmlTextValue(@NotNull StringBuilder sb, @NotNull String textValue) {
        appendXmlTextValue(sb, textValue, 0, textValue.length());
    }

    /**
     * Appends text to the given {@link StringBuilder} and escapes it as required for a DOM text
     * node.
     *
     * @param sb the string builder
     * @param textValue the text value to be appended and escaped
     * @param start the starting offset in the text string
     * @param end the ending offset in the text string
     */
    public static void appendXmlTextValue(
            @NotNull StringBuilder sb, @NotNull String textValue, int start, int end) {
        for (int i = start, n = Math.min(textValue.length(), end); i < n; i++) {
            char c = textValue.charAt(i);
            if (c == '<') {
                sb.append(SdkConstants.LT_ENTITY);
            } else if (c == '&') {
                sb.append(SdkConstants.AMP_ENTITY);
            } else {
                sb.append(c);
            }
        }
    }

    /**
     * Returns true if the given node has one or more element children
     *
     * @param node the node to test for element children
     * @return true if the node has one or more element children
     */
    public static boolean hasElementChildren(@NotNull Node node) {
        NodeList children = node.getChildNodes();
        for (int i = 0, n = children.getLength(); i < n; i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns a character reader for the given file, which must be a UTF encoded file.
     * <p>
     * The reader does not need to be closed by the caller (because the file is read in
     * full in one shot and the resulting array is then wrapped in a byte array input stream,
     * which does not need to be closed.)
     */
    @NotNull
    public static Reader getUtfReader(@NotNull File file) throws IOException {
        byte[] bytes = Files.toByteArray(file);
        int length = bytes.length;
        if (length == 0) {
            return new StringReader("");
        }

        switch (bytes[0]) {
            case (byte)0xEF: {
                if (length >= 3
                        && bytes[1] == (byte)0xBB
                        && bytes[2] == (byte)0xBF) {
                    // UTF-8 BOM: EF BB BF: Skip it
                    return new InputStreamReader(new ByteArrayInputStream(bytes, 3, length - 3),
                            Charsets.UTF_8);
                }
                break;
            }
            case (byte)0xFE: {
                if (length >= 2
                        && bytes[1] == (byte)0xFF) {
                    // UTF-16 Big Endian BOM: FE FF
                    return new InputStreamReader(new ByteArrayInputStream(bytes, 2, length - 2),
                            Charsets.UTF_16BE);
                }
                break;
            }
            case (byte)0xFF: {
                if (length >= 2
                        && bytes[1] == (byte)0xFE) {
                    if (length >= 4
                            && bytes[2] == (byte)0x00
                            && bytes[3] == (byte)0x00) {
                        // UTF-32 Little Endian BOM: FF FE 00 00
                        return new InputStreamReader(new ByteArrayInputStream(bytes, 4,
                                length - 4), "UTF-32LE");
                    }

                    // UTF-16 Little Endian BOM: FF FE
                    return new InputStreamReader(new ByteArrayInputStream(bytes, 2, length - 2),
                            Charsets.UTF_16LE);
                }
                break;
            }
            case (byte)0x00: {
                if (length >= 4
                        && bytes[0] == (byte)0x00
                        && bytes[1] == (byte)0x00
                        && bytes[2] == (byte)0xFE
                        && bytes[3] == (byte)0xFF) {
                    // UTF-32 Big Endian BOM: 00 00 FE FF
                    return new InputStreamReader(new ByteArrayInputStream(bytes, 4, length - 4),
                            "UTF-32BE");
                }
                break;
            }
        }

        // No byte order mark: Assume UTF-8 (where the BOM is optional).
        return new InputStreamReader(new ByteArrayInputStream(bytes), Charsets.UTF_8);
    }

    /**
     * Parses the given XML string as a DOM document, using the JDK parser. The parser does not
     * validate, and is optionally namespace aware.
     *
     * @param xml            the XML content to be parsed (must be well formed)
     * @param namespaceAware whether the parser is namespace aware
     * @return the DOM document
     */
    @NotNull
    public static Document parseDocument(@NotNull String xml, boolean namespaceAware)
            throws IOException, SAXException {
        xml = stripBom(xml);
        return parseDocument(new StringReader(xml), namespaceAware);
    }

    /**
     * Parses the given {@link Reader} as a DOM document, using the JDK parser. The parser does not
     * validate, and is optionally namespace aware.
     *
     * @param xml            a reader for the XML content to be parsed (must be well formed)
     * @param namespaceAware whether the parser is namespace aware
     * @return the DOM document
     */
    @NotNull
    public static Document parseDocument(@NotNull Reader xml, boolean namespaceAware)
            throws IOException, SAXException {
        InputSource is = new InputSource(xml);
        return createDocumentBuilder(namespaceAware).parse(is);
    }

    /**
     * Parses the given UTF file as a DOM document, using the JDK parser. The parser does not
     * validate, and is optionally namespace aware.
     *
     * @param file           the UTF encoded file to parse
     * @param namespaceAware whether the parser is namespace aware
     * @return the DOM document
     */
    @NotNull
    public static Document parseUtfXmlFile(@NotNull File file, boolean namespaceAware)
            throws IOException, SAXException {
        try (Reader reader = getUtfReader(file)) {
            return parseDocument(reader, namespaceAware);
        }
    }

    /** Creates and returns a new empty document. */
    @NotNull
    public static Document createDocument(boolean namespaceAware) {
        return createDocumentBuilder(namespaceAware).newDocument();
    }

    /** Creates a preconfigured document builder. */
    @NotNull
    private static DocumentBuilder createDocumentBuilder(boolean namespaceAware) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(namespaceAware);
            factory.setValidating(false);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new Error(e); // Impossible in the current context.
        }
    }

    /** Strips out a leading UTF byte order mark, if present */
    @NotNull
    public static String stripBom(@NotNull String xml) {
        if (!xml.isEmpty() && xml.charAt(0) == '\uFEFF') {
            return xml.substring(1);
        }
        return xml;
    }

    /**
     * Parses the given XML string as a DOM document, using the JDK parser. The parser does not
     * validate, and is optionally namespace aware. Any parsing errors are silently ignored.
     *
     * @param xml            the XML content to be parsed (must be well formed)
     * @param namespaceAware whether the parser is namespace aware
     * @return the DOM document, or null
     */
    @Nullable
    public static Document parseDocumentSilently(@NotNull String xml, boolean namespaceAware) {
        try {
            return parseDocument(xml, namespaceAware);
        } catch (Exception e) {
            // pass
            // This method is deliberately silent; will return null
        }

        return null;
    }

    public static SAXParserFactory configureSaxFactory(@NotNull SAXParserFactory factory,
                                                       boolean namespaceAware, boolean checkDtd) {
        try {
            factory.setXIncludeAware(false);
            factory.setNamespaceAware(namespaceAware); // http://xml.org/sax/features/namespaces
            factory.setFeature(NAMESPACE_PREFIX_FEATURE, namespaceAware);
            factory.setFeature(PROVIDE_XMLNS_URIS, namespaceAware);
            factory.setValidating(checkDtd);
        } catch (ParserConfigurationException|SAXException ignore) {
        }

        return factory;
    }

    public static SAXParserFactory getConfiguredSaxFactory(
            boolean namespaceAware, boolean checkDtd) {
        SAXParserFactory factory = SAXParserFactory.newInstance(SAX_PARSER_FACTORY, null);
        return configureSaxFactory(factory, namespaceAware, checkDtd);
    }

    @NotNull
    public static SAXParser createSaxParser(@NotNull SAXParserFactory factory)
            throws ParserConfigurationException, SAXException {
        return createSaxParser(factory, false);
    }

    @NotNull
    public static SAXParser createSaxParser(
            @NotNull SAXParserFactory factory,
            boolean allowDocTypeDeclarations) throws ParserConfigurationException, SAXException {
        SAXParser parser = factory.newSAXParser();
        XMLReader reader = parser.getXMLReader();

        // Prevent XML External Entity attack
        if (!allowDocTypeDeclarations) {
            // Most secure
            reader.setFeature(DISALLOW_DOCTYPE_DECL, true);
        } else {
            reader.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            reader.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            reader.setFeature(LOAD_EXTERNAL_DTD, false);
        }

        return parser;
    }

    /**
     * Dump an XML tree to string. This does not perform any pretty printing.
     * To perform pretty printing, use {@code XmlPrettyPrinter.prettyPrint(node)} in
     * {@code sdk-common}.
     */
    public static String toXml(@NotNull Node node) {
        return toXml(node, null);
    }

    public static String toXml(
            @NotNull Node node,
            @Nullable Map<SourcePosition, SourceFilePosition> blame) {
        PositionAwareStringBuilder sb = new PositionAwareStringBuilder(1000);
        Set<Node> nodesInPath = Sets.newHashSet();
        append(sb, node, blame, nodesInPath);
        return sb.toString();
    }

    /** Dump node to string without indentation adjustments */
    private static void append(
            @NotNull PositionAwareStringBuilder sb,
            @NotNull Node node,
            @Nullable Map<SourcePosition, SourceFilePosition> blame,
            @NotNull Set<Node> nodesInPath) {
        if (!nodesInPath.add(node)) {
            throw new RuntimeException("Circular dependency in XML " + sb.toString());
        }
        if (nodesInPath.size() > MAXIMUM_XML_DEPTH) {
            throw new RuntimeException("Maximum XML depth reached " + sb.toString());
        }
        short nodeType = node.getNodeType();
        int currentLine = sb.line;
        int currentColumn = sb.column;
        int currentOffset = sb.getOffset();
        switch (nodeType) {
            case Node.DOCUMENT_NODE:
            case Node.DOCUMENT_FRAGMENT_NODE:
            {
                sb.append(XML_PROLOG);
                NodeList children = node.getChildNodes();
                for (int i = 0, n = children.getLength(); i < n; i++) {
                    Node child = children.item(i);
                    append(sb, child, blame, nodesInPath);
                }
                break;
            }
            case Node.COMMENT_NODE:
                sb.append(XML_COMMENT_BEGIN);
                sb.append(node.getNodeValue());
                sb.append(XML_COMMENT_END);
                break;
            case Node.TEXT_NODE: {
                sb.append(toXmlTextValue(node.getNodeValue()));
                break;
            }
            case Node.CDATA_SECTION_NODE: {
                sb.append("<![CDATA["); //$NON-NLS-1$
                sb.append(node.getNodeValue());
                sb.append("]]>");       //$NON-NLS-1$
                break;
            }
            case Node.ELEMENT_NODE:
            {
                sb.append('<');
                Element element = (Element) node;
                sb.append(element.getTagName());

                NamedNodeMap attributes = element.getAttributes();
                NodeList children = element.getChildNodes();
                int childCount = children.getLength();
                int attributeCount = attributes.getLength();

                if (attributeCount > 0) {
                    for (int i = 0; i < attributeCount; i++) {
                        Node attribute = attributes.item(i);
                        sb.append(' ');
                        sb.append(attribute.getNodeName());
                        sb.append('=').append('"');
                        sb.append(toXmlAttributeValue(attribute.getNodeValue()));
                        sb.append('"');
                    }
                }

                if (childCount == 0) {
                    sb.append('/');
                }
                sb.append('>');
                if (childCount > 0) {
                    for (int i = 0; i < childCount; i++) {
                        Node child = children.item(i);
                        append(sb, child, blame, nodesInPath);
                    }
                    sb.append('<').append('/');
                    sb.append(element.getTagName());
                    sb.append('>');
                }

                if (blame != null) {
                    SourceFilePosition position = getSourceFilePosition(node);
                    if (!position.equals(SourceFilePosition.UNKNOWN)) {
                        blame.put(
                                new SourcePosition(
                                        currentLine,
                                        currentColumn,
                                        currentOffset,
                                        sb.line,
                                        sb.column,
                                        sb.getOffset()),
                                position);
                    }
                }
                break;
            }

            default:
                throw new UnsupportedOperationException(
                        "Unsupported node type " + nodeType + ": not yet implemented");
        }
        nodesInPath.remove(node);
    }

    /**
     * Wraps a StringBuilder, but keeps track of the line and column of the end of the string.
     *
     * It implements append(String) and append(char) which as well as delegating to the underlying
     * StringBuilder also keep track of any new lines, and set the line and column fields.
     * The StringBuilder itself keeps track of the actual character offset.
     */
    private static class PositionAwareStringBuilder {
        @SuppressWarnings("StringBufferField")
        private final StringBuilder sb;
        int line = 0;
        int column = 0;

        public PositionAwareStringBuilder(int size) {
            sb = new StringBuilder(size);
        }

        public PositionAwareStringBuilder append(String text) {
            sb.append(text);
            // we find the last, as it might be useful later.
            int lastNewLineIndex = text.lastIndexOf('\n');
            if (lastNewLineIndex == -1) {
                // If it does not contain a new line, we just increase the column number.
                column += text.length();
            } else {
                // The string could contain multiple new lines.
                line += CharMatcher.is('\n').countIn(text);
                // But for column we only care about the number of characters after the last one.
                column = text.length() - lastNewLineIndex - 1;
            }
            return this;
        }

        public PositionAwareStringBuilder append(char character) {
            sb.append(character);
            if (character == '\n') {
                line += 1;
                column = 0;
            } else {
                column++;
            }
            return this;
        }

        public int getOffset() {
            return sb.length();
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }

    public static void attachSourceFile(@NotNull Node node, @NotNull SourceFile sourceFile) {
        node.setUserData(SOURCE_FILE_USER_DATA_KEY, sourceFile, null);
    }

    @NotNull
    public static SourceFilePosition getSourceFilePosition(@NotNull Node node) {
        SourceFile sourceFile = (SourceFile) node.getUserData(SOURCE_FILE_USER_DATA_KEY);
        if (sourceFile == null) {
            sourceFile = SourceFile.UNKNOWN;
        }
        return new SourceFilePosition(sourceFile, PositionXmlParser.getPosition(node));
    }

    /**
     * Formats the number and removes trailing zeros after the decimal dot and also the dot itself
     * if there were non-zero digits after it.
     *
     * @param value the value to be formatted
     * @return the corresponding XML string for the value
     */
    @NotNull
    public static String formatFloatValue(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Invalid number: " + value);
        }
        // Use locale-independent conversion to make sure that the decimal separator is always dot.
        // We use Float.toString as opposed to Double.toString to avoid writing too many
        // insignificant digits.
        String result = Float.toString((float) value);
        return DecimalUtils.trimInsignificantZeros(result);
    }

    /**
     * Returns the name of the root element tag stored in the given file, or null if it can't be
     * determined.
     */
    @Nullable
    public static String getRootTagName(@NotNull File xmlFile) {
        try (InputStream stream = new BufferedInputStream(new FileInputStream(xmlFile))) {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);

            while (xmlStreamReader.hasNext()) {
                int event = xmlStreamReader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    return xmlStreamReader.getLocalName();
                }
            }
        } catch (XMLStreamException | IOException ignored) {
            // Ignored.
        }

        return null;
    }

    /**
     * Returns the name of the root element tag stored in the given file, or null if it can't be
     * determined.
     */
    @Nullable
    public static String getRootTagName(@NotNull String xmlText) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        try (Reader reader = new StringReader(xmlText)) {
            XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(reader);

            while (xmlStreamReader.hasNext()) {
                int event = xmlStreamReader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    return xmlStreamReader.getLocalName();
                }
            }
        } catch (IOException | XMLStreamException e) {
            // Ignore.
        }
        return null;
    }

    /**
     * Returns the children elements of the given node
     *
     * @param parent the parent node
     * @return a list of element children, never null
     */
    @NotNull
    public static List<Element> getSubTagsAsList(@NotNull Node parent) {
        NodeList childNodes = parent.getChildNodes();
        List<Element> children = new ArrayList<>(childNodes.getLength());
        for (int i = 0, n = childNodes.getLength(); i < n; i++) {
            Node child = childNodes.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) child);
            }
        }

        return children;
    }

    /**
     * Returns an iterator for the children elements of the given node.
     * If you want to access the children as a list, use
     * {@link #getSubTagsAsList(Node)} instead.
     * <p>
     * <b>NOTE: The iterator() call can only be called once!</b>
     */
    @NotNull
    public static Iterable<Element> getSubTags(@Nullable Node parent) {
        return new SubTagIterator(parent);
    }

    /**
     * Returns an iterator for the children elements of the given node matching the
     * given tag name.
     * <p>
     * If you want to access the children as a list, use
     * {@link #getSubTagsAsList(Node)} instead.
     * <p>
     * <b>NOTE: The iterator() call can only be called once!</b>
     */
    @NotNull
    public static Iterable<Element> getSubTagsByName(@Nullable Node parent, @NotNull String tagName) {
        return new NamedSubTagIterator(parent, tagName);
    }

    private static class SubTagIterator implements Iterator<Element>, Iterable<Element> {
        private Element next;
        private boolean used;

        public SubTagIterator(@Nullable Node parent) {
            this.next = getFirstSubTag(parent);
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Element next() {
            Element ret = next;
            next = getNextTag(next);
            return ret;
        }

        @NotNull
        @Override
        public Iterator<Element> iterator() {
            assert !used;
            used = true;
            return this;
        }
    }

    private static class NamedSubTagIterator implements Iterator<Element>, Iterable<Element> {
        private final String name;
        private Element next;
        private boolean used;

        public NamedSubTagIterator(@Nullable Node parent, @NotNull String name) {
            this.name = name;
            this.next = getFirstSubTagByName(parent, name);
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Element next() {
            Element ret = next;
            next = getNextTagByName(next, name);
            return ret;
        }

        @NotNull
        @Override
        public Iterator<Element> iterator() {
            assert !used;
            used = true;
            return this;
        }
    }

    /** Returns the first child element of the given node */
    @Nullable
    public static Element getFirstSubTag(@Nullable Node parent) {
        if (parent == null) {
            return null;
        }
        Node curr = parent.getFirstChild();
        while (curr != null) {
            if (curr.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) curr;
            }

            curr = curr.getNextSibling();
        }

        return null;
    }

    /** Returns the next sibling element from the given node */
    @Nullable
    public static Element getNextTag(@Nullable Node node) {
        if (node == null) {
            return null;
        }
        Node curr = node.getNextSibling();
        while (curr != null) {
            if (curr.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) curr;
            }

            curr = curr.getNextSibling();
        }

        return null;
    }

    /** Returns the previous sibling element from the given node */
    @Nullable
    public static Element getPreviousTag(@Nullable Node node) {
        if (node == null) {
            return null;
        }
        Node curr = node.getPreviousSibling();
        while (curr != null) {
            if (curr.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) curr;
            }

            curr = curr.getPreviousSibling();
        }

        return null;
    }

    /** Returns the next sibling element from the given node that matches the given name */
    @Nullable
    public static Element getFirstSubTagByName(@Nullable Node parent, @NotNull String name) {
        if (parent == null) {
            return null;
        }
        Node curr = parent.getFirstChild();
        while (curr != null) {
            if (curr.getNodeType() == Node.ELEMENT_NODE) {
                String currName = curr.getLocalName();
                if (currName == null) {
                    currName = curr.getNodeName();
                }
                if (name.equals(currName)) {
                    return (Element) curr;
                }
            }

            curr = curr.getNextSibling();
        }

        return null;
    }

    /** Returns the next sibling element from the given node */
    @Nullable
    public static Element getNextTagByName(@Nullable Node node, @NotNull String name) {
        if (node == null) {
            return null;
        }
        Node curr = node.getNextSibling();
        while (curr != null) {
            if (curr.getNodeType() == Node.ELEMENT_NODE) {
                String currName = curr.getLocalName();
                if (currName == null) {
                    currName = curr.getNodeName();
                }
                if (name.equals(currName)) {
                    return (Element) curr;
                }
            }

            curr = curr.getNextSibling();
        }

        return null;
    }

    @Nullable
    public static Element getPreviousTagByName(@Nullable Node node, @NotNull String name) {
        if (node == null) {
            return null;
        }
        Node curr = node.getPreviousSibling();
        while (curr != null) {
            if (curr.getNodeType() == Node.ELEMENT_NODE) {
                String currName = curr.getLocalName();
                if (currName == null) {
                    currName = curr.getNodeName();
                }
                if (name.equals(currName)) {
                    return (Element) curr;
                }
            }

            curr = curr.getPreviousSibling();
        }

        return null;
    }

    /**
     * Returns the comment preceding the given element with no other elements in between, or null
     * if the element is not preceded by a comment.
     */
    @Nullable
    public static Comment getPreviousComment(@NotNull Node element) {
        Node node = element;
        do {
            node = node.getPreviousSibling();
            if (node instanceof Comment) {
                return (Comment)node;
            }
        }
        while (node instanceof Text && CharMatcher.whitespace().matchesAllOf(node.getNodeValue()));
        return null;
    }

    /**
     * Returns the text of the comment preceding the given element with no other elements in
     * between, or null if the element is not preceded by a comment or if the comment is empty
     * or consists of only whitespace characters.
     */
    @Nullable
    public static String getPreviousCommentText(@NotNull Node element) {
        Comment comment = getPreviousComment(element);
        if (comment != null) {
            String text = comment.getNodeValue();
            if (!CharMatcher.whitespace().matchesAllOf(text)) {
                return text.trim();
            }
        }
        return null;
    }

    /**
     * Returns the number of children sub tags of the given node.
     *
     * @param parent the parent node
     * @return the count of element children
     */
    public static int getSubTagCount(@Nullable Node parent) {
        if (parent == null) {
            return 0;
        }
        NodeList childNodes = parent.getChildNodes();
        int childCount = 0;
        for (int i = 0, n = childNodes.getLength(); i < n; i++) {
            Node child = childNodes.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                childCount++;
            }
        }

        return childCount;
    }

    /**
     * Checks if the given array of bytes is likely to represent XML in a proto format.
     *
     * @param bytes the candidate XML contents to check
     * @return true if the bytes are likely to represent proto XML
     */
    public static boolean isProtoXml(@NotNull byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            byte c = bytes[i];
            if (i == 0 && c != PROTO_XML_LEAD_BYTE) {
                return false;
            } else if (!Character.isWhitespace(c)) {
                return c != '<';
            }
        }
        return true;
    }

    /**
     * Checks if the given input stream is likely to represent XML in a proto format.
     *
     * @param stream the candidate XML stream to check
     * @return true if the stream is likely to represent proto XML
     */
    public static boolean isProtoXml(@NotNull InputStream stream) {
        boolean isProto = false;
        int readLimit = 100;
        if (stream.markSupported()) {
            stream.mark(readLimit);
            try {
                try {
                    int c;
                    for (int i = 0; i < readLimit && (c = stream.read()) >= 0; i++) {
                        if (i == 0 && c != PROTO_XML_LEAD_BYTE) {
                            break;
                        } else if (!Character.isWhitespace(c)) {
                            isProto = c != '<';
                            break;
                        }
                    }
                } finally {
                    stream.reset();
                }
            } catch (IOException e) {
                // Ignore and assume text XML.
            }
        }
        return isProto;
    }
}