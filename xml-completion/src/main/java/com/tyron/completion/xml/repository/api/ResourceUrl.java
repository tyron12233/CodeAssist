package com.tyron.completion.xml.repository.api;

import static com.tyron.completion.xml.repository.api.RenderResources.REFERENCE_EMPTY;
import static com.tyron.completion.xml.repository.api.RenderResources.REFERENCE_NULL;
import static com.tyron.completion.xml.repository.api.RenderResources.REFERENCE_UNDEFINED;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.SdkConstants;
import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.io.Serializable;
import java.util.Objects;

import javax.annotation.concurrent.Immutable;

/**
 * A {@linkplain ResourceUrl} represents a parsed resource url such as {@code @string/foo} or {@code
 * ?android:attr/bar}
 */
@Immutable
public class ResourceUrl implements Serializable {
    /** Type of resource. */
    @NonNull
    public final ResourceType type;

    /** Name of resource. */
    @NonNull public final String name;

    /** The namespace, or null if it's in the project namespace. */
    @Nullable
    public final String namespace;

    @NonNull public final UrlType urlType;

    /** The URL requests access to a private resource. */
    public final boolean privateAccessOverride;

    /** If true, the resource is in the android: framework. */
    public boolean isFramework() {
        return SdkConstants.ANDROID_NS_NAME.equals(namespace);
    }

    /** Whether an id resource is of the form {@code @+id} rather than just {@code @id}. */
    public boolean isCreate() {
        return urlType == UrlType.CREATE;
    }

    /** Whether this is a theme resource reference. */
    public boolean isTheme() {
        return urlType == UrlType.THEME;
    }

    /** Whether this is a theme resource reference. */
    public boolean isPrivateAccessOverride() {
        return privateAccessOverride;
    }

    public enum UrlType {
        /** Reference of the form {@code @string/foo}. */
        NORMAL,

        /** Reference of the form {@code @+id/foo}. */
        CREATE,

        /** Reference of the form {@code ?android:textColor}. */
        THEME,

        /** Reference of the form {@code android:textColor}. */
        ATTR,
    }

    private ResourceUrl(
            @NonNull ResourceType type,
            @NonNull String name,
            @Nullable String namespace,
            @NonNull UrlType urlType,
            boolean privateAccessOverride) {
        this.type = type;
        this.name = name;
        this.namespace = namespace;
        this.urlType = urlType;
        this.privateAccessOverride = privateAccessOverride;
    }

    /**
     * Creates a new resource URL, representing "@type/name" or "@android:type/name".
     *
     * @see #parse(String)
     * @param type the resource type
     * @param name the name
     * @param framework whether it's a framework resource
     * @deprecated This factory method is used where we have no way of knowing the namespace. We
     *     need to migrate every call site to the other factory method that takes a namespace.
     */
    @Deprecated // TODO: namespaces
    public static ResourceUrl create(
            @NonNull ResourceType type, @NonNull String name, boolean framework) {
        return new ResourceUrl(
                type,
                name,
                framework ? SdkConstants.ANDROID_NS_NAME : null,
                UrlType.NORMAL,
                false);
    }

    /**
     * Creates a new resource URL, representing "@namespace:type/name".
     *
     * @see #parse(String)
     * @param namespace the resource namespace
     * @param type the resource type
     * @param name the name
     */
    @NonNull
    public static ResourceUrl create(
            @Nullable String namespace, @NonNull ResourceType type, @NonNull String name) {
        return new ResourceUrl(type, name, namespace, UrlType.NORMAL, false);
    }

    /**
     * Creates a new resource URL, representing "?namespace:type/name".
     *
     * @see #parse(String)
     * @param namespace the resource namespace
     * @param type the resource type
     * @param name the name
     */
    @NonNull
    public static ResourceUrl createThemeReference(
            @Nullable String namespace, @NonNull ResourceType type, @NonNull String name) {
        return new ResourceUrl(type, name, namespace, UrlType.THEME, false);
    }

