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

package com.flipkart.android.proteus.managers;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.BoundAttribute;
import com.flipkart.android.proteus.DataContext;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.flipkart.android.proteus.view.UnknownView;
import com.flipkart.android.proteus.view.UnknownViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ViewManager
 *
 * @author aditya.sharat
 */
public class ViewManager implements ProteusView.Manager {

    @NonNull
    protected final ProteusContext context;

    @NonNull
    protected View view;

    @NonNull
    protected final Layout layout;

    @NonNull
    protected final DataContext dataContext;

    @NonNull
    protected final ViewTypeParser parser;

    @Nullable
    protected final List<BoundAttribute> boundAttributes;

    @Nullable
    protected Object extras;

    @Nullable
    protected Style theme;

    @Nullable
    protected Style style;

    private View.OnDragListener onDragListener;
    private View.OnClickListener onClickListener;
    private View.OnLongClickListener onLongClickListener;

    public ViewManager(@NonNull ProteusContext context, @NonNull ViewTypeParser parser,
                       @NonNull View view, @NonNull Layout layout,
                       @NonNull DataContext dataContext) {
        this.context = context;
        this.parser = parser;
        this.view = view;
        this.layout = layout;
        this.dataContext = dataContext;

        if (null != layout.attributes) {
            List<BoundAttribute> boundAttributes = new ArrayList<>();
            for (Layout.Attribute attribute : layout.attributes) {
                if (attribute.value.isBinding()) {
                    boundAttributes.add(new BoundAttribute(attribute.id,
                            attribute.value.getAsBinding()));
                }
            }
            if (boundAttributes.size() > 0) {
                this.boundAttributes = boundAttributes;
            } else {
                this.boundAttributes = null;
            }
        } else {
            this.boundAttributes = null;
        }
    }

    @Override
    public void update(@Nullable ObjectValue data) {
        // update the data context so all child views can refer to new data
        if (data != null) {
            updateDataContext(data);
        }

        // update the bound attributes of this view
        if (this.boundAttributes != null) {
            for (BoundAttribute boundAttribute : this.boundAttributes) {
                this.handleBinding(boundAttribute);
            }
        }
    }

    @Nullable
    @Override
    public View findViewById(@NonNull String id) {
        return view.findViewById(context.getInflater().getUniqueViewId(id));
    }

    public View.OnDragListener getOnDragListener() {
        return onDragListener;
    }

    public void setOnDragListener(View.OnDragListener onDragListener) {
        view.setOnDragListener(onDragListener);
        this.onDragListener = onDragListener;
    }

    public void setOnClickListener(View.OnClickListener onClickListener) {
        view.setOnClickListener(onClickListener);
        this.onClickListener = onClickListener;
    }

    public View.OnClickListener getOnClickListener() {
        return onClickListener;
    }

    public void setOnLongClickListener(View.OnLongClickListener onLongClickListener) {
        view.setOnLongClickListener(onLongClickListener);
        this.onLongClickListener = onLongClickListener;
    }

    public View.OnLongClickListener getOnLongClickListener() {
        return onLongClickListener;
    }

    @NonNull
    @Override
    public ProteusContext getContext() {
        return this.context;
    }

    @NonNull
    @Override
    public Layout getLayout() {
        return this.layout;
    }

    @NonNull
    public DataContext getDataContext() {
        return dataContext;
    }

    @Nullable
    @Override
    public Object getExtras() {
        return this.extras;
    }

    @Override
    public void setExtras(@Nullable Object extras) {
        this.extras = extras;
    }

    private void updateDataContext(ObjectValue data) {
        if (dataContext.hasOwnProperties()) {
            dataContext.update(context, data);
        } else {
            dataContext.setData(data);
        }
    }

    @Override
    public void setStyle(@Nullable Style style) {
        this.style = style;
    }

    @Override
    @Nullable
    public Style getStyle() {
        return this.style;
    }

    public void setTheme(Style theme) {
        this.theme = theme;
    }

    public Style getTheme() {
        return theme;
    }

    @Override
    public Map<String, ViewTypeParser.AttributeSet.Attribute> getAvailableAttributes() {
        Map<String, ViewTypeParser.AttributeSet.Attribute> attributes =
                new TreeMap<>(parser.getAttributeSet().getAttributes());
        attributes.putAll(parser.getAttributeSet().getLayoutParamsAttributes());

        if (view.getParent() instanceof ProteusView) {
            ProteusView parent = ((ProteusView) view.getParent());
            ProteusView.Manager viewManager = parent.getViewManager();
            attributes.putAll(viewManager.getLayoutParamsAttributes());
        }
        return attributes;
    }

    @Override
    public <T extends View> ViewTypeParser<T> getViewTypeParser() {
        return parser;
    }

