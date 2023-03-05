package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.tyron.builder.gradle.internal.services.DslServices;
import org.gradle.api.NamedDomainObjectFactory;
import org.gradle.api.model.ObjectFactory;

/** Factory to create ProductFlavor object using an {@link ObjectFactory} to add the DSL methods. */
public class ProductFlavorFactory implements NamedDomainObjectFactory<ProductFlavor> {

    @NonNull private final DslServices dslServices;

    public ProductFlavorFactory(@NonNull DslServices dslServices) {
        this.dslServices = dslServices;
    }

    @NonNull
    @Override
    public ProductFlavor create(@NonNull String name) {
        return dslServices.newDecoratedInstance(ProductFlavor.class, name, dslServices);
    }
}
