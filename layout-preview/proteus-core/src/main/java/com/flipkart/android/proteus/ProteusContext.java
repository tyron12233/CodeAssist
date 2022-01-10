/*
 * Copyright 2019 Flipkart Internet Pvt. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.android.proteus;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toolbar;
import android.widget.VideoView;
import android.widget.ViewFlipper;
import android.widget.ViewSwitcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

import java.util.HashMap;
import java.util.Map;

/**
 * ProteusContext
 *
 * @author aditya.sharat
 */

public class ProteusContext extends ContextWrapper {

    private static final Map<String, String> sInternalViewMap = new HashMap<>();

    static {
        sInternalViewMap.put("LinearLayout", LinearLayout.class.getName());
        sInternalViewMap.put("ViewGroup", ViewGroup.class.getName());
        sInternalViewMap.put("FrameLayout", FrameLayout.class.getName());
        sInternalViewMap.put("RelativeLayout", RelativeLayout.class.getName());
        sInternalViewMap.put("ScrollView", ScrollView.class.getName());
        sInternalViewMap.put("ViewFlipper", ViewFlipper.class.getName());
        sInternalViewMap.put("ViewSwitcher", ViewSwitcher.class.getName());

        sInternalViewMap.put("View", View.class.getName());
        sInternalViewMap.put("ImageView", ImageView.class.getName());
        sInternalViewMap.put("VideoView", VideoView.class.getName());
        sInternalViewMap.put("CheckBox", CheckBox.class.getName());
        sInternalViewMap.put("Toolbar", Toolbar.class.getName());
        sInternalViewMap.put("TextView", TextView.class.getName());
        sInternalViewMap.put("ImageButton", ImageButton.class.getName());
        sInternalViewMap.put("Button", Button.class.getName());
        sInternalViewMap.put("EditText", EditText.class.getName());
        sInternalViewMap.put("ProgressBar", ProgressBar.class.getName());
        sInternalViewMap.put("SeekBar", SeekBar.class.getName());
        // TODO: add other views
    }

    private Style style;

    @NonNull
    private final ProteusResources resources;

    @Nullable
    private final ProteusLayoutInflater.Callback callback;

    @Nullable
    private final ProteusLayoutInflater.ImageLoader loader;

    private ProteusLayoutInflater inflater;
    private final ProteusParserFactory internalParserFactory = new ProteusParserFactory() {
        @Nullable
        @Override
        public <T extends View> ViewTypeParser<T> getParser(@NonNull String type) {
            if (type.contains(".")) {
                return null;
            }
            String s = sInternalViewMap.get(type);
            if (s == null) {
                return null;
            }
            return ProteusContext.this.getParser(s);
        }
    };
    private ProteusParserFactory parserFactory;


    ProteusContext(Context base, @NonNull ProteusResources resources,
                   @Nullable ProteusLayoutInflater.ImageLoader loader,
                   @Nullable ProteusLayoutInflater.Callback callback) {
        super(base);
        this.callback = callback;
        this.loader = loader;
        this.resources = resources;
    }

    @Nullable
    public ProteusLayoutInflater.Callback getCallback() {
        return callback;
    }

    @NonNull
    public FunctionManager getFunctionManager() {
        return resources.getFunctionManager();
    }

    @NonNull
    public Function getFunction(@NonNull String name) {
        return resources.getFunction(name);
    }

    @Nullable
    public Layout getLayout(@NonNull String name) {
        return resources.getLayout(name);
    }

    @Nullable
    public ProteusLayoutInflater.ImageLoader getLoader() {
        return loader;
    }

    public void setParserFactory(@NonNull ProteusParserFactory factory) {
        this.parserFactory = factory;
    }

    @NonNull
    public ProteusLayoutInflater getInflater(@NonNull IdGenerator idGenerator) {
        if (null == this.inflater) {
            this.inflater = new SimpleLayoutInflater(this, idGenerator);
        }
        return this.inflater;
    }

    @NonNull
    public ProteusLayoutInflater getInflater() {
        return getInflater(new SimpleIdGenerator());
    }

