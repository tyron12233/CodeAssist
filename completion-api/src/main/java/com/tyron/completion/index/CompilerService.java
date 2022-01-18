package com.tyron.completion.index;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;

import java.util.LinkedHashMap;
import java.util.Map;

public class CompilerService {

    private static CompilerService sInstance = null;

    public static CompilerService getInstance() {
        if (sInstance == null) {
            sInstance = new CompilerService();
        }
        return sInstance;
    }

    private final Map<String, CompilerProvider<?>> mIndexProviders;

    public CompilerService() {
        mIndexProviders = new LinkedHashMap<>();
    }

    public <T> void registerIndexProvider(String key, CompilerProvider<T> provider) {
        mIndexProviders.put(key, provider);
    }

    public <T> T getIndex(String key) {
        //noinspection unchecked
        return (T) mIndexProviders.get(key);
    }

    public void index(Project project, Module module) {
        for (CompilerProvider<?> provider : mIndexProviders.values()) {
            provider.get(project, module);
        }
    }

    public void clear() {
        mIndexProviders.clear();
    }

    public boolean isEmpty() {
        return mIndexProviders.isEmpty();
    }
}
