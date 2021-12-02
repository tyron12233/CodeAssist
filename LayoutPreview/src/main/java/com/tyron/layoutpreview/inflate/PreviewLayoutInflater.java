package com.tyron.layoutpreview.inflate;

import android.content.Context;
import android.util.Log;
import android.view.InflateException;
import android.view.View;
import android.view.ViewGroup;

import com.flipkart.android.proteus.Proteus;
import com.flipkart.android.proteus.ProteusBuilder;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusLayoutInflater;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.StringManager;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Value;
import com.flipkart.android.proteus.view.UnknownView;
import com.flipkart.android.proteus.view.UnknownViewGroup;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.builder.project.api.AndroidProject;
import com.tyron.builder.project.api.Project;
import com.tyron.layout.appcompat.AppCompatModule;
import com.tyron.layout.cardview.CardViewModule;
import com.tyron.layout.constraintlayout.ConstraintLayoutModule;
import com.tyron.layoutpreview.ResourceManager;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;
import com.tyron.layoutpreview.manager.ResourceDrawableManager;
import com.tyron.layoutpreview.manager.ResourceLayoutManager;
import com.tyron.layoutpreview.manager.ResourceStringManager;

import java.io.File;
import java.io.StringReader;

import dalvik.system.DexClassLoader;

public class PreviewLayoutInflater {

    private final Context mBaseContext;
    private final Proteus mProteus;
    private final AndroidProject mProject;
    private ProteusContext mContext;

    private final ProteusLayoutInflater.Callback mCallback = new ProteusLayoutInflater.Callback() {
        @Override
        public ProteusView onUnknownViewType(ProteusContext context, ViewGroup parent, String type, Layout layout, ObjectValue data, int index) {

            ProteusView view;
            Value children = null;
            // this View has a children, use a ViewGroup so the children can be laid out as well.
            if (layout.extras != null && layout.extras.get("children") != null) {
                view = new UnknownViewGroup(context, type);

                // remove the children attribute since we don't know how to place them
                children = layout.extras.get("children");
                layout.extras.remove("children");
            } else {
                view = new UnknownView(context, type);
            }

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
                        viewParser.handleAttribute(view.getAsView(), id, entry.getValue());
                    } else {
                        // use the parent parser in case this view has layout params attributes
                        if (parent != null) {
                            ViewTypeParser<View> parentParser = context.getParser(parent.getClass().getName());
                            if (parentParser != null) {
                                parentParser.handleAttribute(view.getAsView(), parentParser.getAttributeId(name), entry.getValue());
                            }
                        }
                    }
                });

                if (children != null) {
                    layout.extras.add("children", children);
                }
            }

            return view;
        }

        @Override
        public void onEvent(String event, Value value, ProteusView view) {

        }
    };

    private final ResourceStringManager mStringManager = new ResourceStringManager();
    private final ResourceDrawableManager mDrawableManager = new ResourceDrawableManager();
    private final ResourceLayoutManager mLayoutManager = new ResourceLayoutManager();

    private ProteusLayoutInflater.ImageLoader mImageLoader = (view, name, callback) -> {
        if (name.startsWith("@drawable")) {
            DrawableValue value = mDrawableManager.get(name.substring("@drawable".length() + 1));
            if (value != null) {
                value.apply(view, mContext, null, callback::setDrawable);
            }
        } else if (name.startsWith("@null")) {
            callback.setDrawable(null);
        }
    };

    public PreviewLayoutInflater(Context base, AndroidProject project) {
        mBaseContext = base;
        ProteusBuilder builder = new ProteusBuilder();
        builder.register(ConstraintLayoutModule.create());
        builder.register(CardViewModule.create());
        builder.register(AppCompatModule.create());
        registerCustomViews(builder, project);
        mProteus = builder.build();
        mProject = project;

        mContext = mProteus.createContextBuilder(base)
                .setCallback(mCallback)
                .setStringManager(mStringManager)
                .setDrawableManager(mDrawableManager)
                .setImageLoader(mImageLoader)
                .setLayoutManager(mLayoutManager)
                .build();
        ProteusTypeAdapterFactory.PROTEUS_INSTANCE_HOLDER.setProteus(mProteus);
    }

    public void parseResources() {
        ResourceManager resourceManager = new ResourceManager(mContext,
                mProject.getAndroidResourcesDirectory(), mProject.getFileManager());
        mStringManager.setStrings(resourceManager.getStrings());
        mDrawableManager.setDrawables(resourceManager.getDrawables());
        mLayoutManager.setLayouts(resourceManager.getLayouts());

    }

    public StringManager getStringManager() {
        return mStringManager;
    }

    public ProteusView inflateLayout(String name) {
        ObjectValue value = new ObjectValue();
        value.add("layout_name", new Primitive(name));
        return mContext.getInflater().inflate(name, value);
    }

    @Deprecated
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
    @Deprecated
    public ProteusView inflate(JsonObject object) {
        try {
            Value value = new ProteusTypeAdapterFactory(mContext)
                    .VALUE_TYPE_ADAPTER.read(new JsonReader(new StringReader(object.toString())), false);
            return inflate(value.getAsLayout());
        } catch (Exception e) {
            throw new InflateException("Unable to inflate layout: " + Log.getStackTraceString(e));
        }
    }

    public ProteusView inflate(Layout layout) {
        return mContext.getInflater().inflate(layout, new ObjectValue());
    }

    public void registerCustomViews(ProteusBuilder builder, Project project) {
        File customViewsDir = new File(project.getBuildDirectory(), "custom_views");
        if (!customViewsDir.exists() && !customViewsDir.mkdirs()) {
            return;
        }

        File[] jarFiles = customViewsDir.listFiles(c -> c.getName().endsWith(".jar"));
        if (jarFiles != null) {
            for (File file : jarFiles) {
                // TODO: use the class loader of the project, Load class from META-INF
                DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(), mContext.getCodeCacheDir().getAbsolutePath(), null, this.getClass().getClassLoader());
            }
        }
    }
}