    @Nullable
    public <T extends View> ViewTypeParser<T> getParser(String type) {
        ViewTypeParser<T> parser = null;
        if (parserFactory != null) {
            parser = parserFactory.getParser(type);
        }

        if (parser == null) {
            if (!type.contains(".")) {
                parser = internalParserFactory.getParser(type);
            }
        }

        if (parser == null) {
            if (resources.getParsers().containsKey(type)) {
                //noinspection unchecked
                parser = resources.getParsers().get(type);
            }
        }

        return parser;
    }

    @NonNull
    public ProteusResources getProteusResources() {
        return resources;
    }

    @Nullable
    public Style getStyle(String name) {
        return resources.getStyle(name);
    }

    /**
     * @return The default style, typically from the application theme or the current activity
     */
    public Style getStyle() {
        return style;
    }

    public Value obtainStyledAttribute(View parent, View view, String name) {
        View current = view;

        boolean replaceNextParent = false;
        if (current == null || view.getParent() == null) {
            replaceNextParent = true;
        }
        while (current instanceof ProteusView) {
            ProteusView proteusView = (ProteusView) current;
            ProteusView.Manager viewManager = proteusView.getViewManager();
            Style style = viewManager.getTheme();
            if (style != null) {
                Value value = style.getValue(name, this, null);
                if (value != null) {
                    return value;
                }

                // try searching on the themeOverlay
                Value materialThemeOverlay = style.getValue("materialThemeOverlay", this, null);
                if (materialThemeOverlay != null) {
                    Value overlayValue =
                            AttributeProcessor.staticPreCompile(materialThemeOverlay.getAsPrimitive(), this, getFunctionManager());
                    if (overlayValue != null) {
                        Value overlay = overlayValue.getAsStyle().getValue(name, this, null);
                        if (overlay != null) {
                            return overlay;
                        }
                    }
                }
            }
            if (replaceNextParent) {
                if (!parent.equals(current)) {
                    current = parent;
                } else {
                    current = null;
                }
            } else {
                current = (View) current.getParent();
            }
        }
        return style.getValue(name, this, null);
    }

    /**
     * Sets the style to use for all the views that are inflated with this context
     */
    public void setStyle(Style style) {
        this.style = style;
    }

    /**
     * Builder
     *
     * @author adityasharat
     */
    public static class Builder {

        @NonNull
        private final Context base;

        @NonNull
        private final FunctionManager functionManager;

        @NonNull
        private final Map<String, ViewTypeParser> parsers;

        @Nullable
        private ProteusLayoutInflater.ImageLoader loader;

        @Nullable
        private ProteusLayoutInflater.Callback callback;

        @Nullable
        private LayoutManager layoutManager;

        @Nullable
        private StyleManager styleManager;

        private StringManager stringManager;

        private DrawableManager drawableManager;
        private ColorManager colorManager;
        private DimensionManager dimensionManager;

        Builder(@NonNull Context context, @NonNull Map<String, ViewTypeParser> parsers, @NonNull FunctionManager functionManager) {
            this.base = context;
            this.parsers = parsers;
            this.functionManager = functionManager;
        }

        public Builder setImageLoader(@Nullable ProteusLayoutInflater.ImageLoader loader) {
            this.loader = loader;
            return this;
        }

        public Builder setCallback(@Nullable ProteusLayoutInflater.Callback callback) {
            this.callback = callback;
            return this;
        }

        public Builder setLayoutManager(@Nullable LayoutManager layoutManager) {
            this.layoutManager = layoutManager;
            return this;
        }

        public Builder setStyleManager(@Nullable StyleManager styleManager) {
            this.styleManager = styleManager;
            return this;
        }

        public Builder setStringManager(StringManager stringManager) {
            this.stringManager = stringManager;
            return this;
        }

        public Builder setDrawableManager(DrawableManager drawableManager) {
            this.drawableManager = drawableManager;
            return this;
        }

        public Builder setColorManager(ColorManager colorManager) {
            this.colorManager = colorManager;
            return this;
        }

        public Builder setDimensionManager(DimensionManager dimensionManager) {
            this.dimensionManager = dimensionManager;
            return this;
        }

        public ProteusContext build() {
            ProteusResources resources = new ProteusResources(parsers, layoutManager,
                    functionManager, styleManager, stringManager, drawableManager, colorManager, dimensionManager);
            return new ProteusContext(base, resources, loader, callback);
        }

    }
}
