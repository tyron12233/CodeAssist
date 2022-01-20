package com.tyron.actions;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Controls how an action would look in the UI
 */
public class Presentation {

    public static final Supplier<String> NULL_STRING = () -> null;

    /**
     * value: String
     */
    public static final String PROP_DESCRIPTION = " description";
    /**
     * value: Drawable
     */
    public static final String PROP_ICON = "icon";
    /**
     * value: Boolean
     */
    public static final String PROP_VISIBLE = "visible";
    /**
     * value: Boolean
     */
    public static final String PROP_ENABLED = "enabled";

    private Drawable mIcon;
    private boolean mIsVisible;
    private boolean mIsEnabled = true;

    private Supplier<String> mTextSupplier = NULL_STRING;
    private Supplier<String> mDescriptionSupplier = NULL_STRING;

    private PropertyChangeSupport mChangeSupport;

    public Presentation() {

    }

    public Presentation(Supplier<String> dynamicText) {
        mTextSupplier = dynamicText;
    }

    public static Presentation createTemplatePresentation() {
        return new Presentation();
    }

    public void addPropertyChangeListener(@NonNull PropertyChangeListener listener) {
        PropertyChangeSupport support = mChangeSupport;
        if (support == null) {
            mChangeSupport = support = new PropertyChangeSupport(this);
        }
        support.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(@NonNull PropertyChangeListener listener) {
        PropertyChangeSupport support = mChangeSupport;
        if (support != null) {
            mChangeSupport.removePropertyChangeListener(listener);
        }
    }

    public String getText() {
        return mTextSupplier.get();
    }

    public void setText(@Nullable String text) {
        setText(() -> text);
    }

    public void setText(@NonNull Supplier<String> text) {
        mTextSupplier = text;
    }

    public void setDescription(@NonNull Supplier<String> description) {
        Supplier<String> oldDescription = mDescriptionSupplier;
        mDescriptionSupplier = description;
        fireObjectPropertyChange(PROP_DESCRIPTION, oldDescription, mDescriptionSupplier);
    }

    private void fireObjectPropertyChange(String propertyName, Object oldValue, Object newValue) {
//        PropertyChangeSupport support = mChangeSupport;
//        if (support != null && !Objects.equals(oldValue, newValue)) {
//            support.firePropertyChange(propertyName, oldValue, newValue);
//        }
    }

    private void fireBooleanPropertyChange(String propertyName, boolean oldValue, boolean newValue) {
//        PropertyChangeSupport support = mChangeSupport;
//        if (support != null && oldValue != newValue) {
//            support.firePropertyChange(propertyName, oldValue, newValue);
//        }
    }

    @NonNull
    @Override
    public Presentation clone() {
        try {
            return (Presentation) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public void setIcon(Drawable icon) {
        mIcon = icon;
    }

    public Drawable getIcon() {
        return mIcon;
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public void setEnabled(boolean enabled) {
        boolean old = mIsEnabled;
        fireBooleanPropertyChange(PROP_ENABLED, old, enabled);
        mIsEnabled = enabled;
    }

    public void setVisible(boolean visible) {
        boolean old = mIsVisible;
        fireBooleanPropertyChange(PROP_VISIBLE, old, visible);
        mIsVisible = visible;
    }

    public String getDescription() {
        return mDescriptionSupplier.get();
    }
}
