package com.tyron.completion.xml.util;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.tyron.completion.CompletionParameters;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.completion.xml.XmlCharacter;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.lexer.XMLLexer;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.Format;
import com.tyron.completion.xml.model.XmlCompletionType;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.stream.Collectors;

public class XmlUtils {

    private static final XmlPullParserFactory sParserFactory;

    static {
        XmlPullParserFactory sParserFactory1;
        try {
            sParserFactory1 = XmlPullParserFactory.newInstance();
        } catch (XmlPullParserException e) {
            sParserFactory1 = null;
            e.printStackTrace();
        }
        sParserFactory = sParserFactory1;
    }

    public static XmlPullParser newPullParser() throws XmlPullParserException {
        return sParserFactory.newPullParser();
    }

    public static String partialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && !XmlCharacter.isNonXmlCharacterPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    public static String fullIdentifier(String contents, int start) {
        int end = start;
        while (end < contents.length() &&
               !XmlCharacter.isNonXmlCharacterPart(contents.charAt(end - 1))) {
            end++;
        }
        return contents.substring(start, end);
    }

    public static String getAttributeValueFromPrefix(String prefix) {
        String attributeValue = prefix;
        if (attributeValue.contains("=")) {
            attributeValue = attributeValue.substring(attributeValue.indexOf('=') + 1);
        }
        if (attributeValue.startsWith("\"")) {
            attributeValue = attributeValue.substring(1);
        }
        if (attributeValue.endsWith("\"")) {
            attributeValue = attributeValue.substring(0, attributeValue.length() - 1);
        }
        return attributeValue;
    }


    public static String getAttributeNameFromPrefix(String prefix) {
        String attributeName = prefix;
        if (attributeName.contains("=")) {
            attributeName = prefix.substring(0, prefix.indexOf('='));
        }
        if (prefix.contains(":")) {
            attributeName = attributeName.substring(attributeName.indexOf(':') + 1);
        }
        return attributeName;
    }

    /**
     * @return depth at the current position
     */
    public static int getDepthAtPosition(XmlPullParser parser, int line) {
        while (parser.getLineNumber() < line) {
            try {
                parser.nextToken();
            } catch (IOException | XmlPullParserException e) {
                // keep parsing
            }
        }
        return parser.getDepth();
    }

    public static boolean isInAttribute(String contents, int line, int column) throws XmlPullParserException, IOException {
        XmlPullParser parser = newPullParser();
        parser.setInput(new StringReader(contents));
        return isInAttribute(parser, line, column);
    }

    public static boolean isInAttribute(XmlPullParser parser, int line, int column) throws IOException, XmlPullParserException {
        int tag = parser.next();
        boolean isInStart = false;
        while (tag != XmlPullParser.END_DOCUMENT) {
            try {
                if (tag == XmlPullParser.START_TAG) {
                    if (parser.getLineNumber() == line) {
                        if (column >= parser.getColumnNumber()) {
                            isInStart = true;
                        }
                    } else {
                        isInStart = parser.getLineNumber() < line;
                    }
                } else if (tag == XmlPullParser.END_TAG) {
                    if (isInStart) {
                        if (line < parser.getLineNumber()) {
                            return true;
                        } else if (line == parser.getLineNumber()) {
                            return column <= parser.getColumnNumber();
                        }
                    }
                    isInStart = false;
                }
                tag = parser.next();
            } catch (XmlPullParserException e) {
                // ignored, continue parsing
            }
        }

        return false;
    }

    /**
     * @return pair of the parent tag and the current tag at the current position
     */
    public static Pair<String, String> getTagAtPosition(XmlPullParser parser, int line,
                                                        int column) throws XmlPullParserException {

        String parentTag = null;
        int previousDepth = 0;
        int currentDepth = 0;
        String tag = null;
        do {
            ProgressManager.checkCanceled();
            try {
                parser.next();
            } catch (IOException | XmlPullParserException e) {
                System.out.println(e);
                // continue
            }

            int type = parser.getEventType();

            if (type == XmlPullParser.END_DOCUMENT) {
                break;
            }

            if (parser.getLineNumber() >= line && type != XmlPullParser.TEXT) {
                currentDepth = parser.getDepth();
                tag = parser.getName();
                break;
            }

            if (type == XmlPullParser.START_TAG && parser.getDepth() > previousDepth) {
                parentTag = parser.getName();
            }

            previousDepth = parser.getDepth();
        } while (true);

        return Pair.create(parentTag, tag);
    }

    /**
     * Checks whether the index is at the attribute tag of the node
     *
     * @param node  The xml nod
     * @param index The index of the cursor
     * @return whther the index is at the attribite tag of the node
     */
    public static boolean isTag(DOMNode node, long index) {
        String name = node.getNodeName();
        if (name == null) {
            name = "";
        }
        return node.getStart() < index && index <= (node.getStart() + name.length() + 1);
    }

    public static boolean isEndTag(DOMNode node, long index) {
        if (!(node instanceof DOMElement)) {
            return false;
        }
        DOMElement element = (DOMElement) node;
        int endOpenOffset = element.getEndTagOpenOffset();
        if (endOpenOffset == -1) {
            return false;
        }
        return index >= endOpenOffset;
    }

    /**
     * Return the owner element of an attribute node
     *
     * @param element The element
     * @return The owner element
     */
    public static Element getElementNode(Node element) {
        if (element.getNodeType() == Node.ELEMENT_NODE) {
            return (Element) element;
        }
        if (element.getNodeType() == Node.ATTRIBUTE_NODE) {
            return ((Attr) element).getOwnerElement();
        }
        return null;
    }

