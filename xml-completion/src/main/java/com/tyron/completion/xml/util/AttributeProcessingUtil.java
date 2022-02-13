package com.tyron.completion.xml.util;

import static com.tyron.builder.compiler.manifest.SdkConstants.TABLE_ROW;
import static com.tyron.builder.compiler.manifest.SdkConstants.VIEW_GROUP;

import androidx.annotation.NonNull;

import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.completion.xml.repository.ResourceItem;
import com.tyron.completion.xml.repository.ResourceRepository;
import com.tyron.completion.xml.repository.api.ResourceNamespace;

import org.eclipse.lemminx.dom.DOMElement;
import org.openjdk.source.tree.ClassTree;

import java.util.List;

public class AttributeProcessingUtil {

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

    private static void registerAttributesFromSuffixedStyleableForNamespace(
            @NonNull ResourceRepository repository,
            @NonNull DOMElement element,
            @NonNull ResourceNamespace namespace) {
        List<ResourceItem> resources = repository.getResources(namespace, ResourceType.STYLEABLE, (item -> {
            String name = item.getName();
            return name.endsWith("_Layout") ||
                   name.endsWith("_LayoutParams") ||
                   name.equals("ViewGroup_MarginLayout") ||
                   name.equals("TableRow_Cell");
        }));
        for (ResourceItem resource : resources) {
            String name = resource.getName();
            int indexOfLastUnderscore = name.lastIndexOf('_');
            String viewName = name.substring(0, indexOfLastUnderscore);
        }
    }
}
