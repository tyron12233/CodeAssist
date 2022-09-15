package com.tyron.completion.xml.v2.project;

import static com.android.SdkConstants.ATTR_FORMAT;
import static com.android.SdkConstants.ATTR_INDEX;
import static com.android.SdkConstants.ATTR_NAME;
import static com.android.SdkConstants.ATTR_PARENT;
import static com.android.SdkConstants.ATTR_QUANTITY;
import static com.android.SdkConstants.ATTR_VALUE;
import static com.android.SdkConstants.TAG_ENUM;
import static com.android.SdkConstants.TAG_FLAG;
import static com.android.SdkConstants.TOOLS_URI;
import static com.android.ide.common.util.PathStringUtil.toPathString;

import com.android.ide.common.rendering.api.ArrayResourceValueImpl;
import com.android.ide.common.rendering.api.AttrResourceValue;
import com.android.ide.common.rendering.api.AttrResourceValueImpl;
import com.android.ide.common.rendering.api.AttributeFormat;
import com.android.ide.common.rendering.api.DensityBasedResourceValueImpl;
import com.android.ide.common.rendering.api.PluralsResourceValueImpl;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.ResourceValueImpl;
import com.android.ide.common.rendering.api.StyleItemResourceValueImpl;
import com.android.ide.common.rendering.api.StyleResourceValueImpl;
import com.android.ide.common.rendering.api.StyleableResourceValueImpl;
import com.android.ide.common.rendering.api.TextResourceValueImpl;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.SingleNamespaceResourceRepository;
import com.android.ide.common.resources.ValueXmlHelper;
import com.android.ide.common.resources.configuration.DensityQualifier;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.ide.common.util.PathString;
import com.android.resources.Density;
import com.android.resources.ResourceFolderType;
import com.android.resources.ResourceType;
import com.android.resources.ResourceUrl;
import com.google.common.collect.ImmutableList;
import com.tyron.completion.xml.v2.base.RepositoryConfiguration;
import com.tyron.xml.completion.util.DOMUtils;

