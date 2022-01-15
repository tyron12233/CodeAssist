package com.tyron.completion.xml;

import android.annotation.SuppressLint;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.lexer.XMLLexer;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.DeclareStyleable;
import com.tyron.completion.xml.model.Format;

import org.antlr.v4.runtime.CharStream;
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

    private static final String EXTENSION = ".xml";

    @Override
    public String getFileExtension() {
        return EXTENSION;
    }

    @SuppressLint("NewApi")
    @Override
    public CompletionList complete(Project project, Module module, File file, String contents,
                                   String prefix, int line, int column, long index) {
        try {
            return completeInternal(project, module, file, contents, prefix, line, column, index);
        } catch (XmlPullParserException | IOException e) {
            e.printStackTrace();
        }
        return CompletionList.EMPTY;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private CompletionList completeInternal(Project project, Module module, File file, String contents,
                                            String prefix, int line, int column, long index) throws XmlPullParserException, IOException {
        XmlIndexProvider indexProvider =
                CompilerService.getInstance().getIndex(XmlIndexProvider.KEY);
        XmlRepository repository = indexProvider.get(project, module);
        repository.initialize();
        Map<String, DeclareStyleable> declareStyleables = repository.getDeclareStyleables();

        XmlPullParser parser = sParserFactory.newPullParser();
        parser.setInput(new StringReader(contents));

        CompletionList list = new CompletionList();
        list.items = new ArrayList<>();

        String tag = getTagAtPosition(parser, line + 1, column + 1);
        Set<DeclareStyleable> styles = StyleUtils.getStyles(declareStyleables, tag);
        if (styles.isEmpty()) {
            styles = StyleUtils.getStyles(declareStyleables, "android.view.View");
        }
        if (styles.isEmpty()) {
            styles.addAll(declareStyleables.values());
        }
        boolean shouldShowNameSpace = prefix.contains(":");

        String fixedPrefix = partialIdentifier(contents, (int) index);

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
            list.items = list.items.stream()
                    .filter(it -> it.label.startsWith(prefix))
                    .collect(Collectors.toList());
        } else {
            for (DeclareStyleable style : styles) {
                for (AttributeInfo attributeInfo : style.getAttributeInfos()) {
                    CompletionItem item = new CompletionItem();
                    item.action = CompletionItem.Kind.NORMAL;
                    item.label = (shouldShowNameSpace ?  attributeInfo.getNamespace() + ":" : "")
                            + attributeInfo.getName();
                    item.commitText = (TextUtils.isEmpty(attributeInfo.getNamespace()) ? "" : attributeInfo.getNamespace() + ":")
                            + attributeInfo.getName();
                    item.cursorOffset = item.commitText.length();
                    item.iconKind = DrawableKind.Attribute;
                    item.detail = attributeInfo.getFormats().stream()
                            .map(Format::name)
                            .collect(Collectors.joining("|"));
                    list.items.add(item);
                }
            }
            list.items = list.items.stream()
                    .filter(it -> {
                        if (fixedPrefix.contains(":")) {
                            if (it.label.startsWith(fixedPrefix)) {
                                return true;
                            }
                        }
                        if (getAttributeNameFromPrefix(it.label).startsWith(fixedPrefix)) {
                            return true;
                        }
                        return getAttributeNameFromPrefix(it.label)
                                .startsWith(getAttributeNameFromPrefix(fixedPrefix));
                    })
                    .collect(Collectors.toList());
        }
        return list;
    }

    private String partialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && !XmlCharacter.isNonXmlCharacterPart(contents.charAt(start - 1))) {
            start--;
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

    private String getTagAtPosition(XmlPullParser parser, int line, int column) {
        int lineNumber = parser.getLineNumber();
        String tag = parser.getName();
        while (lineNumber < line) {
            try {
                parser.nextTag();
            } catch (Throwable e) {
                // ignored, keep parsing
            }
            lineNumber = parser.getLineNumber();
            if (parser.getName() != null) {
                tag = parser.getName();
            }
        }

        return tag;
    }

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
