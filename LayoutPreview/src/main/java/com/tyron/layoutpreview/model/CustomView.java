package com.tyron.layoutpreview.model;

import android.view.View;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.List;

/**
 * Class used to represent custom views to the proteus layout inflater
 */
public class CustomView {

    public static CustomView fromJson(String json) throws JsonSyntaxException {
        return new Gson().fromJson(json, CustomView.class);
    }

    /** The fully qualified name of this view */
    private String type;

    /**
     * The parent type of this custom view, all views must have a parent
     * by default the parent is {@link android.view.View}
     *
     * This will be used to find methods if the current type doesn't provide one
     */
    private String parentType;

    private boolean isViewGroup;

    /**
     * List of attributes supported by this view, will be used later to set to set the attribtues
     * based on xml value
     */
    private List<Attribute> attributes;

    public CustomView() {

    }

    /**
     * Creates a class object based on the type
     * @param classLoader The class loader that will be used to load classes, useful for custom libraries
     * @return The class represented by this custom view
     */
    public Class<? extends View> getViewClass(ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> clazz = Class.forName(type, false, classLoader);
        return clazz.asSubclass(View.class);
    }

    /**
     * Get the JSON representation of this class
     */
    public String toJsonString() {
        return new Gson().toJson(this, CustomView.class);
    }

    public List<Attribute> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<Attribute> attributes) {
        this.attributes = attributes;
    }

    public String getParentType() {
        return parentType;
    }

    public void setParentType(String parentType) {
        this.parentType = parentType;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public boolean isViewGroup() {
        return isViewGroup;
    }

    public void setViewGroup(boolean viewGroup) {
        isViewGroup = viewGroup;
    }
}
