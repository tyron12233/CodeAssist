package com.tyron.completion.xml;

import android.annotation.SuppressLint;
import android.os.Build;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.RequiresApi;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.lexer.XMLLexer;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.DeclareStyleable;
import com.tyron.completion.xml.model.Format;
import com.tyron.completion.xml.model.XmlCachedCompletion;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class XmlCompletionProvider extends CompletionProvider {

    private static final String EXTENSION = ".xml";
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

    private XmlCachedCompletion mCachedCompletion;

    public XmlCompletionProvider() {

    }

    @Override
    public String getFileExtension() {
        return EXTENSION;
    }

    @SuppressLint("NewApi")
    @Override
    public CompletionList complete(Project project, Module module, File file, String contents,
                                   String prefix, int line, int column, long index) {

        if (!(module instanceof AndroidModule)) {
            return CompletionList.EMPTY;
        }

        String partialIdentifier = partialIdentifier(contents, (int) index);

        if (isIncrementalCompletion(mCachedCompletion, file, prefix, line, column)) {
            if (mCachedCompletion.getCompletionType() == XmlCachedCompletion.TYPE_ATTRIBUTE_VALUE) {
                mCachedCompletion.setFilterPrefix(prefix);
            } else {
                mCachedCompletion.setFilterPrefix(partialIdentifier);
            }
            return mCachedCompletion.getCompletionList();
        }
        try {
            XmlCachedCompletion list =
                    completeInternal(project, (AndroidModule) module,
                            file, contents, prefix, line, column, index);
            mCachedCompletion = list;
            return list.getCompletionList();
        } catch (XmlPullParserException | IOException e) {
            e.printStackTrace();
        }

        return CompletionList.EMPTY;
    }

    private boolean isIncrementalCompletion(CachedCompletion cachedCompletion, File file,
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

    @RequiresApi(api = Build.VERSION_CODES.N)
    private XmlCachedCompletion completeInternal(Project project, AndroidModule module, File file, String contents,
                                                 String prefix, int line, int column, long index) throws XmlPullParserException, IOException {
        CompletionList list = new CompletionList();
        XmlCachedCompletion xmlCachedCompletion = new XmlCachedCompletion(file,
                line, column, prefix, list);

        XmlIndexProvider indexProvider =
                CompilerService.getInstance().getIndex(XmlIndexProvider.KEY);
        XmlRepository repository = indexProvider.get(project, module);
        repository.initialize(module);
        Map<String, DeclareStyleable> declareStyleables = repository.getDeclareStyleables();

        String fixedPrefix = partialIdentifier(contents, (int) index);
        String fullPrefix = fullIdentifier(contents, (int) index);
        boolean shouldShowNamespace = !fixedPrefix.contains(":");

        XmlPullParser parser = sParserFactory.newPullParser();
        parser.setInput(new StringReader(contents));

        list.items = new ArrayList<>();

        Pair<String, String> tagPair = getTagAtPosition(parser, line + 1, column + 1);
        String parentTag = tagPair.first;
        String tag = tagPair.second;

        // first get the attributes based on the current tag
        Set<DeclareStyleable> styles = StyleUtils.getStyles(declareStyleables, tag);

        // get the layout params attributes from parent
        // in android convention, layout attributes will end with _Layout
        styles.addAll(StyleUtils.getLayoutParam(declareStyleables, parentTag));


        // parent is unknown, display all attributes
        if (styles.isEmpty()) {
            styles.addAll(declareStyleables.values());
        }

        if (isInAttributeValue(contents, (int) index)) {
            for (DeclareStyleable style : styles) {
                for (AttributeInfo attributeInfo : style.getAttributeInfos()) {
                    String attributeName = getAttributeNameFromPrefix(fixedPrefix);
                    String namespace = "";
                    if (fixedPrefix.contains(":")) {
                        namespace = fixedPrefix.substring(0, fixedPrefix.indexOf(':'));
                        if (namespace.contains("=")) {
                            namespace = namespace.substring(0, namespace.indexOf('='));
                        }
                    }
                    if (!namespace.equals(attributeInfo.getNamespace())) {
                        continue;
                    }
                    if (!attributeName.equals(attributeInfo.getName())) {
                        continue;
                    }

                    List<String> values = attributeInfo.getValues();
                    if (values == null || values.isEmpty()) {
                        AttributeInfo extraAttribute =
                                repository.getExtraAttribute(attributeInfo.getName());
                        if (extraAttribute != null) {
                            values = extraAttribute.getValues();
                        }
                    }
                    if (values != null) {
                        for (String value : values) {
                            CompletionItem item = new CompletionItem();
                            item.action = CompletionItem.Kind.NORMAL;
                            item.label = value;
                            item.commitText = value;
                            item.iconKind = DrawableKind.Attribute;
                            item.cursorOffset = value.length();
                            item.detail = "Attribute";
                            list.items.add(item);
                        }
                    }
                }
            }
            xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_ATTRIBUTE_VALUE);
            xmlCachedCompletion.setFilterPrefix(prefix);
            xmlCachedCompletion.setFilter((item, pre) -> item.label.startsWith(pre));
        } else {
            for (DeclareStyleable style : styles) {
                for (AttributeInfo attributeInfo : style.getAttributeInfos()) {
                    CompletionItem item = getAttributeItem(repository, attributeInfo, shouldShowNamespace, fullPrefix);
                    list.items.add(item);
                }
            }
            xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_ATTRIBUTE);
            xmlCachedCompletion.setFilterPrefix(fixedPrefix);
            xmlCachedCompletion.setFilter((it, pre) -> {
                if (pre.contains(":")) {
                    if (!it.label.startsWith(pre)) {
                        return false;
                    }
                }
                if (it.label.startsWith(pre)) {
                    return true;
                }

                if (getAttributeValueFromPrefix(it.label).startsWith(getAttributeValueFromPrefix(pre))) {
                    return true;
                }

                return getAttributeNameFromPrefix(it.label).startsWith(getAttributeNameFromPrefix(pre));
            });
        }
        return xmlCachedCompletion;
    }

    private CompletionItem getAttributeItem(XmlRepository repository, AttributeInfo attributeInfo, boolean shouldShowNamespace, String fixedPrefix) {

        if (attributeInfo.getFormats() == null || attributeInfo.getFormats().isEmpty()) {
            AttributeInfo extraAttributeInfo = repository.getExtraAttribute(attributeInfo.getName());
            if (extraAttributeInfo != null) {
                attributeInfo = extraAttributeInfo;
            }
        }
        String commitText = "";
        if (shouldShowNamespace) {
            commitText = (TextUtils.isEmpty(attributeInfo.getNamespace())
                    ? ""
                    : attributeInfo.getNamespace() + ":");
        }
        commitText += attributeInfo.getName();

        CompletionItem item = new CompletionItem();
        item.action = CompletionItem.Kind.NORMAL;
        item.label = (TextUtils.isEmpty(attributeInfo.getNamespace())
                ? ""
                : attributeInfo.getNamespace() + ":")
                + attributeInfo.getName();
        item.iconKind = DrawableKind.Attribute;
        item.detail = attributeInfo.getFormats().stream()
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

    private String partialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && !XmlCharacter.isNonXmlCharacterPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private String fullIdentifier(String contents, int start) {
        int end = start;
        while (end < contents.length() && !XmlCharacter.isNonXmlCharacterPart(contents.charAt(end - 1))) {
            end++;
        }
        return contents.substring(start, end);
    }

    private String getAttributeValueFromPrefix(String prefix) {
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

    private String getAttributeNameFromPrefix(String prefix) {
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
    private Pair<String, String> getTagAtPosition(XmlPullParser parser, int line, int column) {
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
    private boolean isInAttributeValue(String contents, int index) {
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
}
