package com.tyron.layoutpreview.inflate;

import android.content.Context;
import android.util.Log;
import android.view.InflateException;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.flipkart.android.proteus.Proteus;
import com.flipkart.android.proteus.ProteusBuilder;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusLayoutInflater;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.SimpleIdGenerator;
import com.flipkart.android.proteus.SimpleLayoutInflater;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.builder.model.Project;
import com.tyron.layoutpreview.ResourceManager;
import com.tyron.layoutpreview.StringManager;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;
import com.tyron.layoutpreview.manager.ResourceStringManager;
import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.model.Format;
import com.tyron.layoutpreview.parser.CustomViewGroupParser;
import com.tyron.layoutpreview.parser.CustomViewParser;
import com.tyron.layoutpreview.view.UnknownView;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class PreviewLayoutInflater {

    private final Context mBaseContext;
    private final Proteus mProteus;
    private final ProteusContext mContext;

    private final ProteusLayoutInflater.Callback mCallback = new ProteusLayoutInflater.Callback() {
        @Override
        public ProteusView onUnknownViewType(ProteusContext context, ViewGroup parent, String type, Layout layout, ObjectValue data, int index) {
            UnknownView view = new UnknownView(context, type);

            // since we don't know what this view is, we can only apply attributes for an android.view.View
            ViewTypeParser<View> viewParser = context.getParser("android.view.View");
            if (viewParser != null && layout != null && layout.extras != null) {

                // create layout params for this view
                viewParser.onAfterCreateView(view, parent, -1);
                layout.extras.entrySet().forEach(entry -> {
                    String name = entry.getKey();
                    int id = viewParser.getAttributeId(name);
                    if (id != -1) {
                        // try first on the view attribute handers
                        viewParser.handleAttribute(view, id, entry.getValue());
                    } else {
                        // use the parent parser in case this view has layout params attributes
                        if (parent != null) {
                            ViewTypeParser<View> parentParser = context.getParser(parent.getClass().getName());
                            if (parentParser != null) {
                                parentParser.handleAttribute(view, parentParser.getAttributeId(name), entry.getValue());
                            }
                        }
                    }
                });
            }
            return view;
        }

        @Override
        public void onEvent(String event, Value value, ProteusView view) {

        }
    };

    private final ResourceStringManager mStringManager = new ResourceStringManager();

    public PreviewLayoutInflater(Context base, Project project) {
        mBaseContext = base;
        mProteus = new ProteusBuilder()
                .register(new CustomViewParser(getTestView()))
                .register(new CustomViewGroupParser(getConstraint()))
                .build();

        ResourceManager resourceManager = new ResourceManager(project.getResourceDirectory());
        mStringManager.setStrings(resourceManager.getStrings());

        mContext = mProteus.createContextBuilder(base)
                .setCallback(mCallback)
                .setStringManager(mStringManager)
                .build();

        ProteusTypeAdapterFactory.PROTEUS_INSTANCE_HOLDER.setProteus(mProteus);
    }

    public StringManager getStringManager() {
        return mStringManager;
    }

    // for testing only
    private CustomView getConstraint() {
        CustomView view = new CustomView();
        view.setType("androidx.constraintlayout.widget.ConstraintLayout");
        view.setParentType("android.view.ViewGroup");

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
        view.setParentType("android.widget.FrameLayout");

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