    /**
     * Creates a new resource URL, representing "namespace:name".
     *
     * @see #parse(String)
     * @param namespace the resource namespace
     * @param name the name
     */
    @NonNull
    public static ResourceUrl createAttrReference(
            @Nullable String namespace, @NonNull String name) {
        return new ResourceUrl(ResourceType.ATTR, name, namespace, UrlType.ATTR, false);
    }

    /**
     * Returns a {@linkplain ResourceUrl} representation of the given string, or null if it's not a
     * valid resource reference. This method works only for strings of type {@link UrlType#NORMAL},
     * {@link UrlType#CREATE} and {@link UrlType#THEME}, see dedicated methods for parsing
     * references to style parents and to {@code attr} resources in the {@code name} XML attribute
     * of style items.
     *
     * @param url the resource url to be parsed
     * @return a pair of the resource type and the resource name
     */
    @Nullable
    public static ResourceUrl parse(@NonNull String url) {
        return parse(url, false);
    }

    /**
     * Returns a {@linkplain ResourceUrl} representation of the given string, or null if it's not a
     * valid resource reference. This method works only for strings of type {@link UrlType#NORMAL},
     * {@link UrlType#CREATE} and {@link UrlType#THEME}, see dedicated methods for parsing
     * references to style parents and to {@code attr} resources in the {@code name} XML attribute
     * of style items.
     *
     * @param url the resource url to be parsed
     * @param defaultToFramework defaults the returned value to be a framework resource if no
     *     namespace is specified.
     *     <p>TODO(namespaces): remove the defaultToFramework argument.
     */
    @Nullable
    public static ResourceUrl parse(@NonNull String url, boolean defaultToFramework) {
        // Options:
        UrlType urlType = UrlType.NORMAL;

        // A prefix that ends with a '*' means that private access is overridden.
        boolean privateAccessOverride = false;

        // If the prefix is '?' the url points to a style.
        boolean isStyle = false;

        // Beginning of the parsing:
        int currentIndex = 0;
        int length = url.length();
        if (currentIndex == length) {
            return null;
        }
        char currentChar = url.charAt(currentIndex);

        // The prefix could be one of @, @+, @+*, @*, ?
        char themePrefix = SdkConstants.PREFIX_THEME_REF.charAt(0);
        char resourcePrefix = SdkConstants.PREFIX_RESOURCE_REF.charAt(0);
        if (themePrefix == currentChar) {
            currentIndex++;
            urlType = UrlType.THEME;
            isStyle = true;
        } else if (resourcePrefix == currentChar) {
            currentIndex++;
            if (currentIndex == length) {
                return null;
            }
            currentChar = url.charAt(currentIndex);
            if (currentChar == '+') {
                currentIndex++;
                urlType = UrlType.CREATE;
            }
        }

        int prefixEnd = currentIndex;
        if (prefixEnd == 0) {
            return null;
        }

        // Private override:
        if (currentIndex == length) {
            return null;
        }
        currentChar = url.charAt(currentIndex);
        if (currentChar == '*') {
            privateAccessOverride = true;
            currentIndex++;
        }

        // The token is used to mark the start of a group in the url.
        // Once a piece of code has extracted the desired group,
        // the token is updated to the current position.
        int tokenStart = currentIndex;

        // Namespace or type:
        // The type can only be empty if the prefix is '?' and in that case will default to 'attr'.
        int typeStart = -1;
        int typeEnd = -1;

        // The namespace can be null but cannot be empty, it is always located before ':'.
        int namespaceStart =
                defaultToFramework ? 0 : -1; // Setting to 0 so we don't unnecessary look for it.
        int namespaceEnd = -1;

        // Let's try to find the type and namespace no matter their order
        // until we hit the end of the string.
        while ((typeStart == -1 || namespaceStart == -1) && currentIndex < length) {
            currentChar = url.charAt(currentIndex);
            switch (currentChar) {
                case '/':
                    if (typeStart == -1) {
                        // If the namespace or type were already found, we do not override them.
                        typeStart = tokenStart;
                        typeEnd = currentIndex;
                        tokenStart = currentIndex + 1;
                    }
                    break;
                case ':':
                    if (namespaceStart == -1) {
                        namespaceStart = tokenStart;
                        namespaceEnd = currentIndex;
                        if (namespaceStart == namespaceEnd) {
                            return null;
                        }
                        tokenStart = currentIndex + 1;
                    }
                    break;
                case '[':
                    while (']' != currentChar && currentIndex < length - 1) {
                        currentIndex++;
                        currentChar = url.charAt(currentIndex);
                    }
                    break;
            }
            currentIndex++;
        }

        // Name:
        // The rest of the url can now be considered as the name.
        // The name cannot be empty.
        int nameStart = tokenStart;
        if (length <= nameStart) {
            return null;
        }

        // End of parsing, we know all the indices and we can start
        // extracting them.
        String name = url.substring(nameStart, length);

        // If no type is defined and the url is a style,
        // then the type defaults to attr.
        // But if it is not a style, then the url is invalid.
        ResourceType type;
        if (typeEnd > typeStart) {
            type = ResourceType.fromXmlValue(url.substring(typeStart, typeEnd));
            if (type == null) {
                return null;
            }
        } else if (isStyle) {
            type = ResourceType.ATTR;
        } else {
            return null;
        }

        // If defaultToFramework is true and no namespace is set,
        // the namespace will be 'android'.
        String namespace;
        if (namespaceStart < namespaceEnd) {
            namespace = url.substring(namespaceStart, namespaceEnd);
        } else {
            namespace = defaultToFramework ? SdkConstants.ANDROID_NS_NAME : null;
        }
        return new ResourceUrl(type, name, namespace, urlType, privateAccessOverride);
    }

