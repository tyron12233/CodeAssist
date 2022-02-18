package com.tyron.completion.xml.repository.api;

import android.util.Pair;

import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

public class LayoutInfo {

    private String mName;

    private List<Pair<String, String>> mAttributes;

    private List<LayoutInfo> mChildren;

    public LayoutInfo() {

    }

    public LayoutInfo(String tag) {
        mName = tag;
    }

    /**
     * Return the name of this layout, this name is the xml tag and will be used
     * to load the view class
     */
    public String getName() {
        return mName;
    }

    public ImmutableList<Pair<String, String>> getAttributes() {
        return ImmutableList.copyOf(mAttributes);
    }

    /**
     * Return the child layout infos of this view, may return null
     * if the view is not a ViewGroup
     */
    @Nullable
    public ImmutableList<LayoutInfo> getChildren() {
        return ImmutableList.copyOf(mChildren);
    }

    public void addAttribute(String name, String value) {
        if (mAttributes == null) {
            mAttributes = new ArrayList<>();
        }
        mAttributes.add(Pair.create(name, value));
    }

    public void addChild(LayoutInfo child) {
        if (mChildren == null) {
            mChildren = new ArrayList<>();
        }
        mChildren.add(child);
    }
}
