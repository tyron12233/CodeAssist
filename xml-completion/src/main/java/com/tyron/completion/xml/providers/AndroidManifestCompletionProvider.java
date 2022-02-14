package com.tyron.completion.xml.providers;

import static com.tyron.completion.xml.util.XmlUtils.fullIdentifier;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeItem;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeNameFromPrefix;
import static com.tyron.completion.xml.util.XmlUtils.getElementNode;
import static com.tyron.completion.xml.util.XmlUtils.isEndTag;
import static com.tyron.completion.xml.util.XmlUtils.isInAttributeValue;
import static com.tyron.completion.xml.util.XmlUtils.isIncrementalCompletion;
import static com.tyron.completion.xml.util.XmlUtils.isTag;
import static com.tyron.completion.xml.util.XmlUtils.newPullParser;
import static com.tyron.completion.xml.util.XmlUtils.partialIdentifier;

import androidx.annotation.NonNull;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.util.PositionXmlParser;
import com.tyron.completion.CompletionParameters;
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
import com.tyron.completion.xml.model.XmlCompletionType;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.util.AndroidAttributeUtils;
import com.tyron.completion.xml.util.AndroidXmlTagUtils;
import com.tyron.completion.xml.util.AttributeValueUtils;
import com.tyron.completion.xml.util.DOMUtils;
import com.tyron.completion.xml.util.StyleUtils;
import com.tyron.completion.xml.util.XmlUtils;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMParser;
import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class AndroidManifestCompletionProvider extends LayoutXmlCompletionProvider {

    public AndroidManifestCompletionProvider() {

    }

    @Override
    public boolean accept(File file) {
        return file.isFile() && "AndroidManifest.xml".equals(file.getName());
    }

    @NonNull
    @Override
    protected CompletionList.Builder completeInternal(Project project,
                                                      AndroidModule module,
                                                      XmlRepository repository,
                                                      DOMDocument parsed,
                                                      String prefix,
                                                      XmlCompletionType completionType,
                                                      ResourceNamespace namespace,
                                                      long index) {
        CompletionList.Builder builder = CompletionList.builder(prefix);
        switch (completionType) {
            case TAG:
                AndroidXmlTagUtils.addManifestTagItems(repository, prefix, builder);
                break;
            case ATTRIBUTE:
                AndroidAttributeUtils.addManifestAttributes(builder, repository.getRepository(),
                                                            parsed.findNodeAt((int) index),
                                                            namespace);
                break;
            case ATTRIBUTE_VALUE:
                DOMAttr attr = parsed.findAttrAt((int) index);
                AttributeValueUtils.addManifestValueItems(repository, prefix, (int) index, attr,
                                                          namespace, builder);
        }
        return builder;
    }
}