    /**
     * Returns a {@linkplain ResourceUrl} representation of the given reference to an {@code attr}
     * resources, most likely the contents of {@code <item name="..." >}.
     */
    @Nullable
    public static ResourceUrl parseAttrReference(@NonNull String input) {
        if (input.isEmpty()) {
            return null;
        }

        if (input.charAt(0) == '@' || input.charAt(0) == '?') {
            return null;
        }

        boolean privateAccessOverride = false;
        int prefixEnd = 0;
        if (input.charAt(0) == '*') {
            prefixEnd = 1;
            privateAccessOverride = true;
        }
        if (input.indexOf('/', prefixEnd) >= 0) {
            return null;
        }

        String namespace = null;
        String name;
        int colon = input.indexOf(':', prefixEnd);
        if (colon < 0) {
            name = input.substring(prefixEnd);
        } else {
            namespace = input.substring(prefixEnd, colon);
            if (namespace.isEmpty()) {
                return null;
            }
            name = input.substring(colon + 1);
        }

        if (name.isEmpty()) {
            return null;
        }

        return new ResourceUrl(
                ResourceType.ATTR, name, namespace, UrlType.ATTR, privateAccessOverride);
    }

    /**
     * Returns a {@linkplain ResourceUrl} representation of the given reference to a style's parent.
     */
    @Nullable
    public static ResourceUrl parseStyleParentReference(@NonNull String input) {
        if (input.isEmpty()) {
            return null;
        }

        boolean privateAccessOverride = false;
        int pos = 0;

        if (input.charAt(pos) == '@' || input.charAt(pos) == '?') {
            pos++;
        }

        if (input.startsWith("*", pos)) {
            pos += 1;
            privateAccessOverride = true;
        }

        String namespace = null;
        int colon = input.indexOf(':', pos);
        if (colon != -1) {
            namespace = input.substring(pos, colon);
            if (namespace.isEmpty()) {
                return null;
            }
            pos = colon + 1;
        }

        int slash = input.indexOf('/', pos);
        if (slash != -1) {
            if (!input.startsWith(SdkConstants.REFERENCE_STYLE, pos)) {
                // Wrong resource type used.
                return null;
            }

            pos = slash + 1;
        }

        String name = input.substring(pos);
        if (name.isEmpty()) {
            return null;
        }

        return new ResourceUrl(
                ResourceType.STYLE, name, namespace, UrlType.NORMAL, privateAccessOverride);
    }

