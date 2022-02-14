package com.tyron.completion.xml.providers;

import static com.tyron.completion.xml.util.XmlUtils.fullIdentifier;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeItem;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeNameFromPrefix;
import static com.tyron.completion.xml.util.XmlUtils.getElementNode;
import static com.tyron.completion.xml.util.XmlUtils.isEndTag;
import static com.tyron.completion.xml.util.XmlUtils.isInAttributeValue;
import static com.tyron.completion.xml.util.XmlUtils.isIncrementalCompletion;
import static com.tyron.completion.xml.util.XmlUtils.isTag;
import static com.tyron.completion.xml.util.XmlUtils.partialIdentifier;

import android.annotation.SuppressLint;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.common.collect.ListMultimap;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.XmlIndexProvider;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.insert.AttributeInsertHandler;
import com.tyron.completion.xml.insert.LayoutTagInsertHandler;
import com.tyron.completion.xml.insert.ValueInsertHandler;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.DeclareStyleable;
import com.tyron.completion.xml.model.XmlCachedCompletion;
import com.tyron.completion.xml.model.XmlCompletionType;
import com.tyron.completion.xml.repository.ResourceItem;
import com.tyron.completion.xml.repository.api.AttrResourceValue;
import com.tyron.completion.xml.repository.api.AttributeFormat;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.util.AndroidResourcesUtils;
import com.tyron.completion.xml.util.AttributeValueUtils;
import com.tyron.completion.xml.util.DOMUtils;
import com.tyron.completion.xml.util.StyleUtils;
import com.tyron.completion.xml.util.XmlUtils;

import org.apache.bcel.classfile.JavaClass;
import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager;
import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.xdrop.fuzzywuzzy.FuzzySearch;

@SuppressLint("NewApi")
public class LayoutXmlCompletionProvider extends CompletionProvider {

    private static final String EXTENSION = ".xml";

    private CachedCompletion mCachedCompletion;

    public LayoutXmlCompletionProvider() {

    }

    @Override
    public boolean accept(File file) {
        return file.isFile() && AndroidResourcesUtils.isLayoutXMLFile(file);
    }

    @Override
    public CompletionList complete(CompletionParameters params) {

        if (!(params.getModule() instanceof AndroidModule)) {
            return CompletionList.EMPTY;
        }

        String contents = params.getContents();
        String prefix = params.getPrefix();
        String partialIdentifier = partialIdentifier(contents, (int) params.getIndex());

        if (isIncrementalCompletion(mCachedCompletion, params)) {
            CompletionList completionList = mCachedCompletion.getCompletionList();
            if (!completionList.items.isEmpty()) {
                return CompletionList.copy(completionList, prefix);
            }
        }
        try {
            CompletionList.Builder builder =
                    completeInternal(params.getProject(), (AndroidModule) params.getModule(),
                                     params.getFile(), contents, prefix, params.getLine(),
                                     params.getColumn(), params.getIndex());
            if (builder == null) {
                return CompletionList.EMPTY;
            }
            CompletionList build = builder.build();
            mCachedCompletion = new CachedCompletion(params.getFile(),
                                                     params.getLine(),
                                                     params.getColumn(),
                                                     builder.getPrefix(), build);
            return build;
        } catch (XmlPullParserException | IOException | ParserConfigurationException | SAXException e) {
            e.printStackTrace();
        }

        return CompletionList.EMPTY;
    }

    private void sort(List<CompletionItem> items, String filterPrefix) {
        items.sort(Comparator.comparingInt(it -> {
            if (it.label.equals(filterPrefix)) {
                return 100;
            }
            return FuzzySearch.ratio(it.label, filterPrefix);
        }));
        Collections.reverse(items);
    }


    @Nullable
    @RequiresApi(api = Build.VERSION_CODES.N)
    private CompletionList.Builder completeInternal(Project project, AndroidModule module, File file
            , String contents, String prefix, int line, int column, long index) throws XmlPullParserException, IOException, ParserConfigurationException, SAXException {
        XmlRepository repository = getRepository(project, module);

        ResourceNamespace namespace = ResourceNamespace.fromPackageName(module.getPackageName());
        DOMDocument parsed = DOMParser.getInstance()
                .parse(contents, namespace.getXmlNamespaceUri(), new URIResolverExtensionManager());
        DOMNode node = parsed.findNodeAt((int) index);

        String parentTag = "";
        String tag = "";
        Element ownerNode = getElementNode(node);
        if (ownerNode != null) {
            parentTag = ownerNode.getParentNode() == null ? "" : ownerNode.getParentNode()
                    .getNodeName();
            tag = ownerNode.getTagName();
        }

        XmlCompletionType completionType = XmlUtils.getCompletionType(parsed, index);
        if (completionType == XmlCompletionType.UNKNOWN) {
            return null;
        }

        String fixedPrefix = XmlUtils.getPrefix(parsed, index, completionType);
        if (fixedPrefix == null) {
            return null;
        }

        CompletionList.Builder builder = CompletionList.builder(fixedPrefix);
        switch (completionType) {
            case TAG:
            case ATTRIBUTE:
            case ATTRIBUTE_VALUE:
                DOMAttr attr = parsed.findAttrAt((int) index);
                String uri = DOMUtils.lookupPrefix(attr);
                ResourceNamespace resourceNamespace = ResourceNamespace.fromNamespaceUri(uri);
                AttributeValueUtils.addValueItems(project, module,
                                                  fixedPrefix,
                                                  repository,
                                                  attr,
                                                  resourceNamespace,
                                                  namespace, builder);
        }
        return builder;
    }

