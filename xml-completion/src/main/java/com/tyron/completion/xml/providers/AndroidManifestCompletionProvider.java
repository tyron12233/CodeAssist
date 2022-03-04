package com.tyron.completion.xml.providers;

import androidx.annotation.NonNull;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.model.XmlCompletionType;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.completion.xml.util.AndroidAttributeUtils;
import com.tyron.completion.xml.util.AndroidXmlTagUtils;
import com.tyron.completion.xml.util.AttributeValueUtils;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;

import java.io.File;

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