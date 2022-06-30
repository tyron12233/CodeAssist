package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;

/**
 * Represents the usage of a configuration. Typical usages include compilation or runtime.
 * This interface allows the user to customize usages by implementing this interface.
 *
 * @since 3.4
 */
public interface Usage extends Named {
    Attribute<Usage> USAGE_ATTRIBUTE = Attribute.of("com.tyron.builder.usage", Usage.class);

    /**
     * The Java API of a library, packaged as class path elements, either a JAR or a classes directory.
     *
     * @since 4.0
     */
    String JAVA_API = "java-api";

    /**
     * The Java API of a library, packaged as class path elements, either a JAR or a classes directory. Should not include resources, but may.
     *
     * @since 4.0
     */
    @Deprecated
    String JAVA_API_CLASSES = "java-api-classes";

    /**
     * The Java API of a library, packaged as a JAR only. Must not include classes directories.
     *
     * @since 5.3
     */
    @Deprecated
    String JAVA_API_JARS = "java-api-jars";

    /**
     * The Java runtime of a component, packaged as class path elements, either a JAR or a classes directory.
     *
     * @since 4.0
     */
    String JAVA_RUNTIME = "java-runtime";

    /**
     * The Java runtime of a component, packaged as JAR only. Must not include classes directories.
     *
     * @since 4.0
     */
    @Deprecated
    String JAVA_RUNTIME_JARS = "java-runtime-jars";

    /**
     * The Java runtime classes of a component, packaged as class path elements, either a JAR or a classes directory. Should not include resources, but may.
     *
     * @since 4.0
     */
    @Deprecated
    String JAVA_RUNTIME_CLASSES = "java-runtime-classes";

    /**
     * The Java runtime resources of a component, packaged as class path elements, either a JAR or a classes directory. Should not include classes, but may.
     *
     * @since 4.0
     */
    @Deprecated
    String JAVA_RUNTIME_RESOURCES = "java-runtime-resources";

    /**
     * The C++ API of a library, packaged as header directories.
     *
     * @since 4.1
     */
    String C_PLUS_PLUS_API = "cplusplus-api";

    /**
     * The native link files of a library, packaged as static or shared library.
     *
     * @since 4.1
     */
    String NATIVE_LINK = "native-link";

    /**
     * The native runtime files of a library, packaged as a shared library.
     *
     * @since 4.1
     */
    String NATIVE_RUNTIME = "native-runtime";

    /**
     * The Swift API of a library, packaged as swiftmodule files.
     *
     * @since 4.1
     */
    String SWIFT_API = "swift-api";

    /**
     * A version catalog, packaged as TOML files, for use as recommendations
     * for dependency and plugin versions.
     *
     * @since 7.0
     */
    @Incubating
    String VERSION_CATALOG = "version-catalog";

    /**
     * Variants meant to be consumed by verification tasks.
     *
     * @since 7.4
     */
    @Incubating
    String VERIFICATION = "verification";
}
