package com.tyron.code.ui.layoutEditor.model;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.google.errorprone.annotations.Immutable;

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

    private ViewPalette(String className, String name, @DrawableRes int icon) {
        mClassName = className;
        mName = name;
        mIcon = icon;
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

        public ViewPalette build() {
            return new ViewPalette(className, name, icon);
        }
    }
}
