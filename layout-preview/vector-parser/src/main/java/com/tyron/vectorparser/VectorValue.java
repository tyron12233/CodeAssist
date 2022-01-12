package com.tyron.vectorparser;

import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusLayoutInflater;
import com.flipkart.android.proteus.value.DrawableValue;

import org.xmlpull.v1.XmlPullParserException;

public class VectorValue extends DrawableValue {

    private final String contents;

    public VectorValue(String contents) {
        this.contents = contents;
    }

    @Override
    public void apply(View view, ProteusContext context, ProteusLayoutInflater.ImageLoader loader
            , Callback callback) {
        DynamicVectorDrawable dynamicVectorDrawable = new DynamicVectorDrawable(context);
        try {
            dynamicVectorDrawable.setContents(contents);
            dynamicVectorDrawable.invalidateSelf();
            callback.apply(dynamicVectorDrawable);
        } catch (XmlPullParserException e) {
            e.printStackTrace();
        }
    }
}
