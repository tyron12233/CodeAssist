package com.tyron.builder.gradle.internal.pipeline;

import com.tyron.builder.api.transform.QualifiedContent.ContentType;
import com.tyron.builder.api.transform.QualifiedContent.DefaultContentType;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

/**
 * Content types private to the Android Plugin.
 */
public enum ExtendedContentType implements ContentType {

    /**
     * The content is dex files.
     */
    DEX(0x1000),

    /**
     * Content is a native library.
     */
    NATIVE_LIBS(0x2000),

    /**
     * Instant Run '$override' classes, which contain code of new method bodies.
     *
     * <p>This stream also contains the AbstractPatchesLoaderImpl class for applying HotSwap
     * changes.
     */
    CLASSES_ENHANCED(0x4000),

    /**
     * The content is an artifact exported by the data binding compiler.
     */
    DATA_BINDING(0x10000),

    /** The content is Java source file. @Deprecated don't use! */
    @Deprecated
    JAVA_SOURCES(0x20000),

    /** The content is a dex archive. It contains a single DEX file per class. */
    DEX_ARCHIVE(0x40000),
    ;

    private final int value;

    ExtendedContentType(int value) {
        this.value = value;
    }

    @Override
    public int getValue() {
        return value;
    }


    /**
     * Returns all {@link DefaultContentType} and {@link ExtendedContentType} content types.
     * @return a set of all known {@link ContentType}
     */
    public static Set<ContentType> getAllContentTypes() {
        return allContentTypes;
    }

    private static final Set<ContentType> allContentTypes;

    static {
        ImmutableSet.Builder<ContentType> builder = ImmutableSet.builder();
        for (DefaultContentType contentType : DefaultContentType.values()) {
            builder.add(contentType);
        }
        for (ExtendedContentType extendedContentType : ExtendedContentType.values()) {
            builder.add(extendedContentType);
        }
        allContentTypes = builder.build();
    }
}
