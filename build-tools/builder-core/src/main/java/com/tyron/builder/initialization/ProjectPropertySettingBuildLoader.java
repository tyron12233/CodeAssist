package com.tyron.builder.initialization;

import static java.util.Collections.emptyMap;

import com.google.common.collect.Maps;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.properties.GradleProperties;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.reflect.JavaPropertyReflectionUtil;
import com.tyron.builder.internal.reflect.PropertyMutator;
import com.tyron.builder.internal.resource.local.FileResourceListener;
import com.tyron.builder.util.GUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;
import java.util.Properties;

import javax.annotation.Nullable;

public class ProjectPropertySettingBuildLoader implements BuildLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectPropertySettingBuildLoader.class);

    private final GradleProperties gradleProperties;
    private final FileResourceListener fileResourceListener;
    private final BuildLoader buildLoader;

    public ProjectPropertySettingBuildLoader(GradleProperties gradleProperties, BuildLoader buildLoader, FileResourceListener fileResourceListener) {
        this.buildLoader = buildLoader;
        this.gradleProperties = gradleProperties;
        this.fileResourceListener = fileResourceListener;
    }

    @Override
    public void load(SettingsInternal settings, GradleInternal gradle) {
        buildLoader.load(settings, gradle);
        BuildProject rootProject = gradle.getRootProject();
        setProjectProperties(rootProject, new CachingPropertyApplicator(rootProject.getClass()));
    }

    private void setProjectProperties(BuildProject project, CachingPropertyApplicator applicator) {
        addPropertiesToProject(project, applicator);
        for (BuildProject childProject : project.getChildProjects().values()) {
            setProjectProperties(childProject, applicator);
        }
    }

    private void addPropertiesToProject(BuildProject project, CachingPropertyApplicator applicator) {
        File projectPropertiesFile = new File(project.getProjectDir(), BuildProject.GRADLE_PROPERTIES);
        LOGGER.debug("Looking for project properties from: {}", projectPropertiesFile);
        fileResourceListener.fileObserved(projectPropertiesFile);
        if (projectPropertiesFile.isFile()) {
            Properties projectProperties = GUtil.loadProperties(projectPropertiesFile);
            LOGGER.debug("Adding project properties (if not overwritten by user properties): {}",
                    projectProperties.keySet());
            configurePropertiesOf(project, applicator, Cast.uncheckedCast(projectProperties));
        } else {
            LOGGER.debug("project property file does not exists. We continue!");
            configurePropertiesOf(project, applicator, emptyMap());
        }
    }

    // {@code mergedProperties} should really be <String, Object>, however properties loader signature expects a <String, String>
    // even if in practice it was never enforced (one can pass other property types, such as boolean) and
    // fixing the method signature would be a binary breaking change in a public API.
    private void configurePropertiesOf(BuildProject project, CachingPropertyApplicator applicator, Map<String, Object> properties) {
        for (Map.Entry<String, Object> entry : gradleProperties.mergeProperties(properties).entrySet()) {
            applicator.configureProperty(project, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Applies the given properties to the project and its subprojects, caching property mutators whenever possible
     * to avoid too many searches.
     */
    private static class CachingPropertyApplicator {
        private final Class<? extends BuildProject> projectClass;
        private final Map<Pair<String, ? extends Class<?>>, PropertyMutator> mutators = Maps
                .newHashMap();

        CachingPropertyApplicator(Class<? extends BuildProject> projectClass) {
            this.projectClass = projectClass;
        }

        void configureProperty(BuildProject project, String name, @Nullable Object value) {
            if (isPossibleProperty(name)) {
                assert project.getClass() == projectClass;
                PropertyMutator propertyMutator = propertyMutatorFor(name, typeOf(value));
                if (propertyMutator != null) {
                    propertyMutator.setValue(project, value);
                } else {
                    setExtraPropertyOf(project, name, value);
                }
            }
        }

        private void setExtraPropertyOf(BuildProject project, String name, @Nullable Object value) {
//            project.getExtensions().getExtraProperties().set(name, value);
        }

        @Nullable
        private Class<?> typeOf(@Nullable Object value) {
            return value == null ? null : value.getClass();
        }

        @Nullable
        private PropertyMutator propertyMutatorFor(String propertyName, @Nullable Class<?> valueType) {
            final Pair<String, ? extends Class<?>> key = Pair.of(propertyName, valueType);
            final PropertyMutator cached = mutators.get(key);
            if (cached != null) {
                return cached;
            }
            if (mutators.containsKey(key)) {
                return null;
            }
            final PropertyMutator mutator = JavaPropertyReflectionUtil.writeablePropertyIfExists(projectClass, propertyName, valueType);
            mutators.put(key, mutator);
            return mutator;
        }

        /**
         * In a properties file, entries like '=' or ':' on a single line define a property with an empty string name and value.
         * We know that no property will have an empty property name.
         *
         * @see java.util.Properties#load(java.io.Reader)
         */
        private boolean isPossibleProperty(String name) {
            return !name.isEmpty();
        }
    }
}