    /**
     * Uses jsoup to build a well formed xml from a broken one.
     * Do not depend on the attribute positions as it does not match the original source.
     *
     * @param contents The broken xml contents
     * @return Well formed xml
     */
    public static String buildFixedXml(String contents) {
        Parser parser = Parser.xmlParser();
        Document document = Jsoup.parse(contents, "", parser);
        Document.OutputSettings settings = document.outputSettings();
        settings.prettyPrint(false);
        return document.toString();
    }

    /**
     * @return whether the current index is inside an attribute value,
     * e.g {@code attribute="CURSOR"}
     */
    public static boolean isInAttributeValue(String contents, int index) {
        XMLLexer lexer = new XMLLexer(CharStreams.fromString(contents));
        Token token;
        while ((token = lexer.nextToken()) != null) {
            int start = token.getStartIndex();
            int end = token.getStopIndex();

            if (token.getType() == Token.EOF) {
                break;
            }

            if (start <= index && index <= end) {
                return token.getType() == XMLLexer.STRING;
            }

            if (end > index) {
                break;
            }
        }
        return false;
    }

    public static boolean isIncrementalCompletion(CachedCompletion cachedCompletion,
                                                  CompletionParameters parameters) {
        File file = parameters.getFile();
        String prefix = parameters.getPrefix();
        int line = parameters.getLine();
        int column = parameters.getColumn();

        prefix = partialIdentifier(prefix, prefix.length());

        if (line == -1) {
            return false;
        }

        if (column == -1) {
            return false;
        }

        if (cachedCompletion == null) {
            return false;
        }

        if (!file.equals(cachedCompletion.getFile())) {
            return false;
        }

        if (prefix.endsWith(".")) {
            return false;
        }

        if (cachedCompletion.getLine() != line) {
            return false;
        }

        if (cachedCompletion.getColumn() > column) {
            return false;
        }

        if (!prefix.startsWith(cachedCompletion.getPrefix())) {
            return false;
        }

        return prefix.length() -
               cachedCompletion.getPrefix()
                       .length() == column - cachedCompletion.getColumn();
    }

    @SuppressLint("NewApi")
    public static CompletionItem getAttributeItem(XmlRepository repository,
                                                  AttributeInfo attributeInfo,
                                                  boolean shouldShowNamespace, String fixedPrefix) {

        if (attributeInfo.getFormats() == null ||
            attributeInfo.getFormats()
                    .isEmpty()) {
            AttributeInfo extraAttributeInfo =
                    repository.getExtraAttribute(attributeInfo.getName());
            if (extraAttributeInfo != null) {
                attributeInfo = extraAttributeInfo;
            }
        }
        String commitText = "";
        commitText = (TextUtils.isEmpty(
                attributeInfo.getNamespace()) ? "" : attributeInfo.getNamespace() + ":");
        commitText += attributeInfo.getName();

        CompletionItem item = new CompletionItem();
        item.action = CompletionItem.Kind.NORMAL;
        item.label = commitText;
        item.iconKind = DrawableKind.Attribute;
        item.detail = attributeInfo.getFormats()
                .stream()
                .map(Format::name)
                .collect(Collectors.joining("|"));
        item.commitText = commitText;
        if (!fixedPrefix.contains("=")) {
            item.commitText += "=\"\"";
            item.cursorOffset = item.commitText.length() - 1;
        } else {
            item.cursorOffset = item.commitText.length() + 2;
        }
        return item;
    }

    public static boolean isFlagValue(DOMAttr attr, int index) {
        String text = attr.getOwnerDocument()
                .getText();
        String value = text.substring(attr.getNodeAttrValue()
                                                  .getStart() + 1, (int) index);
        return value.contains("|");
    }

    @Nullable
    public static String getPrefix(DOMDocument parsed, long index, XmlCompletionType type) {
        String text = parsed.getText();
        switch (type) {
            case TAG:
                DOMNode nodeAt = parsed.findNodeAt((int) index);
                if (nodeAt == null) {
                    return null;
                }
                return text.substring(nodeAt.getStart(), (int) index);
            case ATTRIBUTE:
                DOMAttr attr = parsed.findAttrAt((int) index);
                if (attr == null) {
                    return null;
                }
                return text.substring(attr.getStart(), (int) index);
            case ATTRIBUTE_VALUE:
                DOMAttr attrAt = parsed.findAttrAt((int) index);
                if (attrAt == null) {
                    return null;
                }
                return getFlagValuePrefix(text.substring(attrAt.getNodeAttrValue()
                                                                 .getStart() + 1, (int) index));
        }
        return null;
    }

    private static String getFlagValuePrefix(String prefix) {
        if (prefix.contains("|")) {
            prefix = prefix.substring(prefix.lastIndexOf('|') + 1);
        }
        return prefix;
    }

    public static XmlCompletionType getCompletionType(DOMDocument parsed, long cursor) {
        DOMNode nodeAt = parsed.findNodeAt((int) cursor);
        if (nodeAt == null) {
            return XmlCompletionType.UNKNOWN;
        }

        if (isTag(nodeAt, cursor) || isEndTag(nodeAt, cursor)) {
            return XmlCompletionType.TAG;
        }

        if (isInAttributeValue(parsed.getTextDocument()
                                       .getText(), (int) cursor)) {
            return XmlCompletionType.ATTRIBUTE_VALUE;
        }
        return XmlCompletionType.ATTRIBUTE;
    }
}