    private XmlRepository getRepository(Project project, AndroidModule module) throws IOException {
        XmlIndexProvider indexProvider = CompilerService.getInstance()
                .getIndex(XmlIndexProvider.KEY);
        XmlRepository repository = indexProvider.get(project, module);
        repository.initialize(module);
        return repository;
    }

    private void addTagItems(XmlRepository repository, String prefix, CompletionList list,
                             XmlCachedCompletion xmlCachedCompletion) {
        xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_TAG);
        xmlCachedCompletion.setFilterPrefix(prefix);
        xmlCachedCompletion.setFilter((item, pre) -> {
            String prefixSet = pre;

            if (pre.startsWith("</")) {
                prefixSet = pre.substring(2);
            } else if (pre.startsWith("<")) {
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
        for (Map.Entry<String, JavaClass> entry : repository.getJavaViewClasses()
                .entrySet()) {
            CompletionItem item = new CompletionItem();
            String commitPrefix = "<";
            if (prefix.startsWith("</")) {
                commitPrefix = "</";
            }
            boolean useFqn = prefix.contains(".");
            if (!entry.getKey()
                    .startsWith("android.widget")) {
                useFqn = true;
            }
            item.label = StyleUtils.getSimpleName(entry.getKey());
            item.detail = entry.getValue()
                    .getPackageName();
            item.iconKind = DrawableKind.Class;
            item.commitText = commitPrefix +
                              (useFqn ? entry.getValue()
                                      .getClassName() : StyleUtils.getSimpleName(entry.getValue()
                                                                                         .getClassName()));
            item.cursorOffset = item.commitText.length();
            item.setInsertHandler(new LayoutTagInsertHandler(entry.getValue(), item));
            list.items.add(item);
        }
    }

    private void addAttributeItems(Set<DeclareStyleable> styles, String fullPrefix,
                                   String fixedPrefix, XmlRepository repository,
                                   CompletionList list, XmlCachedCompletion xmlCachedCompletion) {
        boolean shouldShowNamespace = !fixedPrefix.contains(":");

        for (DeclareStyleable style : styles) {
            for (AttributeInfo attributeInfo : style.getAttributeInfos()) {
                if (attributeInfo.getFormats() == null ||
                    attributeInfo.getFormats()
                            .isEmpty()) {
                    AttributeInfo extra = repository.getExtraAttribute(attributeInfo.getName());
                    if (extra != null) {
                        attributeInfo = extra;
                    }
                }
                CompletionItem item =
                        getAttributeItem(repository, attributeInfo, shouldShowNamespace,
                                         fullPrefix);
                item.setInsertHandler(new AttributeInsertHandler(item));
                list.items.add(item);
            }
        }
        xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_ATTRIBUTE);
        xmlCachedCompletion.setFilterPrefix(fixedPrefix);
        xmlCachedCompletion.setFilter((it, pre) -> {
            if (pre.contains(":")) {
                if (pre.endsWith(":")) {
                    return true;
                }
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

    private void addAttributeValueItems(AttrResourceValue styles, String prefix,
                                        String fixedPrefix, XmlRepository repository,
                                        CompletionList list,
                                        XmlCachedCompletion xmlCachedCompletion) {
//        String attributeName = getAttributeNameFromPrefix(fixedPrefix);
//        String namespace = "";
//        if (fixedPrefix.contains(":")) {
//            namespace = fixedPrefix.substring(0, fixedPrefix.indexOf(':'));
//            if (namespace.contains("=")) {
//                namespace = namespace.substring(0, namespace.indexOf('='));
//            }
//        }
//        if (!namespace.equals(attributeInfo.getNamespace())) {
//            continue;
//        }
//        if (!attributeName.isEmpty()) {
//            if (!attributeName.equals(attributeInfo.getName())) {
//                continue;
//            }
//        }
        if (styles == null) {
            return;
        }

        Set<AttributeFormat> formats = styles.getFormats();
        if (!formats.contains(AttributeFormat.ENUM) && !formats.contains(AttributeFormat.FLAGS)) {
            for (AttributeFormat format : formats) {
                Set<ResourceType> matchingTypes = format.getMatchingTypes();
                for (ResourceType matchingType : matchingTypes) {
                    ListMultimap<String, ResourceItem> resources = repository.getRepository()
                            .getResources(ResourceNamespace.RES_AUTO, matchingType);
                    for (ResourceItem value : resources.values()) {
                        CompletionItem item = new CompletionItem();
                        item.action = CompletionItem.Kind.NORMAL;
                        item.label = value.getName();
                        item.commitText = value.getQualifiedNameWithType();
                        item.iconKind = DrawableKind.Attribute;
                        item.cursorOffset = item.commitText.length();
                        item.detail = "Attribute";
                        item.setInsertHandler(new ValueInsertHandler(styles, item));
                        list.items.add(item);
                    }
                }
            }
            return;
        }
        for (String value : styles.getAttributeValues()
                .keySet()) {
            CompletionItem item = new CompletionItem();
            item.action = CompletionItem.Kind.NORMAL;
            item.label = value;
            item.commitText = value;
            item.iconKind = DrawableKind.Attribute;
            item.cursorOffset = value.length();
            item.detail = "Attribute";
            item.setInsertHandler(new ValueInsertHandler(styles, item));
            list.items.add(item);
        }
        xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_ATTRIBUTE_VALUE);
        xmlCachedCompletion.setFilterPrefix(prefix);
        xmlCachedCompletion.setFilter((item, pre) -> item.label.startsWith(pre));
    }
}
