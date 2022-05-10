package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Named;
import com.tyron.builder.internal.scan.UsedByScanPlugin;

/**
 * This attribute describes the categories of variants for a given module.
 * <p>
 * Three values are found in published components:
 * <ul>
 *     <li>{@code library}: Indicates that the variant is a library, that usually means a binary and a set of dependencies</li>
 *     <li>{@code platform}: Indicates that the variant is a platform, that usually means a definition of dependency constraints</li>
 *     <li>{@code documentation}: Indicates that the variant is documentation of the software module</li>
 * </ul>
 * One value is used for derivation. A {@code platform} variant can be consumed as a {@code enforced-platform} which means all the dependency
 * information it provides is applied as {@code forced}.
 *
 * @since 5.3
 */
@UsedByScanPlugin
public interface Category extends Named {

    Attribute<Category> CATEGORY_ATTRIBUTE = Attribute.of("com.tyron.builder.category", Category.class);

    /**
     * The library category
     */
    String LIBRARY = "library";

    /**
     * The platform category
     */
    String REGULAR_PLATFORM = "platform";

    /**
     * The enforced platform, usually a synthetic variant derived from the {@code platform}
     */
    String ENFORCED_PLATFORM = "enforced-platform";

    /**
     * The documentation category
     *
     * @since 5.6
     */
    String DOCUMENTATION = "documentation";

    /**
     * The source code category
     *
     * @since 7.4
     */
    @Incubating
    String SOURCES = "sources";
}
