package com.tyron.layoutpreview2.attr;

import android.view.View;

import androidx.annotation.NonNull;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.tyron.xml.completion.repository.Repository;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.util.DOMUtils;
import com.tyron.layoutpreview2.EditorContext;
import com.tyron.layoutpreview2.util.AttributeUtils;

import org.eclipse.lemminx.dom.DOMAttr;

public abstract class BaseAttributeApplier implements AttributeApplier {

    public BaseAttributeApplier() {
        registerAttributeProcessors();
    }

    @FunctionalInterface
    public interface AttributeConsumer<V, A> {
        void apply(V view, A attribute);
    }

    private final Table<ResourceNamespace, String, AttributeConsumer<? super View, DOMAttr>>
            mTable = HashBasedTable.create();

    public abstract void registerAttributeProcessors();

    public void registerAttributeProcessor(ResourceNamespace namespace,
                                           String name,
                                           AttributeConsumer<View, DOMAttr> value) {
        registerAttributeProcessor(namespace, name, View.class, value);
    }

    public <T extends View> void registerAttributeProcessor(ResourceNamespace namespace,
                                                            String name,
                                                            Class<T> clazz,
                                                            AttributeConsumer<T, DOMAttr> value) {
        //noinspection unchecked
        mTable.put(namespace, name, (view, attribute) -> value.apply((T) view, attribute));
    }

    public <T extends View> void registerStringAttributeProcessor(ResourceNamespace attrNs,
                                                                  String attrName,
                                                                  Class<T> clazz,
                                                                  AttributeConsumer<T, String> value) {
        mTable.put(attrNs, attrName, (view, attribute) -> {
            EditorContext context = EditorContext.getEditorContext(view.getContext());
            final Repository repository = context.getRepository();
            final ResourceNamespace.Resolver resolver =
                    DOMUtils.getNamespaceResolver(attribute.getOwnerDocument());
            final ResourceNamespace documentNs =
                    DOMUtils.getNamespace(attribute.getOwnerDocument());
            String attributeValue = attribute.getValue();
            if (attributeValue != null && documentNs != null) {
                String resolvedValue = AttributeUtils
                        .resolve(attributeValue, documentNs, resolver, repository);
                //noinspection unchecked
                value.apply((T) view, resolvedValue);
                return;
            }

            //noinspection unchecked
            value.apply((T) view, attributeValue);
        });
    }

    @Override
    public void apply(@NonNull View view, @NonNull DOMAttr attr) {
        String prefix = DOMUtils.getPrefix(attr);
        if (!attr.getName().contains(":")) {
            prefix = null;
        }
        ResourceNamespace namespace = null;
        if (prefix != null) {
            final ResourceNamespace.Resolver namespaceResolver =
                    DOMUtils.getNamespaceResolver(attr.getOwnerDocument());
            final String uri = namespaceResolver.prefixToUri(prefix);
            if (uri != null) {
                namespace = ResourceNamespace.fromNamespaceUri(uri);
            }
        }

        final AttributeConsumer<? super View, DOMAttr> consumer =
                mTable.get(namespace, attr.getLocalName());
        if (consumer != null) {
            consumer.apply(view, attr);
        }
    }
}
