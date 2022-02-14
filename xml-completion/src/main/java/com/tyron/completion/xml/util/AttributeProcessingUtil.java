package com.tyron.completion.xml.util;

import static com.tyron.builder.compiler.manifest.SdkConstants.TABLE_ROW;
import static com.tyron.builder.compiler.manifest.SdkConstants.VIEW_GROUP;

import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.compiler.manifest.configuration.FolderConfiguration;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.completion.xml.repository.ResourceItem;
import com.tyron.completion.xml.repository.ResourceRepository;
import com.tyron.completion.xml.repository.api.AttrResourceValue;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;
import com.tyron.completion.xml.repository.api.StyleableResourceValue;

import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AttributeProcessingUtil {

    private static final FolderConfiguration DEFAULT = FolderConfiguration.createDefault();

    public static List<AttrResourceValue> getTagAttributes(@NonNull ResourceRepository repository
            , @NonNull DOMNode node, ResourceNamespace namespace) {
        String tagName = getSimpleName(node.getNodeName());
        Set<String> classes = StyleUtils.getClasses(tagName);
        return classes.stream()
                .flatMap(it -> getAttributes(repository, it, namespace).stream())
                .collect(Collectors.toList());
    }

    public static List<AttrResourceValue> getAttributes(@NonNull ResourceRepository repository,
                                                        @NonNull String tag,
                                                        @NonNull ResourceNamespace namespace) {
        String modified = tag;
        if (VIEW_GROUP.equals(modified)) {
            modified = "ViewGroup_Layout";
        }
        Set<String> names = ImmutableSet.of(modified, getLayoutStyleablePrimary(tag),
                                            getLayoutStyleableSecondary(tag));
        return names.stream()
                .map(it -> getResourceValue(repository, it, namespace))
                .filter(it -> it instanceof StyleableResourceValue)
                .flatMap(it -> ((StyleableResourceValue) it).getAllAttributes().stream())
                .collect(Collectors.toList());
    }

    public static AttrResourceValue getLayoutAttributeFromNode(@NonNull ResourceRepository repository, @NonNull DOMElement node, @NonNull String attributeName, @NonNull ResourceNamespace namespace) {
        String tagName = getSimpleName(node.getTagName());
        Set<String> classes = StyleUtils.getClasses(tagName);
        // get all the attributes of the superclasses of the view
        for (String aClass : classes) {
            String modified = aClass;
            if (aClass.equals(VIEW_GROUP)) {
                modified = "ViewGroup_Layout";
            }
            Set<String> names = ImmutableSet.of(modified, getLayoutStyleablePrimary(aClass),
                                                getLayoutStyleableSecondary(aClass));
            for (String name : names) {

                ResourceValue resourceValue = getResourceValue(repository, name, namespace);
                if (resourceValue == null) {
                    continue;
                }

                StyleableResourceValue styleable = (StyleableResourceValue) resourceValue;
                AttrResourceValue previous = null;
                for (AttrResourceValue attribute : styleable.getAllAttributes()) {
                    if (attributeName.equals(attribute.getName())) {
                        AttrResourceValue attributeResourceValue =
                                getAttributeResourceValue(repository, attribute);
                        if (attributeResourceValue != null) {
                            return attributeResourceValue;
                        } else {
                            previous = attribute;
                        }
                    }
                }
            }
        }

        DOMElement parentElement = node.getParentElement();
        if (parentElement == null) {
            return null;
        }
        tagName = parentElement.getTagName();
        if (tagName == null) {
            return null;
        }

        tagName = getSimpleName(tagName);
        classes = StyleUtils.getClasses(tagName);
        // get all the attributes of the superclasses of the view
        for (String aClass : classes) {
            String modified = aClass;
            if (aClass.equals(VIEW_GROUP)) {
                modified = "ViewGroup_Layout";
            }
            Set<String> names = ImmutableSet.of(modified, getLayoutStyleablePrimary(aClass),
                                                getLayoutStyleableSecondary(aClass));
            for (String name : names) {
                ResourceValue resourceValue = getResourceValue(repository, name, namespace);
                if (resourceValue == null) {
                    continue;
                }

                StyleableResourceValue styleable = (StyleableResourceValue) resourceValue;
                AttrResourceValue previous = null;
                for (AttrResourceValue attribute : styleable.getAllAttributes()) {
                    if (attributeName.equals(attribute.getName())) {
                        AttrResourceValue attributeResourceValue =
                                getAttributeResourceValue(repository, attribute);
                        if (attributeResourceValue != null) {
                            return attributeResourceValue;
                        } else {
                            previous = attribute;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static ResourceValue getResourceValue(@NonNull ResourceRepository repository,
                                                  String name, ResourceNamespace namespace) {
        ResourceValue value = null;
        try {
            value = repository.getValue(
                    ResourceReference.styleable(ResourceNamespace.ANDROID, name));
        } catch (Resources.NotFoundException ignored) {

        }

        if (value == null) {
            try {
                value = repository.getValue(ResourceReference.styleable(namespace, name));
            } catch (Resources.NotFoundException ignored) {

            }
        }

        return value;
    }

    private static AttrResourceValue getAttributeResourceValue(@NonNull ResourceRepository repository, @NonNull AttrResourceValue value) {
        if (value.getFormats()
                    .isEmpty() &&
            value.getAttributeValues()
                    .isEmpty()) {
            try {
                return (AttrResourceValue) repository.getValue(
                        ResourceReference.attr(value.getNamespace(), value.getName()));
            } catch (Resources.NotFoundException ignored) {
                return null;
            }
        }

        return value;
    }

    private static void registerAttributesForClassAndSuperclasses() {

    }

    public static void processXmlAttributes(@NonNull DOMElement element,
                                            @NonNull ResourceRepository repository) {
        String tagName = element.getTagName();

    }

    /**
     * Returns the expected styleable name for the layout attributes defined by the simple name
     */
    public static String getLayoutStyleablePrimary(@NonNull String simpleName) {
        switch (simpleName) {
            case VIEW_GROUP:
                return "ViewGroup_MarginLayout";
            case TABLE_ROW:
                return "TableRow_Cell";
            default:
                return simpleName + "_Layout";
        }
    }

    public static String getLayoutStyleableSecondary(@NonNull String simpleName) {
        return simpleName + "_LayoutParams";
    }

    private static List<ResourceItem> getAttributesFromSuffixedStyleableForNamespace(@NonNull ResourceRepository repository, @NonNull DOMElement element, @NonNull ResourceNamespace namespace) {
        return repository.getResources(namespace, ResourceType.STYLEABLE, (item -> {
            String name = item.getName();
            return name.endsWith("_Layout") ||
                   name.endsWith("_LayoutParams") ||
                   name.equals("ViewGroup_MarginLayout") ||
                   name.equals("TableRow_Cell");
        }));
    }

    private static String getSimpleName(String qualifiedName) {
        int index = qualifiedName.lastIndexOf('.');
        if (index == -1) {
            return qualifiedName;
        }
        return qualifiedName.substring(index);
    }
}
