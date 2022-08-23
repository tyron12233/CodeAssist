package com.tyron.completion.java;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.util.PackageTrie;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Allows to retrieve java classes in a project by non-qualified names
 */
public class ShortNamesCache {

    private static final Map<Project, ShortNamesCache> map = new WeakHashMap<>();

    public static ShortNamesCache getInstance(Project project) {
        ShortNamesCache cache = map.get(project);
        if (cache == null) {
            cache = new ShortNamesCache(project);
            map.put(project, cache);
        }
        return cache;
    }

    private final Project project;

    public ShortNamesCache(Project project) {
        this.project = project;
    }

    /**
     * Returns the list of fully qualified names of all classes in the project and (optionally) libraries.
     */
    public String[] getAllClassNames() {
        Module mainModule = project.getMainModule();
        if (!(mainModule instanceof JavaModule)) {
            return new String[0];
        }
        JavaModule javaModule = (JavaModule) mainModule;
        PackageTrie classIndex = javaModule.getClassIndex();
        return classIndex.getLeafNodes().toArray(new String[0]);
    }
}
