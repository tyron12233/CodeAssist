package com.tyron.layoutpreview.convert.adapter;

import com.flipkart.android.proteus.value.Value;

/**
 * CustomValueTypeAdapterCreator
 *
 * @author adityasharat
 */
public abstract class CustomValueTypeAdapterCreator<V extends Value> {

    public abstract CustomValueTypeAdapter<V> create(int type, ProteusTypeAdapterFactory factory);

}