    @Override
    public void removeAttribute(String attributeName) {
        if (layout.attributes != null) {
            int attributeId = ProteusHelper.getAttributeId((ProteusView) view, attributeName);
            Layout.Attribute attribute = new Layout.Attribute(attributeId, null);
            layout.attributes.remove(attribute);
        }
        if (layout.extras != null) {
            layout.extras.remove(attributeName);
        }

        View.OnDragListener listener = getOnDragListener();
        View.OnClickListener onClickListener = getOnClickListener();
        View.OnLongClickListener onLongClickListener = getOnLongClickListener();

        // when an attribute is removed, there is no way to undo the
        // attributes that have already been set so we just recreate
        // the view and add it again to the layout

        if (!(view instanceof UnknownView) && !(view instanceof UnknownViewGroup)) {
            ViewParent viewParent = view.getParent();
            if (viewParent instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) viewParent;
                int index = parent.indexOfChild(view);
                parent.removeView(view);

                ProteusView view = context.getInflater().inflate(layout, new ObjectValue(), parent, -1);
                this.view = view.getAsView();
                view.setViewManager(this);

                setOnClickListeners(view, onClickListener, onLongClickListener, listener);

                parent.addView(view.getAsView(), index);
            }
        }
    }

    private void setOnClickListeners(ProteusView view,
                                     View.OnClickListener onClickListener,
                                     View.OnLongClickListener onLongClickListener,
                                     View.OnDragListener onDragListener) {
        view.getViewManager().setOnClickListener(onClickListener);
        view.getViewManager().setOnLongClickListener(onLongClickListener);

        if (view.getAsView() instanceof ViewGroup) {
            view.getViewManager().setOnDragListener(onDragListener);

            ViewGroup viewGroup = (ViewGroup) view.getAsView();
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View childAt = viewGroup.getChildAt(i);
                if (childAt instanceof ProteusView) {
                    setOnClickListeners((ProteusView) childAt,
                            onClickListener, onLongClickListener, onDragListener);
                }
            }
        }
    }


    @Override
    public void updateAttribute(String name, Value value) {
        removeAttribute(name);

        if (value.isPrimitive()) {
            Value result = AttributeProcessor.staticPreCompile(value.getAsPrimitive(), context, context.getFunctionManager());
            if (result != null) {
                value = result;
            }
        }
        int attributeId = ProteusHelper.getAttributeId((ProteusView) view, name);
        boolean isExtra = !ProteusHelper.isAttributeFromView((ProteusView) view, name, attributeId);
        ViewTypeParser<View> parser = ProteusHelper.getViewTypeParser((ProteusView) view, name, isExtra);
        if (this.parser.equals(parser)) {
            if (layout.attributes == null) {
                layout.attributes = new ArrayList<>();
            }
            layout.attributes.remove(new Layout.Attribute(attributeId, null));
            layout.attributes.add(new Layout.Attribute(attributeId, value));
            parser.handleAttribute((View) view.getParent(), view, attributeId, value);
        } else {
            if (layout.extras == null) {
                layout.extras = new ObjectValue();
            }
            if (value != null) {
                layout.extras.addProperty(name, value.getAsString());
                if (parser != null) {
                    parser.handleAttribute((View) view.getParent(), view, attributeId, value);
                }
            }
        }
    }

    public void updateAttribute(String name, String string) {
        Primitive primitive = new Primitive(string);
        Value value = AttributeProcessor.staticPreCompile(primitive, context,
                context.getFunctionManager());
        if (value == null) {
            value = new Primitive(string);
        }
        updateAttribute(name, value);
    }

    public String getAttributeName(int id) {
        for (Map.Entry<String, ViewTypeParser.AttributeSet.Attribute> entry :
                parser.getAttributeSet().getAttributes().entrySet()) {
            String k = entry.getKey();
            ViewTypeParser.AttributeSet.Attribute v = entry.getValue();
            if (v.id == id) {
                return k;
            }
        }

        if (view.getParent() instanceof ProteusView) {
            ProteusView parent = (ProteusView) view.getParent();
            //noinspection rawtypes
            ViewTypeParser parser = parent.getViewManager().getViewTypeParser();
            for (Map.Entry<String, ViewTypeParser.AttributeSet.Attribute> entry :
                    parser.getAttributeSet().getAttributes().entrySet()) {
                String k = entry.getKey();
                ViewTypeParser.AttributeSet.Attribute v = entry.getValue();
                if (v.id == id) {
                    return k;
                }
            }
        }
        return "Unknown";
    }

    private void handleBinding(BoundAttribute boundAttribute) {
        //noinspection unchecked
        parser.handleAttribute((View) view.getParent(), view, boundAttribute.attributeId, boundAttribute.binding);
    }
}
