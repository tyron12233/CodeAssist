package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.properties.GradleProperties;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Map;

public class DefaultGradlePropertiesController implements GradlePropertiesController {

    private State state = new NotLoaded();
    private final GradleProperties sharedGradleProperties = new SharedGradleProperties();
    private final IGradlePropertiesLoader propertiesLoader;

    public DefaultGradlePropertiesController(IGradlePropertiesLoader propertiesLoader) {
        this.propertiesLoader = propertiesLoader;
    }

    @Override
    public GradleProperties getGradleProperties() {
        return sharedGradleProperties;
    }

    @Override
    public void loadGradlePropertiesFrom(File settingsDir) {
        state = state.loadGradlePropertiesFrom(settingsDir);
    }

    @Override
    public void unloadGradleProperties() {
        state = new NotLoaded();
    }

    public void overrideWith(GradleProperties gradleProperties) {
        state = state.overrideWith(gradleProperties);
    }

    private class SharedGradleProperties implements GradleProperties {

        @Nullable
        @Override
        public Object find(String propertyName) {
            return gradleProperties().find(propertyName);
        }

        @Override
        public Map<String, Object> mergeProperties(Map<String, Object> properties) {
            return gradleProperties().mergeProperties(properties);
        }

        private GradleProperties gradleProperties() {
            return state.gradleProperties();
        }
    }

    private interface State {

        GradleProperties gradleProperties();

        State loadGradlePropertiesFrom(File settingsDir);

        State overrideWith(GradleProperties gradleProperties);
    }

    private class NotLoaded implements State {

        @Override
        public GradleProperties gradleProperties() {
            throw new IllegalStateException("GradleProperties has not been loaded yet.");
        }

        @Override
        public State loadGradlePropertiesFrom(File settingsDir) {
            return new Loaded(propertiesLoader.loadGradleProperties(settingsDir), settingsDir);
        }

        @Override
        public State overrideWith(GradleProperties gradleProperties) {
            return new Overridden(gradleProperties);
        }
    }

    private static class Loaded implements State {

        private final GradleProperties gradleProperties;
        private final File propertiesDir;

        public Loaded(GradleProperties gradleProperties, File propertiesDir) {
            this.gradleProperties = gradleProperties;
            this.propertiesDir = propertiesDir;
        }

        @Override
        public GradleProperties gradleProperties() {
            return gradleProperties;
        }

        @Override
        public State loadGradlePropertiesFrom(File settingsDir) {
            if (!propertiesDir.equals(settingsDir)) {
                throw new IllegalStateException(String.format(
                        "GradleProperties has already been loaded from '%s' and cannot be loaded from '%s'.",
                        propertiesDir, settingsDir));
            }
            return this;
        }

        @Override
        public State overrideWith(GradleProperties gradleProperties) {
            throw new IllegalStateException(
                    "GradleProperties has already been loaded and cannot be overridden.");
        }
    }

    private static class Overridden implements State {

        private final GradleProperties gradleProperties;

        public Overridden(GradleProperties gradleProperties) {
            this.gradleProperties = gradleProperties;
        }

        @Override
        public GradleProperties gradleProperties() {
            return gradleProperties;
        }

        @Override
        public State loadGradlePropertiesFrom(File settingsDir) {
            throw new IllegalStateException();
        }

        @Override
        public State overrideWith(GradleProperties gradleProperties) {
            return new Overridden(gradleProperties);
        }
    }
}