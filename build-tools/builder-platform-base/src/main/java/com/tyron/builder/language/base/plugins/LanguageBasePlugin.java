package com.tyron.builder.language.base.plugins;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.internal.model.RuleSource;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.language.base.LanguageSourceSet;

/**
 * Base plugin for language support.
 *
 * - Adds a {@link ProjectSourceSet} named {@code sources} to the project.
 * - Registers the base {@link LanguageSourceSet} type.
 */
@Incubating
public class LanguageBasePlugin implements Plugin<BuildProject> {
    @Override
    public void apply(BuildProject project) {
//        project.getPluginManager().apply(ComponentBasePlugin.class);
    }

//    @SuppressWarnings("UnusedDeclaration")
//    static class Rules extends RuleSource {
//        @ComponentType
//        void registerBaseLanguageSourceSet(TypeBuilder<LanguageSourceSet> builder) {
//            builder.defaultImplementation(BaseLanguageSourceSet.class);
//            builder.internalView(LanguageSourceSetInternal.class);
//        }
//
//        @Model
//        ProjectSourceSet sources(Instantiator instantiator, CollectionCallbackActionDecorator decorator) {
//            return instantiator.newInstance(DefaultProjectSourceSet.class, decorator);
//        }
//    }
}
