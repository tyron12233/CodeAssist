package com.tyron.layoutpreview;

import android.util.AttributeSet;
import android.util.Log;

public class PreviewAttributeSet implements AttributeSet {

    private AttributeSet mAttrs;

    public PreviewAttributeSet(AttributeSet attributeSet) {
        mAttrs = attributeSet;
    }

    @Override
    public int getAttributeCount() {
        return mAttrs.getAttributeCount();
    }

    @Override
    public String getAttributeName(int i) {
        return mAttrs.getAttributeName(i);
    }

    @Override
    public String getAttributeValue(int i) {
        String attr = mAttrs.getAttributeValue(i);
        Log.d("test", "Attribute value called " + attr);
        return attr;
    }

    @Override
    public String getAttributeValue(String s, String s1) {
        return mAttrs.getAttributeValue(s, s1);
    }

    @Override
    public String getPositionDescription() {
        return mAttrs.getPositionDescription();
    }

    @Override
    public int getAttributeNameResource(int i) {
        return mAttrs.getAttributeNameResource(i);
    }

    @Override
    public int getAttributeListValue(String s, String s1, String[] strings, int i) {
        return mAttrs.getAttributeListValue(s, s1, strings, i);
    }

    @Override
    public boolean getAttributeBooleanValue(String s, String s1, boolean b) {
        return mAttrs.getAttributeBooleanValue(s, s1, b);
    }

    @Override
    public int getAttributeResourceValue(String s, String s1, int i) {
        return mAttrs.getAttributeResourceValue(s, s1, i);
    }

    @Override
    public int getAttributeIntValue(String s, String s1, int i) {
        return mAttrs.getAttributeIntValue(s, s1, i);
    }

    @Override
    public int getAttributeUnsignedIntValue(String s, String s1, int i) {
        return mAttrs.getAttributeUnsignedIntValue(s, s1, i);
    }

    @Override
    public float getAttributeFloatValue(String s, String s1, float v) {
        return mAttrs.getAttributeFloatValue(s, s1, v);
    }

    @Override
    public int getAttributeListValue(int i, String[] strings, int i1) {
        return mAttrs.getAttributeListValue(i, strings, i1);
    }

    @Override
    public boolean getAttributeBooleanValue(int i, boolean b) {
        return mAttrs.getAttributeBooleanValue(i, b);
    }

    @Override
    public int getAttributeResourceValue(int i, int i1) {
        return mAttrs.getAttributeResourceValue(i, i1);
    }

    @Override
    public int getAttributeIntValue(int i, int i1) {
        return mAttrs.getAttributeIntValue(i, i1);
    }

    @Override
    public int getAttributeUnsignedIntValue(int i, int i1) {
        return getAttributeUnsignedIntValue(i, i1);
    }

    @Override
    public float getAttributeFloatValue(int i, float v) {
        return mAttrs.getAttributeFloatValue(i, v);
    }

    @Override
    public String getIdAttribute() {
        return mAttrs.getIdAttribute();
    }

    @Override
    public String getClassAttribute() {
        return mAttrs.getClassAttribute();
    }

    @Override
    public int getIdAttributeResourceValue(int i) {
        return mAttrs.getIdAttributeResourceValue(i);
    }

    @Override
    public int getStyleAttribute() {
        return mAttrs.getStyleAttribute();
    }
}
