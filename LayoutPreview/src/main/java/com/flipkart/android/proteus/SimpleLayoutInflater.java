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

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.exceptions.ProteusInflateException;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;

import java.util.Iterator;
import java.util.Map;

/**
 * A layout builder which can parse json to construct an android view out of it. It uses the
 * registered parsers to convert the json string to a view and then assign attributes.
 * <p>
 * Modified to allow unknown views.
 */
public class SimpleLayoutInflater implements ProteusLayoutInflater {

    private static final String TAG = "SimpleLayoutInflater";

    private static final String[] sClassPrefix = new String[] {"android.widget", "android.view", "android.webkit"};

    @NonNull
    protected final ProteusContext context;

    @NonNull
    protected final IdGenerator idGenerator;

    SimpleLayoutInflater(@NonNull ProteusContext context, @NonNull IdGenerator idGenerator) {
        this.context = context;
        this.idGenerator = idGenerator;
    }

    @SuppressWarnings("rawtypes")
    @Override
    @Nullable
    public ViewTypeParser getParser(@NonNull String type) {
        if (!type.contains(".")) {
            for (String prefix : sClassPrefix) {
                ViewTypeParser<? extends View> parser = context.getParser(prefix + "." + type);
                if (parser != null) {
                    return parser;
                }
            }
        }
        return context.getParser(type);
    }

    @NonNull
    @Override
    public ProteusView inflate(@NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {

        /*
         * Get the the view type parser for this layout type
         */
        final ViewTypeParser parser = getParser(layout.type);
        if (parser == null) {
            /*
             * If parser is not registered ask the application land for the view
             */
            return onUnknownViewEncountered(layout.type, parent, layout, data, dataIndex);
        }

        /*
         * Create a view of {@code layout.type}
         */
        final ProteusView view = createView(parser, layout, data, parent, dataIndex);

        if (view.getViewManager() == null) {

            /*
             * Do post creation logic
             */
            onAfterCreateView(parser, view, parent, dataIndex);

            /*
             * Create View Manager for {@code layout.type}
             */
            final ProteusView.Manager viewManager = createViewManager(parser, view, layout, data, parent, dataIndex);

            /*
             * Set the View Manager on the view.
             */
            view.setViewManager(viewManager);
        }

        /*
         * Handle each attribute and set it on the view.
         */
        if (layout.attributes != null) {
            Iterator<Layout.Attribute> iterator = layout.attributes.iterator();
            Layout.Attribute attribute;
            while (iterator.hasNext()) {
                attribute = iterator.next();
                handleAttribute(parser, view, parent, attribute.id, attribute.value);
            }
        }

        if (layout.extras != null && parent != null) {
            for (Map.Entry<String, Value> entry : layout.extras.entrySet()) {
                ViewTypeParser parentParser = context.getParser(getType(parent));
                int id = parentParser.getAttributeId(entry.getKey());
                if (id != -1) {
                    parentParser.handleAttribute(view.getAsView(), id, entry.getValue());
                }
            }
        }

        return view;
    }

    @NonNull
    @Override
    public ProteusView inflate(@NonNull Layout layout, @NonNull ObjectValue data, int dataIndex) {
        return inflate(layout, data, null, dataIndex);
    }

    @NonNull
    @Override
    public ProteusView inflate(@NonNull Layout layout, @NonNull ObjectValue data) {
        return inflate(layout, data, null, -1);
    }

    @NonNull
    @Override
    public ProteusView inflate(@NonNull String name, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        Layout layout = context.getLayout(name);
        if (null == layout) {
            throw new ProteusInflateException("layout : '" + name + "' not found");
        }
        return inflate(layout, data, parent, dataIndex);
    }

    @NonNull
    @Override
    public ProteusView inflate(@NonNull String name, @NonNull ObjectValue data, int dataIndex) {
        return inflate(name, data, null, dataIndex);
    }

    @NonNull
    @Override
    public ProteusView inflate(@NonNull String name, @NonNull ObjectValue data) {
        return inflate(name, data, null, -1);
    }

    @Override
    public int getUniqueViewId(@NonNull String id) {
        return idGenerator.getUnique(id);
    }

    @NonNull
    @Override
    public IdGenerator getIdGenerator() {
        return idGenerator;
    }

    protected ProteusView createView(@NonNull ViewTypeParser parser, @NonNull Layout layout, @NonNull ObjectValue data,
                                     @Nullable ViewGroup parent, int dataIndex) {
        return parser.createView(context, layout, data, parent, dataIndex);
    }

    protected ProteusView.Manager createViewManager(@NonNull ViewTypeParser parser, @NonNull ProteusView view, @NonNull Layout layout,
                                                      @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return parser.createViewManager(context, view, layout, data, parser, parent, dataIndex);
    }

    protected void onAfterCreateView(@NonNull ViewTypeParser parser, @NonNull ProteusView view, @Nullable ViewGroup parent, int index) {
        parser.onAfterCreateView(view, parent, index);
    }

    @NonNull
    protected ProteusView onUnknownViewEncountered(String type, ViewGroup parent, Layout layout, ObjectValue data, int dataIndex) {
        if (ProteusConstants.isLoggingEnabled()) {
            Log.d(TAG, "No ViewTypeParser for: " + type);
        }
        if (context.getCallback() != null) {
            ProteusView view = context.getCallback().onUnknownViewType(context, parent, type, layout, data, dataIndex);
            //noinspection ConstantConditions because we need to throw a ProteusInflateException specifically
            if (view == null) {
                throw new ProteusInflateException("inflater Callback#onUnknownViewType() must not return null");
            }

            return view;
        }
        throw new ProteusInflateException("Layout contains type: 'include' but inflater callback is null");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected boolean handleAttribute(@NonNull ViewTypeParser parser, @NonNull ProteusView view, ViewGroup parent, int attribute, @NonNull Value value) {
        if (ProteusConstants.isLoggingEnabled()) {
            Log.d(TAG, "Handle '" + attribute + "' : " + value);
        }

        boolean success = parser.handleAttribute(view.getAsView(), attribute, value);

        if (!success) {
            ViewTypeParser parentParser = context.getParser(getType(parent));
            if (parentParser != null) {
                boolean found = parentParser.handleAttribute(view.getAsView(), attribute, value);
                if (found) {
                    return true;
                }
            }
        }

        return success;
    }

    private String getType(View view) {
        String name = view.getClass().getName();
        if (name.startsWith("android.widget")) {
            name = name.replace("android.widget.", "");
        } else if (name.startsWith("android.view")) {
            name = name.replace("android.view.", "");
        }
        return name;
    }
}
