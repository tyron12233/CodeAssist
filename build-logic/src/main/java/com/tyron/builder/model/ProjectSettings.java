package com.tyron.builder.model;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

@SuppressWarnings({"unchecked", "ConstantConditions"})
public class ProjectSettings implements SharedPreferences {

    /**
     * Commonly used keys for project settings
     */
    public static final String LIBRARY_LIST = "libraries";
    public static final String SAVED_EDITOR_FILES = "editor_opened_files";
    public static final String USE_R8 = "useR8";

    private final File mConfigFile;
    private final Map<String, Object> mConfigMap;

    public ProjectSettings(File configFile) {
        mConfigFile = configFile;
        mConfigMap = parseFile();
    }

    private Map<String, Object> parseFile() {
        HashMap<String, Object> config = null;
        try {
            config = new Gson().fromJson(new FileReader(mConfigFile),
                    new TypeToken<HashMap<String, Object>>(){}.getType());
        } catch (FileNotFoundException ignore) {

        }
        return config == null ? getDefaults() : config;
    }

    private Map<String, Object> getDefaults() {
        Map<String, Object> map = new HashMap<>();
        map.put(USE_R8, false);
        return map;
    }

    public void refresh() {
        mConfigMap.clear();
        mConfigMap.putAll(parseFile());
    }

    @Override
    public Map<String, ?> getAll() {
        return mConfigMap;
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String def) {
        return (String) mConfigMap.getOrDefault(key, def);
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String s, @Nullable Set<String> set) {
        return (Set<String>) mConfigMap.getOrDefault(s, set);
    }

    @Override
    public int getInt(String s, int i) {
        return (int) mConfigMap.getOrDefault(s, i);
    }

    @Override
    public long getLong(String s, long l) {
        return (long) mConfigMap.getOrDefault(s, l);
    }

    @Override
    public float getFloat(String s, float v) {
        return (float) mConfigMap.getOrDefault(s, v);
    }

    @Override
    public boolean getBoolean(String s, boolean b) {
        return (boolean) mConfigMap.getOrDefault(s, b);
    }

    @Override
    public boolean contains(String s) {
        return mConfigMap.containsKey(s);
    }

    @Override
    public Editor edit() {
        return new Editor(mConfigFile, parseFile());
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {

    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {

    }

    public static class Editor implements SharedPreferences.Editor {

        private final File mConfigFile;
        private final Map<String, Object> mValues;

        public Editor(File file, Map<String, Object> values) {
            mConfigFile = file;
            mValues = values;
        }

        @Override
        public SharedPreferences.Editor putString(String s, @Nullable String s1) {
            mValues.put(s, s1);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String s, @Nullable Set<String> set) {
            mValues.put(s, set);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String s, int i) {
            mValues.put(s, i);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String s, long l) {
            mValues.put(s, l);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String s, float v) {
            mValues.put(s, v);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String s, boolean b) {
            mValues.put(s, b);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String s) {
            mValues.remove(s);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            mValues.clear();
            return this;
        }

        @Override
        public boolean commit() {
            String json;
            try {
                Gson gson = new GsonBuilder()
                        .setPrettyPrinting()
                        .create();
                json = gson.toJson(mValues);
                FileUtils.writeStringToFile(mConfigFile, json, StandardCharsets.UTF_8);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void apply() {
            Executors.newSingleThreadExecutor().execute(this::commit);
        }
    }
}
