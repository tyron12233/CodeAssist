package com.tyron.code.ui.layoutEditor.model;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.flipkart.android.proteus.value.Value;
import com.google.errorprone.annotations.Immutable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an item that can be drag and dropped to the editor
 */
@Immutable
public class ViewPalette {

    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    private final String mClassName;
    private final String mName;
    private final int mIcon;
    private final Map<String, Value> mDefaultValues;

    private ViewPalette(String className,
                        String name,
                        @DrawableRes int icon,
                        Map<String, Value> defaultValues) {
        mClassName = className;
        mName = name;
        mIcon = icon;
        mDefaultValues = defaultValues;
    }

    /**
     * @return The class name that will be used to inflate the view
     */
    public String getClassName() {
        return mClassName;
    }

    public String getName() {
        return mName;
    }

    @DrawableRes
    public int getIcon() {
        return mIcon;
    }

    /**
     * @return The default values that can be used when this palette is inflated
     */
    public Map<String, Value> getDefaultValues() {
        return mDefaultValues;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ViewPalette)) return false;
        ViewPalette that = (ViewPalette) o;
        return mIcon == that.mIcon && mClassName.equals(that.mClassName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mClassName, mIcon);
    }

    public static class Builder {
        private String className;
        private String name;
        private int icon;
        private final Map<String, Value> defaultValues = new HashMap<>();

        public Builder setClassName(String name) {
            this.className = name;
            return this;
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setIcon(@DrawableRes int icon) {
            this.icon = icon;
            return this;
        }

        public Builder addDefaultValue(@NonNull String name, @NonNull Value value) {
            this.defaultValues.put(name, value);
            return this;
        }

        public ViewPalette build() {
            return new ViewPalette(className, name, icon, defaultValues);
        }
    }
}
