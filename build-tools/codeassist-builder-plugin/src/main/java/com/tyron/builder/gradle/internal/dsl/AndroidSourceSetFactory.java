package com.tyron.builder.gradle.internal.dsl;

import com.tyron.builder.api.dsl.AndroidSourceSet;
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet;
import com.tyron.builder.gradle.internal.services.DslServices;

import org.gradle.api.NamedDomainObjectFactory;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory to create AndroidSourceSet object using an {@link ObjectFactory} to add the DSL methods.
 */
public class AndroidSourceSetFactory implements NamedDomainObjectFactory<AndroidSourceSet> {

    @NotNull
    private final Project project;
    private final boolean publishPackage;
    @NotNull private final DslServices dslServices;

    /**
     * Constructor for this AndroidSourceSetFactory.
     *
     * @param project the project for this AndroidSourceSetFactory.
     * @param publishPackage true to set the package name to "publish", false to set it to "apk".
     * @param dslServices dslServices of the project.
     */
    public AndroidSourceSetFactory(
            @NotNull Project project, boolean publishPackage, @NotNull DslServices dslServices) {
        this.publishPackage = publishPackage;
        this.project = project;
        this.dslServices = dslServices;
    }

    @NotNull
    @Override
    public AndroidSourceSet create(@NotNull String name) {
        return dslServices.newInstance(
                DefaultAndroidSourceSet.class, name, project, publishPackage);
    }
}