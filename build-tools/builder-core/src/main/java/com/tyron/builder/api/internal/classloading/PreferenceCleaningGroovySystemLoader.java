package com.tyron.builder.api.internal.classloading;

import com.tyron.builder.api.BuildException;

import java.lang.reflect.Field;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.PreferenceChangeListener;
import java.util.prefs.Preferences;

public class PreferenceCleaningGroovySystemLoader implements GroovySystemLoader {

    private final Field prefListenerField;
    private final ClassLoader leakingLoader;

    public PreferenceCleaningGroovySystemLoader(ClassLoader leakingLoader) throws Exception {
        this.leakingLoader = leakingLoader;
        prefListenerField = AbstractPreferences.class.getDeclaredField("prefListeners");
        prefListenerField.setAccessible(true);
    }

    @Override
    public void shutdown() {
        try {
            Preferences groovyNode =
                    Preferences.userRoot().node("/org/codehaus/groovy/tools/shell");
            PreferenceChangeListener[] prefListeners =
                    (PreferenceChangeListener[]) prefListenerField.get(groovyNode);
            if (prefListeners == null) {
                return;
            }
            for (PreferenceChangeListener prefListener : prefListeners) {
                ClassLoader prefListenerLoader = prefListener.getClass().getClassLoader();
                if (leakingLoader == prefListenerLoader) {
                    groovyNode.removePreferenceChangeListener(prefListener);
                }
            }
        } catch (Exception e) {
            throw new BuildException("Could not shut down the Groovy system for " + leakingLoader,
                    e);
        }
    }

    @Override
    public void discardTypesFrom(ClassLoader classLoader) {

    }
}
