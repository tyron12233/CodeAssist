package com.tyron.builder.initialization;

import static com.tyron.builder.api.BuildProject.GRADLE_PROPERTIES;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.properties.GradleProperties;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.internal.Cast;

public class DefaultGradlePropertiesLoader implements IGradlePropertiesLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultGradlePropertiesLoader.class);

    private final StartParameterInternal startParameter;
    private final Environment environment;

    public DefaultGradlePropertiesLoader(StartParameterInternal startParameter, Environment environment) {
        this.startParameter = startParameter;
        this.environment = environment;
    }

    @Override
    public GradleProperties loadGradleProperties(File rootDir) {
        return loadProperties(rootDir);
    }

    GradleProperties loadProperties(File rootDir) {
        Map<String, Object> defaultProperties = new HashMap<>();
        Map<String, Object> overrideProperties = new HashMap<>();

        addGradlePropertiesFrom(startParameter.getGradleHomeDir(), defaultProperties);
        addGradlePropertiesFrom(rootDir, defaultProperties);
        addGradlePropertiesFrom(startParameter.getGradleUserHomeDir(), overrideProperties);

        // TODO:configuration-cache What happens when a system property is set from a Gradle property and
        //    that same system property is then used to set a Gradle property from an included build?
        //    e.g., included-build/gradle.properties << systemProp.org.gradle.project.fromSystemProp=42
        setSystemPropertiesFromGradleProperties(defaultProperties);
        setSystemPropertiesFromGradleProperties(overrideProperties);
        System.getProperties().putAll(startParameter.getSystemPropertiesArgs());

        overrideProperties.putAll(projectPropertiesFromEnvironmentVariables());
        overrideProperties.putAll(projectPropertiesFromSystemProperties());
        overrideProperties.putAll(startParameter.getProjectProperties());

        return new DefaultGradleProperties(defaultProperties, overrideProperties);
    }

    private void addGradlePropertiesFrom(File dir, Map<String, Object> target) {
        Map<String, String> propertiesFile = environment.propertiesFile(new File(dir, GRADLE_PROPERTIES));
        if (propertiesFile != null) {
            target.putAll(propertiesFile);
        }
    }

    private Map<String, String> projectPropertiesFromSystemProperties() {
        Map<String, String> systemProjectProperties = byPrefix(SYSTEM_PROJECT_PROPERTIES_PREFIX, environment.getSystemProperties());
        LOGGER.debug("Found system project properties: {}", systemProjectProperties.keySet());
        return systemProjectProperties;
    }

    private Map<String, String> projectPropertiesFromEnvironmentVariables() {
        Map<String, String> envProjectProperties = byPrefix(ENV_PROJECT_PROPERTIES_PREFIX, environment.getVariables());
        LOGGER.debug("Found env project properties: {}", envProjectProperties.keySet());
        return envProjectProperties;
    }

    private Map<String, String> byPrefix(String prefix, Environment.Properties properties) {
        return mapKeysRemovingPrefix(prefix, properties.byNamePrefix(prefix));
    }

    private void setSystemPropertiesFromGradleProperties(Map<String, Object> properties) {
        if (properties.isEmpty()) {
            return;
        }
        String prefix = BuildProject.SYSTEM_PROP_PREFIX + '.';
        int prefixLength = prefix.length();
        for (String key : properties.keySet()) {
            if (key.length() > prefixLength && key.startsWith(prefix)) {
                System.setProperty(key.substring(prefixLength), Cast.uncheckedNonnullCast(properties.get(key)));
            }
        }
    }

    private static Map<String, String> mapKeysRemovingPrefix(String prefix, Map<String, String> mapWithPrefix) {
        Map<String, String> mapWithoutPrefix = new HashMap<>(mapWithPrefix.size());
        for (Map.Entry<String, String> entry : mapWithPrefix.entrySet()) {
            mapWithoutPrefix.put(
                    entry.getKey().substring(prefix.length()),
                    entry.getValue()
            );
        }
        return mapWithoutPrefix;
    }
}