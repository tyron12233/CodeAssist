package com.tyron.completion.xml.providers;

import static com.tyron.completion.xml.util.XmlUtils.fullIdentifier;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeItem;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeNameFromPrefix;
import static com.tyron.completion.xml.util.XmlUtils.getTagAtPosition;
import static com.tyron.completion.xml.util.XmlUtils.isInAttributeValue;
import static com.tyron.completion.xml.util.XmlUtils.isIncrementalCompletion;
import static com.tyron.completion.xml.util.XmlUtils.partialIdentifier;

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
import com.tyron.completion.xml.XmlCharacter;
import com.tyron.completion.xml.XmlIndexProvider;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.lexer.XMLLexer;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.DeclareStyleable;
import com.tyron.completion.xml.model.Format;
import com.tyron.completion.xml.model.XmlCachedCompletion;
import com.tyron.completion.xml.util.AndroidResourcesUtils;
import com.tyron.completion.xml.util.StyleUtils;
import com.tyron.completion.xml.util.XmlUtils;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.apache.bcel.classfile.JavaClass;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import me.xdrop.fuzzywuzzy.FuzzySearch;

@SuppressLint("NewApi")
public class XmlCompletionProvider extends CompletionProvider {

    private static final String EXTENSION = ".xml";

    private XmlCachedCompletion mCachedCompletion;

    public XmlCompletionProvider() {

    }

    @Override
    public boolean accept(File file) {
        return file.isFile() && AndroidResourcesUtils.isLayoutXMLFile(file);
    }

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
            CompletionList completionList = mCachedCompletion.getCompletionList();
            Collections.sort(completionList.items, (item1, item2) -> {
                String filterPrefix = mCachedCompletion.getFilterPrefix();
                int first = FuzzySearch.partialRatio(item1.label, filterPrefix);
                int second = FuzzySearch.partialRatio(item2.label, filterPrefix);
                return Integer.compare(first, second);
            });
            Collections.reverse(completionList.items);
            return completionList;
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

        XmlPullParser parser = XmlUtils.newPullParser();
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

        if (prefix.startsWith("<")) {
            addTagItems(repository, prefix, list, xmlCachedCompletion);
        } else {
            if (isInAttributeValue(contents, (int) index)) {
                addAttributeValueItems(styles, prefix, fixedPrefix, repository, list, xmlCachedCompletion);
            } else {
                addAttributeItems(styles, fullPrefix, fixedPrefix, repository, list, xmlCachedCompletion);
            }
        }
        return xmlCachedCompletion;
    }

    private void addTagItems(XmlRepository repository, String prefix, CompletionList list, XmlCachedCompletion xmlCachedCompletion) {
        xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_TAG);
        xmlCachedCompletion.setFilterPrefix(prefix);
        xmlCachedCompletion.setFilter((item, pre) -> {
            String prefixSet = pre;
            if (pre.startsWith("<")) {
                prefixSet = pre.substring(1);
            }

            if (prefixSet.contains(".")) {
                if (FuzzySearch.partialRatio(prefixSet, item.detail) >= 80) {
                    return true;
                }
            } else {
                if (FuzzySearch.partialRatio(prefixSet, item.label) >= 80) {
                    return true;
                }
            }

            String className = item.detail + "." + item.label;
            return FuzzySearch.partialRatio(prefixSet, className) >= 30;

        });
        for (Map.Entry<String, JavaClass> entry :
                repository.getJavaViewClasses().entrySet()) {
            CompletionItem item = new CompletionItem();
            item.label = StyleUtils.getSimpleName(entry.getKey());
            item.detail = entry.getValue().getPackageName();
            item.iconKind = DrawableKind.Class;
            item.commitText = "<" + entry.getValue().getClassName();
            item.cursorOffset = item.commitText.length();
            list.items.add(item);
        }
    }

    private void addAttributeItems(Set<DeclareStyleable> styles, String fullPrefix, String fixedPrefix, XmlRepository repository, CompletionList list, XmlCachedCompletion xmlCachedCompletion) {
        boolean shouldShowNamespace = !fixedPrefix.contains(":");

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
                if (it.label.contains(":")) {
                    if (!it.label.startsWith(pre)) {
                        return false;
                    }
                    it.label = it.label.substring(it.label.indexOf(':') + 1);
                }
            }
            if (it.label.startsWith(pre)) {
                return true;
            }

            String labelPrefix = getAttributeNameFromPrefix(it.label);
            String prePrefix = getAttributeNameFromPrefix(pre);
            return FuzzySearch.partialRatio(labelPrefix, prePrefix) >= 70;
        });
    }

    private void addAttributeValueItems(Set<DeclareStyleable> styles, String prefix, String fixedPrefix, XmlRepository repository, CompletionList list, XmlCachedCompletion xmlCachedCompletion) {
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
    }

    private boolean isInAttributeTag(String contents, int index) {
        XMLLexer lexer = new XMLLexer(CharStreams.fromString(contents));
        Token token;
        while ((token = lexer.nextToken()) != null) {
            int start = token.getStartIndex();
            int end = token.getStopIndex();

            if (start <= index && index <= end) {
                return token.getType() == XMLLexer.ATTRIBUTE;
            }

            if (end > index) {
                break;
            }
        }
        return false;
    }
}
