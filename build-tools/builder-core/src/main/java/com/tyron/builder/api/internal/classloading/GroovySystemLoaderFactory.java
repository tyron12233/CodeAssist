package com.tyron.builder.api.internal.classloading;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.util.internal.VersionNumber;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class GroovySystemLoaderFactory {
    private static final NoOpGroovySystemLoader NO_OP = new NoOpGroovySystemLoader();

    public GroovySystemLoader forClassLoader(ClassLoader classLoader) {
        try {
            Class<?> groovySystem = getGroovySystem(classLoader);
            if (groovySystem == null || groovySystem.getClassLoader() != classLoader) {
                return NO_OP;
            }
            GroovySystemLoader classInfoCleaningLoader = createClassInfoCleaningLoader(groovySystem, classLoader);
            GroovySystemLoader preferenceCleaningLoader = new PreferenceCleaningGroovySystemLoader(classLoader);
            return new CompositeGroovySystemLoader(classInfoCleaningLoader, preferenceCleaningLoader);
        } catch (Exception e) {
            throw new BuildException("Could not inspect the Groovy system for ClassLoader " + classLoader, e);
        }
    }

    private Class<?> getGroovySystem(ClassLoader classLoader) {
        try {
            return classLoader.loadClass("groovy.lang.GroovySystem");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private GroovySystemLoader createClassInfoCleaningLoader(Class<?> groovySystem, ClassLoader classLoader) throws Exception {
        VersionNumber groovyVersion = getGroovyVersion(groovySystem);
        return isGroovy24OrLater(groovyVersion) ? new ClassInfoCleaningGroovySystemLoader(classLoader) : NO_OP;
    }

    private VersionNumber getGroovyVersion(Class<?> groovySystem) throws IllegalAccessException, InvocationTargetException {
        try {
            Method getVersion = groovySystem.getDeclaredMethod("getVersion");
            String versionString = (String) getVersion.invoke(null);
            return VersionNumber.parse(versionString);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private boolean isGroovy24OrLater(VersionNumber groovyVersion) {
        if (groovyVersion == null) {
            return false;
        }
        return groovyVersion.getMajor() == 2 && groovyVersion.getMinor() >= 4 || groovyVersion.getMajor() > 2;
    }
}
