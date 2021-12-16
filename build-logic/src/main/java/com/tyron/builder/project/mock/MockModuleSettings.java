package com.tyron.builder.project.mock;

import androidx.annotation.Nullable;

import com.tyron.builder.model.ModuleSettings;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mock project settings that does not rely on app_config.json
 */
public class MockModuleSettings extends ModuleSettings {

    private final Map<String, Object> mMap = new HashMap<>();

    public MockModuleSettings() {
        this(new File(""));
    }

    private MockModuleSettings(File configFile) {
        super(configFile);
    }

    @Override
    public void refresh() {

    }

    @Override
    public Map<String, ?> getAll() {
        return mMap;
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String def) {
        return (String) mMap.getOrDefault(key, def);
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String s, @Nullable Set<String> set) {
        return (Set<String>) mMap.getOrDefault(s, set);
    }

    @Override
    public int getInt(String s, int i) {
        return (int) mMap.getOrDefault(s, i);
    }

    @Override
    public long getLong(String s, long l) {
        return (long) mMap.getOrDefault(s, l);
    }

    @Override
    public float getFloat(String s, float v) {
        return (float) mMap.getOrDefault(s, v);
    }

    @Override
    public boolean getBoolean(String s, boolean b) {
        return (boolean) mMap.getOrDefault(s, b);
    }

    @Override
    public boolean contains(String s) {
        return mMap.containsKey(s);
    }

    @Override
    public Editor edit() {
        return new MockEditor();
    }


    private class MockEditor extends Editor {

        public MockEditor() {
            this(null, mMap);
        }

        private MockEditor(File file, Map<String, Object> values) {
            super(file, values);
        }

        @Override
        public boolean commit() {
            return true;
        }
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
        super.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
        super.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
    }
}
