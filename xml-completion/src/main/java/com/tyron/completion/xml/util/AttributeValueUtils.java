package com.tyron.completion.xml.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.configuration.Configurable;
import com.tyron.builder.compiler.manifest.configuration.FolderConfiguration;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.insert.ValueInsertHandler;
import com.tyron.xml.completion.repository.Repository;
import com.tyron.xml.completion.repository.ResourceItem;
import com.tyron.xml.completion.repository.ResourceRepository;
import com.tyron.xml.completion.repository.api.AttrResourceValue;
import com.tyron.xml.completion.repository.api.AttributeFormat;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceValue;
import com.tyron.xml.completion.repository.api.StyleableResourceValue;
import com.tyron.xml.completion.util.DOMUtils;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMElement;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class AttributeValueUtils {

    private static final FolderConfiguration DEFAULT = FolderConfiguration.createDefault();

    public static void addManifestValueItems(@NonNull XmlRepository repository,
                                             @NonNull String prefix,
                                             int index,
                                             @NonNull DOMAttr attr,
                                             @NonNull ResourceNamespace appNamespace,
                                             CompletionList.Builder builder) {
        DOMElement ownerElement = attr.getOwnerElement();
        if (ownerElement == null) {
            return;
        }

        String tagName = ownerElement.getTagName();
        if (tagName == null) {
            return;
        }

        String manifestStyleName = AndroidXmlTagUtils.getManifestStyleName(tagName);
        if (manifestStyleName == null) {
            return;
        }

        String namespace = DOMUtils.lookupPrefix(attr);
        if (namespace == null) {
            return;
        }
        ResourceNamespace resourceNamespace = ResourceNamespace.fromNamespaceUri(namespace);
        if (resourceNamespace == null) {
            return;
        }
        ResourceValue resourceValue =
                AttributeProcessingUtil.getResourceValue(repository.getRepository(),
                                                         manifestStyleName, resourceNamespace);
        if (!(resourceValue instanceof StyleableResourceValue)) {
            return;
        }

        String attributeName = attr.getLocalName();
        StyleableResourceValue styleable = (StyleableResourceValue) resourceValue;
        for (AttrResourceValue attribute : styleable.getAllAttributes()) {
            if (attributeName.equals(attribute.getName())) {
                AttrResourceValue attributeResourceValue =
                        AttributeProcessingUtil.getAttributeResourceValue(
                                repository.getRepository(), attribute);
                if (attributeResourceValue != null) {
                    addValues(attributeResourceValue, repository.getRepository(),
                              attr, index, prefix, appNamespace, builder);
                }
            }
        }
    }

    public static void addValueItems(@NonNull Project project,
                                     @NonNull Module module,
                                     @NonNull String prefix,
                                     int index,
                                     @NonNull XmlRepository repo,
                                     @NonNull DOMAttr attr,
                                     @NonNull ResourceNamespace attrNamespace,
                                     @NonNull ResourceNamespace appNamespace,
                                     @NonNull CompletionList.Builder list) {
        ResourceRepository repository = repo.getRepository();
        AttrResourceValue attribute = AttributeProcessingUtil.getLayoutAttributeFromNode(repository,
                                                                                         attr.getOwnerElement(),
                                                                                         attr.getLocalName(),
                                                                                         attrNamespace);
        if (attribute == null) {
            // attribute is not found
            return;
        }

        addValues(attribute, repository, attr, index, prefix, appNamespace, list);
    }

    private static void addValues(AttrResourceValue attribute,
                                  Repository repository,
                                  DOMAttr attr,
                                  int index,
                                  String prefix,
                                  ResourceNamespace appNamespace,
                                  CompletionList.Builder list) {
        Set<AttributeFormat> formats = attribute.getFormats();
        if (formats.contains(AttributeFormat.FLAGS) || formats.contains(AttributeFormat.ENUM)) {
            if (formats.contains(AttributeFormat.ENUM) && XmlUtils.isFlagValue(attr, index)) {
                return;
            }
            Map<String, Integer> attributeValues = attribute.getAttributeValues();
            for (String flag : attributeValues.keySet()) {
                CompletionItem item =
                        CompletionItem.create(flag, "Value", flag, DrawableKind.Snippet);
                item.setInsertHandler(new ValueInsertHandler(attribute, item));
                item.addFilterText(flag);
                list.addItem(item);
            }
        }

        if (prefix.startsWith("@")) {
            String resourceType = getResourceType(prefix);
            if (resourceType == null) {
                return;
            }

            ResourceNamespace.Resolver resolver =
                    DOMUtils.getNamespaceResolver(attr.getOwnerDocument());

            ResourceNamespace namespace;
            if (resourceType.contains(":")) {
                int i = resourceType.indexOf(':');
                String packagePrefix = resourceType.substring(0, i);
                resourceType = resourceType.substring(i + 1);
                namespace = ResourceNamespace.fromPackageName(packagePrefix);
            } else {
                namespace = appNamespace;
            }

            ResourceType fromTag = ResourceType.fromXmlTagName(resourceType);
            if (fromTag == null) {
                return;
            }

            List<ResourceValue> items = repository.getResources(namespace, fromTag)
                    .asMap()
                    .values()
                    .stream()
                    .map(AttributeValueUtils::getApplicableValue)
                    .map(it -> it != null ? it.getResourceValue() : null)
                    .collect(Collectors.toList());

            for (ResourceValue value : items) {
                if (value.getResourceType()
                        .getName()
                        .startsWith(resourceType)) {
                    String label = value.asReference()
                            .getRelativeResourceUrl(appNamespace, resolver)
                            .toString();
                    CompletionItem item = CompletionItem.create(label, "Value", label);
                    item.iconKind = DrawableKind.LocalVariable;
                    item.setInsertHandler(new ValueInsertHandler(attribute, item));
                    item.addFilterText(value.asReference().getRelativeResourceUrl(appNamespace).toString());
                    item.addFilterText(value.getName());
                    list.addItem(item);
                }
            }
        }
    }

    @Nullable
    private static String getResourceType(String declaration) {
        if (!declaration.startsWith("@")) {
            return null;
        }
        if (declaration.contains("/")) {
            return declaration.substring(1, declaration.indexOf('/'));
        }
        return declaration.substring(1);
    }

    @Nullable
    public static ResourceItem getApplicableValue(Collection<ResourceItem> items) {
        Map<Configurable, ResourceItem> map = new HashMap<>();
        for (ResourceItem item : items) {
            FolderConfiguration configuration = item.getConfiguration();
            map.put(() -> configuration, item);
        }
        Configurable matching = DEFAULT.findMatchingConfigurable(map.keySet());
        if (matching == null) {
            return null;
        }
        return map.get(matching);
    }

    private static List<ResourceType> getMatchingTypes(AttrResourceValue attrResourceValue) {
        return attrResourceValue.getFormats()
                .stream()
                .flatMap(it -> it.getMatchingTypes()
                        .stream())
                .collect(Collectors.toList());
    }
}
