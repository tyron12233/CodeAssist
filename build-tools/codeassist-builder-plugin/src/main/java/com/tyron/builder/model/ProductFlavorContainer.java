package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * A Container of all the data related to {@link ProductFlavor}.
 */
public interface ProductFlavorContainer {

    /**
     * The Product Flavor itself.
     *
     * @return the product flavor
     */
    @NotNull
    ProductFlavor getProductFlavor();

    /**
     * The associated main sources of the product flavor
     *
     * @return the main source provider.
     */
    @Nullable
    SourceProvider getSourceProvider();

    /**
     * Returns a list of ArtifactMetaData/SourceProvider association.
     *
     * @return a list of ArtifactMetaData/SourceProvider association.
     */
    @NotNull
    Collection<SourceProviderContainer> getExtraSourceProviders();
}