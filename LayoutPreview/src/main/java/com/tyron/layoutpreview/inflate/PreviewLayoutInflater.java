package com.tyron.layoutpreview.inflate;

import android.content.Context;
import android.util.Log;
import android.view.InflateException;

import com.flipkart.android.proteus.Proteus;
import com.flipkart.android.proteus.ProteusBuilder;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusLayoutInflater;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.SimpleIdGenerator;
import com.flipkart.android.proteus.SimpleLayoutInflater;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;
import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.model.Format;
import com.tyron.layoutpreview.parser.CustomViewGroupParser;
import com.tyron.layoutpreview.parser.CustomViewParser;
import com.tyron.layoutpreview.view.UnknownView;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;

public class PreviewLayoutInflater {

    private final Context mBaseContext;
    private final Proteus mProteus;
    private final ProteusContext mContext;

    private final ProteusLayoutInflater.Callback mCallback = new ProteusLayoutInflater.Callback() {
        @Override
        public ProteusView onUnknownViewType(ProteusContext context, String type, Layout layout, ObjectValue data, int index) {
            return new UnknownView(context, type);
        }

        @Override
        public void onEvent(String event, Value value, ProteusView view) {

        }
    };

    public PreviewLayoutInflater(Context base) {
        mBaseContext = base;
        mProteus = new ProteusBuilder()
                .register(new CustomViewParser(getTestView()))
                .register(new CustomViewGroupParser(getConstraint()))
                .build();
        mContext = mProteus.createContextBuilder(base)
                .setCallback(mCallback)
                .build();

        ProteusTypeAdapterFactory.PROTEUS_INSTANCE_HOLDER.setProteus(mProteus);
    }

    // for testing only
    private CustomView getConstraint() {
        CustomView view = new CustomView();
        view.setType("androidx.constraintlayout.widget.ConstraintLayout");
        view.setParentType("ViewGroup");

        Attribute leftToLeft = Attribute.builder()
                .setLayoutParams(true)
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintLeft_toLeftOf")
                .setMethodName("leftToLeft")
                .setParameters(int.class)
                .build();

        Attribute rightToRight = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintRight_toRightOf")
                .setMethodName("rightToRight")
                .setParameters(int.class)
                .build();

        Attribute rightToLeft = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintRight_toLeftOf")
                .setMethodName("rightToLeft")
                .setParameters(int.class)
                .build();

        Attribute leftToRight = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintLeft_toRightOf")
                .setMethodName("leftToRight")
                .setParameters(int.class)
                .build();

        Attribute topToTop = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintTop_toTopOf")
                .setMethodName("topToTop")
                .setParameters(int.class)
                .build();

        Attribute topToBottom = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintTop_toBottomOf")
                .setMethodName("topToBottom")
                .setParameters(int.class)
                .build();

        Attribute bottomToTop = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintBottom_toTopOf")
                .setMethodName("bottomToTop")
                .setParameters(int.class)
                .build();

        Attribute bottomToBottom = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintBottom_toBottomOf")
                .setMethodName("bottomToBottom")
                .setParameters(int.class)
                .build();


        view.setAttributes(Arrays.asList(leftToLeft, rightToRight, rightToLeft, leftToRight,
                topToTop, topToBottom, bottomToTop, bottomToBottom));
        return view;
    }

    private CustomView getTestView() {
        CustomView view = new CustomView();
        view.setType("androidx.cardview.widget.CardView");
        view.setParentType("FrameLayout");

        Attribute attribute = Attribute.builder()
                .setMethodName("setCardBackgroundColor")
                .setXmlName("app:cardBackgroundColor")
                .setParameters(int.class)
                .addFormat(Format.COLOR)
                .build();

        Attribute cornerRadius = Attribute.builder()
                .setMethodName("setRadius")
                .setXmlName("app:cardCornerRadius")
                .addFormat(Format.DIMENSION)
                .setParameters(float.class)
                .setDimension(true)
                .build();

        view.setAttributes(Arrays.asList(attribute, cornerRadius));
        return view;
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
            Value value = new ProteusTypeAdapterFactory(mContext)
                    .VALUE_TYPE_ADAPTER.read(new JsonReader(new StringReader(object.toString())));
            return inflate(value.getAsLayout());
        } catch (Exception e) {
            throw new InflateException("Unable to inflate layout: " + Log.getStackTraceString(e));
        }
    }

    public ProteusView inflate(Layout layout) {
        return mContext.getInflater().inflate(layout, new ObjectValue());
    }
}