    /** Returns if the resource url is @null, @empty or @undefined. */
    public static boolean isNullOrEmpty(@NonNull String url) {
        return url.equals(REFERENCE_NULL) || url.equals(REFERENCE_EMPTY) ||
                url.equals(REFERENCE_UNDEFINED);
    }

    /**
     * Checks whether this resource has a valid name. Used when parsing data that isn't
     * necessarily known to be a valid resource; for example, "?attr/hello world"
     */
    public boolean hasValidName() {
        return isValidName(name, type);
    }

    public static boolean isValidName(@NonNull String input, @NonNull ResourceType type) {
        // TODO(namespaces): This (almost) duplicates ValueResourceNameValidator.

        // Make sure it looks like a resource name; if not, it could just be a string
        // which starts with a ?, etc.
        if (input.isEmpty()) {
            return false;
        }

        if (!Character.isJavaIdentifierStart(input.charAt(0))) {
            return false;
        }
        for (int i = 1, n = input.length(); i < n; i++) {
            char c = input.charAt(i);
            if (!Character.isJavaIdentifierPart(c) && c != '.') {
                // Sample data allows for extra characters
                if (type != ResourceType.SAMPLE_DATA
                        || (c != '/' && c != '[' && c != ']' && c != ':')) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Tries to resolve this {@linkplain ResourceUrl} into a valid {@link ResourceReference} by
     * expanding the namespace alias (or lack thereof) based on the context in which this
     * {@linkplain ResourceUrl} was used.
     *
     * @param contextNamespace aapt namespace of the module in which this URL was used
     * @param resolver logic for expanding namespaces aliases, most likely by walking up the XML
     *     tree.
     * @see ResourceNamespace#fromNamespacePrefix(String, ResourceNamespace,
     *     ResourceNamespace.Resolver)
     */
    @Nullable
    public ResourceReference resolve(
            @NonNull ResourceNamespace contextNamespace,
            @NonNull ResourceNamespace.Resolver resolver) {
        ResourceNamespace resolvedNamespace =
                ResourceNamespace.fromNamespacePrefix(this.namespace, contextNamespace, resolver);
        if (resolvedNamespace == null) {
            return null;
        }
        if (name.indexOf(':') >= 0 && type != ResourceType.SAMPLE_DATA) {
            return null;
        }
        return new ResourceReference(resolvedNamespace, type, name);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        switch (urlType) {
            case NORMAL:
                sb.append(SdkConstants.PREFIX_RESOURCE_REF);
                break;
            case CREATE:
                sb.append("@+");
                break;
            case THEME:
                sb.append(SdkConstants.PREFIX_THEME_REF);
                break;
            case ATTR:
                // No prefix.
                break;
        }
        if (privateAccessOverride) {
            sb.append('*');
        }
        if (namespace != null) {
            sb.append(namespace);
            sb.append(':');
        }

        if (urlType != UrlType.ATTR) {
            sb.append(type.getName());
            sb.append('/');
        }

        sb.append(name);
        return sb.toString();
    }

    /**
     * Returns a short string representation, which includes just the namespace (if defined in this
     * {@linkplain ResourceUrl} and name, separated by a colon. For example {@code
     * ResourceUrl.parse("@android:style/Theme").getQualifiedName()} returns {@code "android:Theme"}
     * and {@code ResourceUrl.parse("?myColor").getQualifiedName()} returns {@code "myColor"}.
     *
     * <p>This is used when the type is implicit, e.g. when specifying attribute for a style item or
     * a parent for a style.
     */
    @NonNull
    public String getQualifiedName() {
        if (namespace == null) {
            return name;
        } else {
            return namespace + ':' + name;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ResourceUrl that = (ResourceUrl) o;
        return urlType == that.urlType
                && type == that.type
                && Objects.equals(name, that.name)
                && Objects.equals(namespace, that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(urlType, type, name, namespace);
    }
}
