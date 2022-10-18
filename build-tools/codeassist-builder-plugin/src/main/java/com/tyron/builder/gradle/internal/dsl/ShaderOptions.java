package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;

/** Implementation of CoreShaderOptions for usage in the non-model based Gradle plugin DSL. */
public class ShaderOptions implements CoreShaderOptions, com.tyron.builder.api.dsl.Shaders {

    List<String> args = Lists.newArrayListWithExpectedSize(2);
    ListMultimap<String, String> scopedArgs = ArrayListMultimap.create();

    @Inject
    public ShaderOptions() {}

    @NonNull
    @Override
    public List<String> getGlslcArgs() {
        return args;
    }

    @NonNull
    @Override
    public ListMultimap<String, String> getScopedGlslcArgs() {
        return scopedArgs;
    }

    @Override
    public void glslcArgs(@NonNull String... options) {
        this.args.addAll(Arrays.asList(options));
    }

    @Override
    public void glslcScopedArgs(@NonNull String key, @NonNull String... options) {
        this.scopedArgs.putAll(key, Arrays.asList(options));
    }

    void _initWith(@NonNull CoreShaderOptions that) {
        args.clear();
        args.addAll(that.getGlslcArgs());

        scopedArgs.clear();
        scopedArgs.putAll(that.getScopedGlslcArgs());
    }
}
