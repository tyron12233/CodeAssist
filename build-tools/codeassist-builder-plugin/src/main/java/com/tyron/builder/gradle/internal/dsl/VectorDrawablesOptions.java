package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.tyron.builder.core.DefaultVectorDrawablesOptions;
import java.util.Arrays;

public class VectorDrawablesOptions extends DefaultVectorDrawablesOptions
        implements com.tyron.builder.api.dsl.VectorDrawables {

    @Override
    public void generatedDensities(@NonNull String... densities) {
        setGeneratedDensities(Arrays.asList(densities));
    }

    @NonNull
    public static VectorDrawablesOptions copyOf(
            @NonNull com.tyron.builder.model.VectorDrawablesOptions original) {
        VectorDrawablesOptions options = new VectorDrawablesOptions();

        options.setGeneratedDensities(original.getGeneratedDensities());
        options.setUseSupportLibrary(original.getUseSupportLibrary());

        return options;
    }
}
