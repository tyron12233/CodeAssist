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

import androidx.annotation.NonNull;
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
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.util.AndroidAttributeUtils;
import com.tyron.completion.xml.util.AndroidResourcesUtils;
import com.tyron.completion.xml.util.AndroidXmlTagUtils;
import com.tyron.completion.xml.util.AttributeProcessingUtil;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.xdrop.fuzzywuzzy.FuzzySearch;

@SuppressLint("NewApi")
public class LayoutXmlCompletionProvider extends CompletionProvider {

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

        try {
            XmlRepository repository =
                    getRepository(params.getProject(), (AndroidModule) params.getModule());

            String contents = params.getContents();

            ResourceNamespace namespace =
                    ResourceNamespace.fromPackageName(((AndroidModule) params.getModule()).getPackageName());
            DOMDocument parsed = DOMParser.getInstance()
                    .parse(contents, namespace.getXmlNamespaceUri(),
                           new URIResolverExtensionManager());
            DOMNode node = parsed.findNodeAt((int) params.getIndex());

            XmlCompletionType completionType =
                    XmlUtils.getCompletionType(parsed, params.getIndex());
            if (completionType == XmlCompletionType.UNKNOWN) {
                return CompletionList.EMPTY;
            }

            String prefix = XmlUtils.getPrefix(parsed, params.getIndex(), completionType);
            if (prefix == null) {
                return CompletionList.EMPTY;
            }

            if (isIncrementalCompletion(mCachedCompletion, params)) {
                CompletionList completionList = mCachedCompletion.getCompletionList();
                if (!completionList.items.isEmpty()) {
                    return CompletionList.copy(completionList, prefix);
                }
            }

            CompletionList.Builder builder =
                    completeInternal(params.getProject(), ((AndroidModule) params.getModule()),
                                     repository, parsed, prefix, completionType, namespace,
                                     params.getIndex());
            CompletionList build = builder.build();
            mCachedCompletion =
                    new CachedCompletion(params.getFile(), params.getLine(), params.getColumn(),
                                         builder.getPrefix(), build);
            return build;
        } catch (XmlPullParserException | IOException | ParserConfigurationException | SAXException e) {
            e.printStackTrace();
        }

        return CompletionList.EMPTY;
    }

    @NonNull
    @RequiresApi(api = Build.VERSION_CODES.N)
    protected CompletionList.Builder completeInternal(Project project, AndroidModule module,
                                                    XmlRepository repository, DOMDocument parsed,
                                                    String prefix,
                                                    XmlCompletionType completionType,
                                                    ResourceNamespace namespace, long index) throws XmlPullParserException, IOException, ParserConfigurationException, SAXException {

        CompletionList.Builder builder = CompletionList.builder(prefix);
        switch (completionType) {
            case TAG:
                AndroidXmlTagUtils.addTagItems(repository, prefix, builder);
                break;
            case ATTRIBUTE:
                AndroidAttributeUtils.addLayoutAttributes(builder,
                                                          repository.getRepository(),
                                                          parsed.findNodeAt((int) index),
                                                          namespace);
                break;
            case ATTRIBUTE_VALUE:
                DOMAttr attr = parsed.findAttrAt((int) index);
                String uri = DOMUtils.lookupPrefix(attr);
                ResourceNamespace resourceNamespace = ResourceNamespace.fromNamespaceUri(uri);
                AttributeValueUtils.addValueItems(project, module, prefix, (int) index, repository, attr,
                                                  resourceNamespace, namespace, builder);
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
}
