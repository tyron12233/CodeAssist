package com.tyron.layoutpreview.inflate;

import android.content.Context;
import android.view.InflateException;
import android.view.View;

import com.flipkart.android.proteus.Proteus;
import com.flipkart.android.proteus.ProteusBuilder;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.gson.ProteusTypeAdapterFactory;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;

import java.io.StringReader;

public class PreviewLayoutInflater {

    private final Context mBaseContext;
    private final Proteus mProteus;
    private final ProteusContext mContext;

    public PreviewLayoutInflater(Context base) {
        mBaseContext = base;
        mProteus = new ProteusBuilder()
                .build();
        mContext = mProteus.createContextBuilder(base)
                .build();

        ProteusTypeAdapterFactory.PROTEUS_INSTANCE_HOLDER.setProteus(mProteus);
    }

    public ProteusView inflate(String xml) throws InflateException {
        try {
            JsonObject object = new XmlToJsonConverter()
                    .convert(xml);
            return inflate(object);
        } catch (Exception e) {
            throw new InflateException("Unable to inflate layout: " + e.getMessage());
        }
    }

    /**
     * Convenience method to inflate a layout using a {@link JsonObject}
     * @param object The json object to inflate
     * @return The inflated view
     */
    public ProteusView inflate(JsonObject object) {
        try {
            return inflate(new ProteusTypeAdapterFactory(mContext)
                    .LAYOUT_TYPE_ADAPTER
                    .read(new JsonReader(new StringReader(object.toString()))));
        } catch (Exception e) {
            throw new InflateException("Unable to inflate layout: " + e.getMessage());
        }
    }

    public ProteusView inflate(Layout layout) {
        return mContext.getInflater().inflate(layout, new ObjectValue());
    }
}
