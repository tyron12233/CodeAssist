package com.tyron.builder.compiler.manifest.resources;

import static com.google.common.base.MoreObjects.firstNonNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.tyron.builder.compiler.manifest.SdkConstants;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.function.Function;

public enum ResourceType {
    ANIM("anim", "Animation"),
    ANIMATOR("animator", "Animator"),
    ARRAY("array", "Array", "string-array", "integer-array"),
    ATTR("attr", "Attr"),
    BOOL("bool", "Boolean"),
    COLOR("color", "Color"),
    DIMEN("dimen", "Dimension"),
    DRAWABLE("drawable", "Drawable"),
    FONT("font", "Font"),
    FRACTION("fraction", "Fraction"),
    ID("id", "ID"),
    INTEGER("integer", "Integer"),
    INTERPOLATOR("interpolator", "Interpolator"),
    LAYOUT("layout", "Layout"),
    MENU("menu", "Menu"),
    MIPMAP("mipmap", "Mip Map"),
    NAVIGATION("navigation", "Navigation"),
    PLURALS("plurals", "Plurals"),
    RAW("raw", "Raw"),
    STRING("string", "String"),
    STYLE("style", "Style"),
    STYLEABLE("styleable", "Styleable", Kind.STYLEABLE),
    TRANSITION("transition", "Transition"),
    XML("xml", "XML"),

    PUBLIC("public", "Public visibility modifier", Kind.SYNTHETIC),

    SAMPLE_DATA("sample-data", "Sample data", Kind.SYNTHETIC),

    AAPT("_aapt", "Aapt attribute", Kind.SYNTHETIC),

    OVERLAYABLE("overlayable", "Overlayable tag", Kind.SYNTHETIC),

    STYLE_ITEM("item", "Style item", Kind.SYNTHETIC),

    MACRO("macro", "Macro resource replacement", Kind.SYNTHETIC)
    ;

    private enum Kind {

        REAL,

        STYLEABLE,

        SYNTHETIC
    }

    @NotNull private final String mName;
    @NotNull private final Kind mKind;
    @NotNull private final String mDisplayName;
    @NotNull private final String[] mAlternateXmlNames;

    ResourceType(
            @NotNull String name,
            @NotNull String displayName,
            @NotNull String... alternateXmlNames) {
        mName = name;
        mKind = Kind.REAL;
        mDisplayName = displayName;
        mAlternateXmlNames = alternateXmlNames;
    }

    ResourceType(
            @NotNull String name,
            @NotNull String displayName,
            @NotNull Kind kind) {
        mName = name;
        mKind = kind;
        mDisplayName = displayName;
        mAlternateXmlNames = new String[0];
    }

    /** The set of all types of resources that can be referenced by other resources */
    public static final ImmutableSet<ResourceType> REFERENCEABLE_TYPES;

    private static final ImmutableMap<String, ResourceType> TAG_NAMES;
    private static final ImmutableMap<String, ResourceType> CLASS_NAMES;

    static {
        ImmutableMap.Builder<String, ResourceType> tagNames = ImmutableMap.builder();
        tagNames.put(SdkConstants.TAG_DECLARE_STYLEABLE, STYLEABLE);
        tagNames.put(SdkConstants.TAG_PUBLIC, PUBLIC);
        tagNames.put(OVERLAYABLE.getName(), OVERLAYABLE);
        // macro

        ImmutableMap.Builder<String, ResourceType> classNames = ImmutableMap.builder();
        classNames.put(STYLEABLE.mName, STYLEABLE);

        for (ResourceType type : ResourceType.values()) {
            if (type.mKind != Kind.REAL || type == STYLEABLE) {
                continue;
            }
            classNames.put(type.getName(), type);
            tagNames.put(type.getName(), type);
            for (String mAlternateXmlName : type.mAlternateXmlNames) {
                tagNames.put(mAlternateXmlName, type);
            }
        }
        TAG_NAMES = tagNames.build();
        CLASS_NAMES = classNames.build();
        REFERENCEABLE_TYPES = Arrays.stream(values())
                .filter(ResourceType::getCanBeReferenced)
                .collect(Sets.toImmutableEnumSet());
    }

    @Nullable
    public static ResourceType fromClassName(String className) {
        return CLASS_NAMES.get(className);
    }

    @Nullable
    public static ResourceType fromXmlTagName(String tagName) {
        return TAG_NAMES.get(tagName);
    }

    @Nullable
    public static <T> ResourceType fromXmlTag(
            @NotNull T tag,
            @NotNull Function<T, String> nameFunction,
            @NotNull BiFunction<? super T, ? super String, String> attributeFunction) {
        String tagName = nameFunction.apply(tag);
        switch (tagName) {
            case SdkConstants.TAG_EAT_COMMENT:
                return null;
            case SdkConstants.TAG_ITEM:
                String typeAttribute = attributeFunction.apply(tag, SdkConstants.ATTR_TYPE);
                if (!Strings.isNullOrEmpty(typeAttribute)) {
                    return fromClassName(typeAttribute);
                } else {
                    return null;
                }
            default:
                return fromXmlTagName(tagName);
        }
    }


    /**
     * Returns the enum by its name as it appears in a {@link ResourceUrl} string.
     *
     * @param xmlValue value of the type attribute or the prefix of a {@link ResourceUrl}, e.g.
     *     "string" or "array".
     */
    @Nullable
    public static ResourceType fromXmlValue(@NotNull String xmlValue) {
        if (xmlValue.equals(SdkConstants.TAG_DECLARE_STYLEABLE)
                || xmlValue.equals(STYLEABLE.mName)) {
            return null;
        }

//        if (xmlValue.equals(SAMPLE_DATA.mName)) {
//            return SAMPLE_DATA;
//        }

        if (xmlValue.equals(AAPT.mName)) {
            return AAPT;
        }

        if (xmlValue.equals(OVERLAYABLE.mName)) {
            return OVERLAYABLE;
        }

        if (xmlValue.equals(MACRO.mName)) {
            return MACRO;
        }
        return CLASS_NAMES.get(xmlValue);
    }


    @Nullable
    public static ResourceType fromXmlTag(@NotNull Node domNode) {
        if (!(domNode instanceof Element)) {
            return null;
        }

        Element tag = (Element) domNode;
        return fromXmlTag(
                tag,
                element -> firstNonNull(element.getLocalName(), element.getTagName()),
                Element::getAttribute);
    }

    @NotNull
    public String getName() {
        return mName;
    }

    @NotNull
    public String getDisplayName() {
        return mDisplayName;
    }

    public boolean getCanBeReferenced() {
        return (mKind == Kind.REAL && this != ATTR) || this == MACRO;
    }
}
