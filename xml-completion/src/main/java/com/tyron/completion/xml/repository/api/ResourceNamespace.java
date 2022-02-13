package com.tyron.completion.xml.repository.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.tyron.builder.compiler.manifest.SdkConstants;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Represents a namespace used by aapt when processing resources.
 *
 * <p>In "traditional" projects, all resources from local sources and AARs live in the {@link
 * #RES_AUTO} namespace and are processed together by aapt. Framework resources belong in the
 * "android" package name / namespace.
 *
 * <p>In namespace-aware projects, every module and AAR contains resources in a separate namespace
 * that is read from the manifest and corresponds to the {@code package-name}. Framework resources
 * are treated as before.
 *
 * <p>The tools namespace is a special case and is only used by sample data, so it never reaches the
 * aapt stage.
 *
 * <p>This class is serializable to allow passing between Gradle workers.
 */
public class ResourceNamespace implements Comparable<ResourceNamespace>, Serializable {
    public static final ResourceNamespace ANDROID =
            new ResourceNamespace(SdkConstants.ANDROID_URI, SdkConstants.ANDROID_NS_NAME);
    public static final ResourceNamespace RES_AUTO = new ResAutoNamespace();
    public static final ResourceNamespace TOOLS = new ToolsNamespace();
    public static final ResourceNamespace AAPT = new AaptNamespace();
    /** The namespace of the Androidx appcompat library when namespaces are used. */
    public static final ResourceNamespace APPCOMPAT = fromPackageName("androidx.appcompat");
    /** The namespace of the old appcompat library when namespaces are used. */
    public static final ResourceNamespace APPCOMPAT_LEGACY =
            fromPackageName("android.support.v7.appcompat");

    private static final Logger LOG = Logger.getLogger(ResourceNamespace.class.getSimpleName());

    @SuppressWarnings("StaticNonFinalField") // Non-final to be able to set in code.
    public static boolean noncomplianceLogging = false;

    /**
     * Namespace used in code that needs to start keeping track of namespaces. For easy tracking of
     * parts of codebase that need fixing.
     */
    @NonNull
    public static ResourceNamespace TODO() {
        if (noncomplianceLogging) {
            String trace =
                    Arrays.stream(Thread.currentThread().getStackTrace())
                            .map(Object::toString)
                            .collect(Collectors.joining("\n"));
            LOG.warning("This code does not support namespaces yet\n" + trace);
        }
        return RES_AUTO;
    }

    /**
     * Logic for looking up namespace prefixes defined in some context.
     *
     * @see ResourceNamespace#fromNamespacePrefix(String, ResourceNamespace, Resolver)
     */
    public interface Resolver {
        /** Returns the full URI of an XML namespace for a given prefix, if defined. */
        @Nullable
        String prefixToUri(@NonNull String namespacePrefix);

        @Nullable
        default String uriToPrefix(@NonNull String namespaceUri) {
            // TODO(namespaces): remove the default implementation once layoutlib provides one.
            return null;
        }

        Resolver EMPTY_RESOLVER =
                new Resolver() {
                    @Nullable
                    @Override
                    public String uriToPrefix(@NonNull String namespaceUri) {
                        return null;
                    }

                    @Nullable
                    @Override
                    public String prefixToUri(@NonNull String namespacePrefix) {
                        return null;
                    }
                };

        /**
         * Contains a single mapping from "tools" to the tools URI. In the past we assumed the
         * "tools:" prefix is defined, we need to keep doing this for projects that don't care about
         * namespaces.
         */
        Resolver TOOLS_ONLY =
                fromBiMap(
                        ImmutableBiMap.of(
                                SdkConstants.TOOLS_NS_NAME, SdkConstants.TOOLS_URI));

        /**
         * Creates a new {@link Resolver} which looks up prefix definitions in the given {@link
         * BiMap}.
         *
         * @param prefixes a {@link BiMap} mapping prefix strings to full namespace URIs
         */
        @NonNull
        static Resolver fromBiMap(@NonNull BiMap<String, String> prefixes) {
            return new Resolver() {
                @Nullable
                @Override
                public String uriToPrefix(@NonNull String namespaceUri) {
                    return prefixes.inverse().get(namespaceUri);
                }

                @Nullable
                @Override
                public String prefixToUri(@NonNull String namespacePrefix) {
                    return prefixes.get(namespacePrefix);
                }
            };
        }
    }

    @NonNull private final String uri;
    @Nullable private final String packageName;

    /**
     * Constructs a {@link ResourceNamespace} for the given (fully qualified) aapt package name.
     * Note that this is not the string used in XML notation before the colon (at least not in the
     * general case), which can be an alias.
     *
     * <p>This factory method can be used when reading the build system model or for testing, other
     * code most likely needs to resolve the short namespace prefix against XML namespaces defined
     * in the given context.
     *
     * @see #fromNamespacePrefix(String, ResourceNamespace, Resolver)
     */
    @NonNull
    public static ResourceNamespace fromPackageName(@NonNull String packageName) {
        assert !Strings.isNullOrEmpty(packageName);
        if (packageName.equals(SdkConstants.ANDROID_NS_NAME)) {
            // Make sure ANDROID is a singleton, so we can use object identity to check for it.
            return ANDROID;
        } else {
            return new ResourceNamespace(SdkConstants.URI_PREFIX + packageName, packageName);
        }
    }

    /**
     * Constructs a {@link ResourceNamespace} in code that does not keep track of namespaces yet,
     * only of the boolean `isFramework` flag.
     */
    @NonNull
    @Deprecated
    public static ResourceNamespace fromBoolean(boolean isFramework) {
        return isFramework ? ANDROID : TODO();
    }

    /**
     * Tries to build a {@link ResourceNamespace} from the first part of a {@link
     * ResourceUrl}, given the context in which the string was used.
     *
     * @param prefix the string to resolve
     * @param defaultNamespace namespace in which this prefix was used. If no prefix is used (it's
     *     null), this is the namespace that will be returned. For example, if an XML file inside
     *     libA (com.lib.a) references "@string/foo", it means the "foo" resource from libA, so the
     *     "com.lib.a" namespace should be passed as the {@code defaultNamespace}.
     * @param resolver strategy for mapping short namespace prefixes to namespace URIs as used in
     *     XML resource files. This should be provided by the XML parser used. For example, if the
     *     source XML document contained snippet such as {@code
     *     xmlns:foo="http://schemas.android.com/apk/res/com.foo"}, it should return {@code
     *     "http://schemas.android.com/apk/res/com.foo"} when applied to argument {@code "foo"}.
     * @see ResourceUrl#namespace
     */
    @Nullable
    public static ResourceNamespace fromNamespacePrefix(
            @Nullable String prefix,
            @NonNull ResourceNamespace defaultNamespace,
            @NonNull Resolver resolver) {
        if (Strings.isNullOrEmpty(prefix)) {
            return defaultNamespace;
        }

        String uri = resolver.prefixToUri(prefix);
        if (uri != null) {
            return fromNamespaceUri(uri);
        } else {
            // TODO(namespaces): What is considered a good package name by aapt?
            return fromPackageName(prefix);
        }
    }

    /**
     * Constructs a {@link ResourceNamespace} for the given URI, as used in XML resource files.
     *
     * <p>This methods returns null if we don't recognize the URI.
     */
    @Nullable
    public static ResourceNamespace fromNamespaceUri(@NonNull String uri) {
        if (uri.equals(SdkConstants.ANDROID_URI)) {
            return ANDROID;
        }
        if (uri.equals(SdkConstants.AUTO_URI)) {
            return RES_AUTO;
        }
        if (uri.equals(SdkConstants.TOOLS_URI)) {
            return TOOLS;
        }
        if (uri.equals(SdkConstants.AAPT_URI)) {
            return AAPT;
        }
        if (uri.startsWith(SdkConstants.URI_PREFIX)) {
            // TODO(namespaces): What is considered a good package name by aapt?
            String packageName = uri.substring(SdkConstants.URI_PREFIX.length());
            if (!packageName.isEmpty()) {
                return fromPackageName(packageName);
            }
        }

        // The prefix is mapped to a string/URL we don't understand.
        return null;
    }

    private ResourceNamespace(@NonNull String uri, @Nullable String packageName) {
        this.uri = uri;
        this.packageName = packageName;
    }

    /**
     * Returns the package associated with this namespace, or null in the case of {@link #RES_AUTO}.
     *
     * <p>The result value can be used as the namespace part of a {@link
     * com.android.resources.ResourceUrl}.
     */
    @Nullable
    public String getPackageName() {
        return packageName;
    }

    @NonNull
    public String getXmlNamespaceUri() {
        return uri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ResourceNamespace that = (ResourceNamespace) o;
        return Objects.equals(packageName, that.packageName);
    }

    @NonNull
    public Object readResolve() {
        switch (uri) {
            case SdkConstants.ANDROID_URI:
                return ANDROID;
            case SdkConstants.AUTO_URI:
                return RES_AUTO;
            case SdkConstants.TOOLS_URI:
                return TOOLS;
            case SdkConstants.AAPT_URI:
                return AAPT;
            default:
                return this;
        }
    }

    @Override
    public int hashCode() {
        return uri.hashCode();
    }

    @Override
    public String toString() {
        return uri.substring(SdkConstants.URI_DOMAIN_PREFIX.length());
    }

    @Override
    public int compareTo(@NonNull ResourceNamespace other) {
        return uri.compareTo(other.uri);
    }

    private static class ResAutoNamespace extends ResourceNamespace {
        private ResAutoNamespace() {
            super(SdkConstants.AUTO_URI, null);
        }
    }

    private static class ToolsNamespace extends ResourceNamespace {
        private ToolsNamespace() {
            super(SdkConstants.TOOLS_URI, null);
        }
    }

    private static class AaptNamespace extends ResourceNamespace {
        private AaptNamespace() {
            super(SdkConstants.AAPT_URI, null);
        }
    }
}
