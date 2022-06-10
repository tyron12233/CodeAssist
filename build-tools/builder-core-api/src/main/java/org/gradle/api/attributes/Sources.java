package org.gradle.api.attributes;

import org.gradle.api.Incubating;
import org.gradle.api.Named;

/**
 * Attributes to specific the type of source code contained in this variant.
 * <p>
 * This attribute is usually found on variants that have the {@link Category} attribute valued at {@link Category#SOURCES documentation}.
 *
 * @since 7.4
 */
@Incubating
public interface Sources extends Named {
    Attribute<Sources> SOURCES_ATTRIBUTE = Attribute.of("org.gradle.sources", Sources.class);

    /**
     * A list of directories containing source code, includes code in transitive dependencies
     */
    String ALL_SOURCE_DIRS = "all-source-directories";
}