import org.eclipse.lemminx.dom.DOMComment;
import org.eclipse.lemminx.dom.DOMElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public class DomResourceItem implements ResourceItem {

    @NotNull private final String myName;
    @NotNull private final ResourceType myType;
    @Nullable private DomResourceFile mySourceFile;

    @NotNull
    private final ResourceFolderRepository myOwner;
    @Nullable
    private ResourceValue myResourceValue;

    /**
     * This weak reference is kept exclusively for the {@link #wasTag(DOMElement)} method. Once the original
     * tag is garbage collected, the {@link #wasTag(DOMElement)} method will return false for any tag except
     * the one pointed to by {@link #myTagPointer}.
     */
    @Nullable private final WeakReference<DOMElement> myOriginalTag;
    private final File file;

    private DomResourceItem(@NotNull String name,
                            @NotNull ResourceType type,
                            @NotNull ResourceFolderRepository owner,
                            @Nullable DOMElement tag,
                            @NotNull File file) {
        myName = name;
        myType = type;
        myOwner = owner;

        myOriginalTag = tag == null ? null : new WeakReference<>(tag);
        this.file = file;
    }

    /**
     * Creates a new PsiResourceItem for a given {@link DOMElement}.
     *
     * @param name the name of the resource
     * @param type the type of the resource
     * @param owner the owning resource repository
     * @param tag the XML tag to create the resource from
     */
    @NotNull
    public static DomResourceItem forXmlTag(@NotNull String name,
                                            @NotNull ResourceType type,
                                            @NotNull ResourceFolderRepository owner,
                                            @NotNull DOMElement tag,
                                            @NotNull File file) {
        return new DomResourceItem(name, type, owner, tag, file);
    }

    /**
     * Creates a new PsiResourceItem for a given {@link PsiFile}.
     *
     * @param name the name of the resource
     * @param type the type of the resource
     * @param owner the owning resource repository
     * @param file the XML file to create the resource from
     */
    @NotNull
    public static DomResourceItem forFile(@NotNull String name,
                                          @NotNull ResourceType type,
                                          @NotNull ResourceFolderRepository owner,
                                          @NotNull File file) {
        return new DomResourceItem(name, type, owner, null, file);
    }

    @Override
    public String getName() {
        return myName;
    }

    @Override
    public ResourceType getType() {
        return myType;
    }

    @Override
    public ResourceNamespace getNamespace() {
        return myOwner.getNamespace();
    }

    @Override
    public String getLibraryName() {
        return null;
    }

    @Override
    public ResourceReference getReferenceToSelf() {
        return new ResourceReference(getNamespace(), myType, myName);
    }

    @Override
    public SingleNamespaceResourceRepository getRepository() {
        return myOwner;
    }

    @Override
    public String getKey() {
        String qualifiers = getConfiguration().getQualifierString();
        if (!qualifiers.isEmpty()) {
            return myType.getName() + '-' + qualifiers + '/' + myName;
        }

        return myType.getName() + '/' + myName;
    }

    @Override
    public ResourceValue getResourceValue() {
        if (myResourceValue == null) {
            DOMElement tag = getTag();
            if (tag == null) {
                DomResourceFile source = getSourceFile();
                assert source != null : "getResourceValue called on a PsiResourceItem with no source";
                // Density based resource value?
                ResourceType type = getType();
                Density density = type == ResourceType.DRAWABLE || type == ResourceType.MIPMAP ? getFolderDensity() : null;

                File virtualFile = source.getVirtualFile();
                String path = virtualFile == null ? null : virtualFile.getAbsolutePath();
                if (density != null) {
                    myResourceValue = new DensityBasedResourceValueImpl(getNamespace(), myType, myName, path, density, null);
                } else {
                    myResourceValue = new ResourceValueImpl(getNamespace(), myType, myName, path, null);
                }
            } else {
                myResourceValue = parseXmlToResourceValueSafe(tag);
            }
        }

        return myResourceValue;
    }

    @Nullable
    private ResourceValue parseXmlToResourceValueSafe(@Nullable DOMElement tag) {
        return parseXmlToResourceValue(tag);
    }


    @Nullable
    private ResourceValue parseXmlToResourceValue(@Nullable DOMElement tag) {
        if (tag == null) {
            return null;
        }

        ResourceValueImpl value;
        switch (myType) {
            case STYLE:
                String parent = getAttributeValue(tag, ATTR_PARENT);
                value = parseStyleValue(tag, new StyleResourceValueImpl(getNamespace(), myName, parent, null));
                break;
            case STYLEABLE:
                value = parseDeclareStyleable(tag, new StyleableResourceValueImpl(getNamespace(), myName, null, null));
                break;
            case ATTR:
                value = parseAttrValue(tag, new AttrResourceValueImpl(getNamespace(), myName, null));
                break;
            case ARRAY:
                value = parseArrayValue(tag, new ArrayResourceValueImpl(getNamespace(), myName, null) {
                    // Allow the user to specify a specific element to use via tools:index
                    @Override
                    protected int getDefaultIndex() {
                        String index = tag.getAttributeNS(ATTR_INDEX, TOOLS_URI);
                        if (index != null) {
                            return Integer.parseInt(index);
                        }
                        return super.getDefaultIndex();
                    }
                });
                break;
            case PLURALS:
                value = parsePluralsValue(tag, new PluralsResourceValueImpl(getNamespace(), myName, null, null) {
                    // Allow the user to specify a specific quantity to use via tools:quantity
                    @Override
                    public String getValue() {
                        String quantity = tag.getAttributeNS(ATTR_QUANTITY, TOOLS_URI);
                        if (quantity != null) {
                            String value = getValue(quantity);
                            if (value != null) {
                                return value;
                            }
                        }
                        return super.getValue();
                    }
                });
                break;
            case STRING:
                value = parseTextValue(tag, new DomTextResourceValue(getNamespace(), myName, null, null, null));
                break;
            default:
                value = parseValue(tag, new ResourceValueImpl(getNamespace(), myType, myName, null));
                break;
        }
        value.setNamespaceResolver(namespacePrefix -> DOMUtils.getNamespaceResolver(tag.getOwnerDocument()).prefixToUri(namespacePrefix));
        return value;
    }

    @NotNull
    private static StyleResourceValueImpl parseStyleValue(@NotNull DOMElement tag, @NotNull StyleResourceValueImpl styleValue) {
        for (DOMElement child : DOMUtils.getSubTags(tag)) {
            String name = getAttributeValue(child, ATTR_NAME);
            if (!StringUtil.isEmpty(name)) {
                String value = ValueXmlHelper.unescapeResourceString(child.getTextContent(), true, true);
                StyleItemResourceValueImpl itemValue =
                        new StyleItemResourceValueImpl(styleValue.getNamespace(), name, value, styleValue.getLibraryName());
                itemValue.setNamespaceResolver(namespacePrefix -> DOMUtils.getNamespaceResolver(tag.getOwnerDocument()).prefixToUri(namespacePrefix));
                styleValue.addItem(itemValue);
            }
        }

        return styleValue;
    }

    @Nullable
    private static String getAttributeValue(@NotNull DOMElement tag, @NotNull String attributeName) {
        return tag.getAttribute(attributeName);
    }

    @NotNull
    private StyleableResourceValueImpl parseDeclareStyleable(@NotNull DOMElement tag,
                                                             @NotNull StyleableResourceValueImpl declareStyleable) {
        for (DOMElement child : DOMUtils.getSubTags(tag)) {
            String name = getAttributeValue(child, ATTR_NAME);
            if (!StringUtil.isEmpty(name)) {
                ResourceUrl url = ResourceUrl.parseAttrReference(name);
                if (url != null) {
                    ResourceReference resolvedAttr = url.resolve(getNamespace(),
                            namespacePrefix -> DOMUtils.getNamespaceResolver(tag.getOwnerDocument()).prefixToUri(namespacePrefix));
                    if (resolvedAttr != null) {
                        AttrResourceValue attr = parseAttrValue(child, new AttrResourceValueImpl(resolvedAttr, null));
                        declareStyleable.addValue(attr);
                    }
                }
            }
        }
        return declareStyleable;
    }

    @NotNull
    private static AttrResourceValueImpl parseAttrValue(@NotNull DOMElement attrTag, @NotNull AttrResourceValueImpl attrValue) {
        attrValue.setDescription(getDescription(attrTag));

        Set<AttributeFormat> formats = EnumSet.noneOf(AttributeFormat.class);
        String formatString = getAttributeValue(attrTag, ATTR_FORMAT);
        if (formatString != null) {
            formats.addAll(AttributeFormat.parse(formatString));
        }

        for (DOMElement child : DOMUtils.getSubTags(attrTag)) {
            String tagName = child.getLocalName();
            if (TAG_ENUM.equals(tagName)) {
                formats.add(AttributeFormat.ENUM);
            } else if (TAG_FLAG.equals(tagName)) {
                formats.add(AttributeFormat.FLAGS);
            }

            String name = getAttributeValue(child, ATTR_NAME);
            if (name != null) {
                Integer numericValue = null;
                String value = getAttributeValue(child, ATTR_VALUE);
                if (value != null) {
                    try {
                        // Use Long.decode to deal with hexadecimal values greater than 0x7FFFFFFF.
                        numericValue = Long.decode(value).intValue();
                    } catch (NumberFormatException ignored) {
                    }
                }
                attrValue.addValue(name, numericValue, getDescription(child));
            }
        }

        attrValue.setFormats(formats);

        return attrValue;
    }

    @Nullable
    private static String getDescription(@NotNull DOMElement tag) {
        DOMComment comment = DOMUtils.findPreviousComment(tag);
        if (comment != null) {
            String text = comment.getTextContent();
            return text.trim();
        }
        return null;
    }

    @NotNull
    private static ArrayResourceValueImpl parseArrayValue(@NotNull DOMElement tag, @NotNull ArrayResourceValueImpl arrayValue) {
        for (DOMElement child : DOMUtils.getSubTags(tag)) {
            String text = ValueXmlHelper.unescapeResourceString(child.getTextContent(), true, true);
            arrayValue.addElement(text);
        }

        return arrayValue;
    }

    @NotNull
    private static PluralsResourceValueImpl parsePluralsValue(@NotNull DOMElement tag, @NotNull PluralsResourceValueImpl value) {
        for (DOMElement child : DOMUtils.getSubTags(tag)) {
            String quantity = child.getAttribute(ATTR_QUANTITY);
            if (quantity != null) {
                String text = ValueXmlHelper.unescapeResourceString(child.getTextContent(), true, true);
                value.addPlural(quantity, text);
            }
        }

        return value;
    }

    @NotNull
    private static ResourceValueImpl parseValue(@NotNull DOMElement tag, @NotNull ResourceValueImpl value) {
        String text = tag.getTextContent();
        text = ValueXmlHelper.unescapeResourceString(text, true, true);
        value.setValue(text);
        return value;
    }

    @NotNull
    private static DomTextResourceValue parseTextValue(@NotNull DOMElement tag, @NotNull DomTextResourceValue value) {
        String text = tag.getTextContent();
        text = ValueXmlHelper.unescapeResourceString(text, true, true);
        value.setValue(text);

        return value;
    }


    @Nullable
    private Density getFolderDensity() {
        FolderConfiguration configuration = getConfiguration();
        DensityQualifier densityQualifier = configuration.getDensityQualifier();
        if (densityQualifier != null) {
            return densityQualifier.getValue();
        }
        return null;
    }

    @Override
    public PathString getSource() {
        return toPathString(file);
    }

    @Override
    public boolean isFileBased() {
        return true;
    }

    @Override
    public FolderConfiguration getConfiguration() {
        DomResourceFile source = getSourceFile();
        assert source != null : "getConfiguration called on a PsiResourceItem with no source";
        return source.getFolderConfiguration();
    }

    public void setSourceFile(@Nullable DomResourceFile sourceFile) {
        mySourceFile = sourceFile;
    }


    @Nullable
    public DomResourceFile getSourceFile() {
        if (mySourceFile != null) {
            return mySourceFile;
        }


        String name = Objects.requireNonNull(file.getParentFile()).getName();
        ResourceFolderType folderType = ResourceFolderType.getFolderType(name);
        if (folderType == null) {
            return null;
        }
        FolderConfiguration folderConfiguration = FolderConfiguration.getConfigForFolder(name);
        if (folderConfiguration == null) {
            return null;
        }
        // PsiResourceFile constructor sets the source of this item.
        return new DomResourceFile(file, ImmutableList.of(this), folderType, new RepositoryConfiguration(myOwner, folderConfiguration));
    }

    @Nullable
    public DOMElement getTag() {
        return myOriginalTag == null ? null : myOriginalTag.get();
    }

    private class DomTextResourceValue extends TextResourceValueImpl {
        DomTextResourceValue(@NotNull ResourceNamespace namespace, @NotNull String name,
                             @Nullable String textValue, @Nullable String rawXmlValue, @Nullable String libraryName) {
            super(namespace, name, textValue, rawXmlValue, libraryName);
        }

        @Override
        public String getRawXmlValue() {
            DOMElement tag = getTag();

            if (tag == null) {
                return getValue();
            }

            return tag.getNodeValue();
        }
    }
}
