package com.tyron.completion.xml.util;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.util.Pair;

import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.XmlCharacter;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.lexer.XMLLexer;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.Format;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
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
        while (end < contents.length() && !XmlCharacter.isNonXmlCharacterPart(contents.charAt(end - 1))) {
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
     * @return pair of the parent tag and the current tag at the current position
     */
    public static Pair<String, String> getTagAtPosition(XmlPullParser parser, int line,
                                                        int column) {
        int lineNumber = parser.getLineNumber();
        int previousDepth = parser.getDepth();
        String previousTag = "";
        String parentTag = "";
        String tag = parser.getName();
        while (lineNumber < line) {
            previousTag = parser.getName();
            try {
                parser.nextTag();
            } catch (Throwable e) {
                // ignored, keep parsing
            }
            lineNumber = parser.getLineNumber();

            if (parser.getName() != null) {
                tag = parser.getName();
            }

            if (parser.getDepth() > previousDepth) {
                previousDepth = parser.getDepth();
                parentTag = previousTag;
            }
        }

        if (parentTag == null && previousTag != null) {
            parentTag = previousTag;
        }

        return Pair.create(parentTag, tag);
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

            if (start <= index && index <= end) {
                return token.getType() == XMLLexer.STRING;
            }

            if (end > index) {
                break;
            }
        }
        return false;
    }

    public static boolean isIncrementalCompletion(CachedCompletion cachedCompletion, File file,
                                            String prefix, int line, int column) {
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

        return prefix.length() - cachedCompletion.getPrefix().length() == column - cachedCompletion.getColumn();
    }

    @SuppressLint("NewApi")
    public static CompletionItem getAttributeItem(XmlRepository repository, AttributeInfo attributeInfo,
                                            boolean shouldShowNamespace,
                                            String fixedPrefix) {

        if (attributeInfo.getFormats() == null || attributeInfo.getFormats().isEmpty()) {
            AttributeInfo extraAttributeInfo =
                    repository.getExtraAttribute(attributeInfo.getName());
            if (extraAttributeInfo != null) {
                attributeInfo = extraAttributeInfo;
            }
        }
        String commitText = "";
        commitText = (TextUtils.isEmpty(attributeInfo.getNamespace()) ? "" :
                attributeInfo.getNamespace() + ":");
        commitText += attributeInfo.getName();

        CompletionItem item = new CompletionItem();
        item.action = CompletionItem.Kind.NORMAL;
        item.label = commitText;
        item.iconKind = DrawableKind.Attribute;
        item.detail = attributeInfo.getFormats().stream()
                .map(Format::name).collect(Collectors.joining("|"));
        item.commitText = commitText;
        if (!fixedPrefix.contains("=")) {
            item.commitText += "=\"\"";
            item.cursorOffset = item.commitText.length() - 1;
        } else {
            item.cursorOffset = item.commitText.length() + 2;
        }
        return item;
    }


}
