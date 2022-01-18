package com.tyron.completion.xml.providers;

import static com.tyron.completion.xml.util.XmlUtils.*;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeItem;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeNameFromPrefix;
import static com.tyron.completion.xml.util.XmlUtils.partialIdentifier;

import android.Manifest;
import android.util.Pair;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.XmlIndexProvider;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.DeclareStyleable;
import com.tyron.completion.xml.model.XmlCachedCompletion;
import com.tyron.completion.xml.util.AndroidResourcesUtils;
import com.tyron.completion.xml.util.StyleUtils;
import com.tyron.completion.xml.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class AndroidManifestCompletionProvider extends CompletionProvider {

    private static final Map<String, String> sManifestTagMappings = new HashMap<>();

    static {
        sManifestTagMappings.put("manifest", "AndroidManifest");
        sManifestTagMappings.put("application", "AndroidManifestApplication");
        sManifestTagMappings.put("permission", "AndroidManifestPermission");
        sManifestTagMappings.put("permission-group", "AndroidManifestPermissionGroup");
        sManifestTagMappings.put("permission-tree", "AndroidManifestPermissionTree");
        sManifestTagMappings.put("uses-permission", "AndroidManifestUsesPermission");
        sManifestTagMappings.put("required-feature", "AndroidManifestRequiredFeature");
        sManifestTagMappings.put("required-not-feature", "AndroidManifestRequiredNotFeature");
        sManifestTagMappings.put("uses-configuration", "AndroidManifestUsesConfiguration");
        sManifestTagMappings.put("uses-feature", "AndroidManifestUsesFeature");
        sManifestTagMappings.put("feature-group", "AndroidManifestFeatureGroup");
        sManifestTagMappings.put("uses-sdk", "AndroidManifestUsesSdk");
        sManifestTagMappings.put("extension-sdk", "AndroidManifestExtensionSdk");
        sManifestTagMappings.put("library", "AndroidManifestLibrary");
        sManifestTagMappings.put("static-library", "AndroidManifestStaticLibrary");
        sManifestTagMappings.put("uses-libraries", "AndroidManifestUsesLibrary");
        sManifestTagMappings.put("uses-native-library", "AndroidManifestUsesNativeLibrary");
        sManifestTagMappings.put("uses-static-library", "AndroidManifestUsesStaticLibrary");
        sManifestTagMappings.put("additional-certificate", "AndroidManifestAdditionalCertificate");
        sManifestTagMappings.put("uses-package", "AndroidManifestUsesPackage");
        sManifestTagMappings.put("supports-screens", "AndroidManifestSupportsScreens");
        sManifestTagMappings.put("processes", "AndroidManifestProcesses");
        sManifestTagMappings.put("process", "AndroidManifestProcess");
        sManifestTagMappings.put("deny-permission", "AndroidManifestDenyPermission");
        sManifestTagMappings.put("allow-permission", "AndroidManifestAllowPermission");
        sManifestTagMappings.put("provider", "AndroidManifestProvider");
        sManifestTagMappings.put("grant-uri-permission", "AndroidManifestGrantUriPermission");
        sManifestTagMappings.put("path-permission", "AndroidManifestPathPermission");
        sManifestTagMappings.put("service", "AndroidManifestService");
        sManifestTagMappings.put("receiver", "AndroidManifestReceiver");
        sManifestTagMappings.put("activity", "AndroidManifestActivity");
        sManifestTagMappings.put("activity-alias", "AndroidManifestActivityAlias");
        sManifestTagMappings.put("meta-data", "AndroidManifestMetaData");
        sManifestTagMappings.put("property", "AndroidManifestProperty");
        sManifestTagMappings.put("intent-filter", "AndroidManifestIntentFilter");
        sManifestTagMappings.put("action", "AndroidManifestAction");
        sManifestTagMappings.put("data", "AndroidManifestData");
        sManifestTagMappings.put("category", "AndroidManifestCategory");
        sManifestTagMappings.put("instrumentation", "AndroidManifestInstrumentation");
        sManifestTagMappings.put("screen", "AndroidManifestCompatibleScreensScreen");
        sManifestTagMappings.put("input-type", "AndroidManifestSupportsInputType");
        sManifestTagMappings.put("layout", "AndroidManifestLayout");
        sManifestTagMappings.put("restrict-update", "AndroidManifestRestrictUpdate");
        sManifestTagMappings.put("uses-split", "AndroidManifestUsesSplit");

    }

    private static String getTag(String tag) {
        return sManifestTagMappings.get(tag);
    }

    private XmlCachedCompletion mCachedCompletion;

    public AndroidManifestCompletionProvider() {

    }

    @Override
    public boolean accept(File file) {
        return file.isFile() && "AndroidManifest.xml".equals(file.getName());
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
            XmlCachedCompletion list = completeInternal(project, (AndroidModule) module, file,
                    contents, prefix, line, column, index);
            mCachedCompletion = list;
            return list.getCompletionList();
        } catch (XmlPullParserException e) {
            e.printStackTrace();
        }

        return CompletionList.EMPTY;
    }

    public XmlCachedCompletion completeInternal(Project project, Module module, File file,
                                                String contents, String prefix, int line,
                                                int column, long index) throws XmlPullParserException {
        CompletionList list = new CompletionList();
        XmlCachedCompletion xmlCachedCompletion = new XmlCachedCompletion(file, line, column,
                prefix, list);
        String fixedPrefix = partialIdentifier(contents, (int) index);
        String fullPrefix = fullIdentifier(contents, (int) index);

        XmlPullParser parser = newPullParser();
        parser.setInput(new StringReader(contents));

        XmlIndexProvider indexProvider =
                CompilerService.getInstance().getIndex(XmlIndexProvider.KEY);
        XmlRepository repository = indexProvider.get(project, module);
        repository.initialize((AndroidModule) module);
        Map<String, DeclareStyleable> manifestAttrs = repository.getManifestAttrs();

        Pair<String, String> tagAtPosition = getTagAtPosition(parser, line, column);
        String currentTag = getTag(tagAtPosition.second);
        if (currentTag == null) {
            return xmlCachedCompletion;
        }

        Set<DeclareStyleable> styles = StyleUtils.getStyles(manifestAttrs, currentTag);

        if (prefix.startsWith("<")) {
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
            for (String s : sManifestTagMappings.keySet()) {
                CompletionItem item = new CompletionItem();
                item.label = s;
                item.commitText = "<" + s;
                item.cursorOffset = item.commitText.length();
                item.iconKind = DrawableKind.Package;
                item.detail = "Tag";
                list.items.add(item);
            }

        } else {
            if (isInAttributeValue(contents, (int) index)) {
                addAttributeValueItems(styles, repository, prefix, fixedPrefix, list,
                        xmlCachedCompletion);
            } else {
                addAttributeItems(styles, fullPrefix, fixedPrefix, repository, list,
                        xmlCachedCompletion);
            }
        }
        return xmlCachedCompletion;
    }

    private void addAttributeItems(Set<DeclareStyleable> styles, String fullPrefix,
                                   String fixedPrefix, XmlRepository repository,
                                   CompletionList list, XmlCachedCompletion xmlCachedCompletion) {
        boolean shouldShowNamespace = !fixedPrefix.contains(":");

        Set<AttributeInfo> attributeInfos = new HashSet<>();

        for (DeclareStyleable style : styles) {
            attributeInfos.addAll(style.getAttributeInfosWithParents(repository));
        }

        for (AttributeInfo attributeInfo : attributeInfos) {
            CompletionItem item = getAttributeItem(repository, attributeInfo, shouldShowNamespace
                    , fixedPrefix + fullPrefix);
            list.items.add(item);
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

    private void addAttributeValueItems(Set<DeclareStyleable> styles, XmlRepository repository,
                                        String prefix, String fixedPrefix, CompletionList list,
                                        XmlCachedCompletion xmlCachedCompletion) {
        Set<AttributeInfo> attributeInfos = new HashSet<>();
        for (DeclareStyleable style : styles) {
            attributeInfos.addAll(style.getAttributeInfosWithParents(repository));
        }

        String attributeName = getAttributeNameFromPrefix(fixedPrefix);
        String namespace = "";
        if (fixedPrefix.contains(":")) {
            namespace = fixedPrefix.substring(0, fixedPrefix.indexOf(':'));
            if (namespace.contains("=")) {
                namespace = namespace.substring(0, namespace.indexOf('='));
            }
        }

        for (AttributeInfo attributeInfo : attributeInfos) {
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
        xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_ATTRIBUTE_VALUE);
        xmlCachedCompletion.setFilterPrefix(prefix);
        xmlCachedCompletion.setFilter((item, pre) -> item.label.startsWith(pre));
    }
}
