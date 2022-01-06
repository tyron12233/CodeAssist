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

package com.flipkart.android.proteus.parser.custom;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import com.flipkart.android.proteus.DataContext;
import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusLayoutInflater;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.exceptions.ProteusInflateException;
import com.flipkart.android.proteus.managers.ViewGroupManager;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Binding;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.NestedBinding;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.flipkart.android.proteus.view.ProteusAspectRatioFrameLayout;

import java.util.Iterator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ViewGroupParser<T extends ViewGroup> extends ViewTypeParser<T> {

  private static final String LAYOUT_MODE_CLIP_BOUNDS = "clipBounds";
  private static final String LAYOUT_MODE_OPTICAL_BOUNDS = "opticalBounds";

  @NonNull
  @Override
  public String getType() {
    return "android.view.ViewGroup";
  }

  @Nullable
  @Override
  public String getParentType() {
    return "android.view.View";
  }

  @NonNull
  @Override
  public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data,
                                @Nullable ViewGroup parent, int dataIndex) {
    return new ProteusAspectRatioFrameLayout(context);
  }

  @NonNull
  @Override
  public ProteusView.Manager createViewManager(@NonNull ProteusContext context, @NonNull ProteusView view, @NonNull Layout layout,
                                               @NonNull ObjectValue data, @Nullable ViewTypeParser caller, @Nullable ViewGroup parent,
                                               int dataIndex) {
    DataContext dataContext = createDataContext(context, layout, data, parent, dataIndex);
    return new ViewGroupManager(context, null != caller ? caller : this, view.getAsView(), layout, dataContext);
  }

  @Override
  protected void addAttributeProcessors() {

    addAttributeProcessor(Attributes.ViewGroup.ClipChildren, new BooleanAttributeProcessor<T>() {
      @Override
      public void setBoolean(T view, boolean value) {
        view.setClipChildren(value);
      }
    });

    addAttributeProcessor(Attributes.ViewGroup.ClipToPadding, new BooleanAttributeProcessor<T>() {
      @Override
      public void setBoolean(T view, boolean value) {
        view.setClipToPadding(value);
      }
    });

    addAttributeProcessor(Attributes.ViewGroup.LayoutMode, new StringAttributeProcessor<T>() {
      @Override
      public void setString(T view, String value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
          if (LAYOUT_MODE_CLIP_BOUNDS.equals(value)) {
            view.setLayoutMode(ViewGroup.LAYOUT_MODE_CLIP_BOUNDS);
          } else if (LAYOUT_MODE_OPTICAL_BOUNDS.equals(value)) {
            view.setLayoutMode(ViewGroup.LAYOUT_MODE_OPTICAL_BOUNDS);
          }
        }
      }
    });

    addAttributeProcessor(Attributes.ViewGroup.SplitMotionEvents, new BooleanAttributeProcessor<T>() {
      @Override
      public void setBoolean(T view, boolean value) {
        view.setMotionEventSplittingEnabled(value);
      }
    });

    addAttributeProcessor(Attributes.ViewGroup.Children, new AttributeProcessor<T>() {
      @Override
      public void handleBinding(View parent, T view, Binding value) {
        handleDataBoundChildren(view, value);
      }

      @Override
      public void handleValue(View parent, T view, Value value) {
        handleChildren(view, value);
      }

      @Override
      public void handleResource(View parent, T view, Resource resource) {
        throw new IllegalArgumentException("children cannot be a resource");
      }

      @Override
      public void handleAttributeResource(View parent, T view, AttributeResource attribute) {
        throw new IllegalArgumentException("children cannot be a resource");
      }

      @Override
      public void handleStyle(View parent, T view, Style style) {
        throw new IllegalArgumentException("children cannot be a style attribute");
      }
    });
  }

  @Override
  public boolean handleChildren(T view, Value children) {
    ProteusContext context = ProteusHelper.getProteusContext(view);
    ProteusLayoutInflater layoutInflater = context.getInflater();
    ObjectValue data = new ObjectValue();
    int dataIndex = -1;

    if (view instanceof ProteusView) {
      if (((ProteusView) view).getViewManager() != null) {
        data = ((ProteusView) view).getViewManager().getDataContext().getData();
        dataIndex = ((ProteusView) view).getViewManager().getDataContext().getIndex();
      }
    }

    if (children.isArray()) {
      ProteusView child;
      Iterator<Value> iterator = children.getAsArray().iterator();
      Value element;
      while (iterator.hasNext()) {
        element = iterator.next();
        if (!element.isLayout()) {
          throw new ProteusInflateException("attribute  'children' must be an array of 'Layout' objects");
        }
        child = layoutInflater.inflate(element.getAsLayout(), data, view, dataIndex);

       addView(view, child.getAsView());
      }
    }

    return true;
  }

  protected void handleDataBoundChildren(T view, Binding value) {
    ProteusView parent = ((ProteusView) view);
    ViewGroupManager manager = (ViewGroupManager) parent.getViewManager();
    DataContext dataContext = manager.getDataContext();
    ObjectValue config = ((NestedBinding) value).getValue().getAsObject();

    Binding collection = config.getAsBinding(ProteusConstants.COLLECTION);
    Layout layout = config.getAsLayout(ProteusConstants.LAYOUT);

    manager.hasDataBoundChildren = true;

    if (null == layout || null == collection) {
      throw new ProteusInflateException("'collection' and 'layout' are mandatory for attribute:'children'");
    }

    Value dataset = collection.getAsBinding().evaluate((ProteusContext) view.getContext(), dataContext.getData(), dataContext.getIndex());
    if (dataset.isNull()) {
      return;
    }

    if (!dataset.isArray()) {
      throw new ProteusInflateException("'collection' in attribute:'children' must be NULL or Array");
    }

    int length = dataset.getAsArray().size();
    int count = view.getChildCount();
    ObjectValue data = dataContext.getData();
    ProteusLayoutInflater inflater = manager.getContext().getInflater();
    ProteusView child;
    View temp;

    if (count > length) {
      while (count > length) {
        count--;
        view.removeViewAt(count);
      }
    }

    for (int index = 0; index < length; index++) {
      if (index < count) {
        temp = view.getChildAt(index);
        if (temp instanceof ProteusView) {
          ((ProteusView) temp).getViewManager().update(data);
        }
      } else {
        //noinspection ConstantConditions : We want to throw an exception if the layout is null
        child = inflater.inflate(layout, data, view, index);
        addView(parent, child);
      }
    }
  }

  @Override
  public boolean addView(ProteusView parent, ProteusView view) {
    if (parent instanceof ViewGroup) {
      ((ViewGroup) parent).addView(view.getAsView());
      return true;
    }
    return false;
  }

  public boolean addView(ProteusView parent, View view) {
    if (parent instanceof ViewGroup) {
      ((ViewGroup) parent).addView(view);
      return true;
    }
    return false;
  }

  public boolean addView(View parent, View view) {
    if (parent instanceof ViewGroup) {
      ((ViewGroup) parent).addView(view);
      return true;
    }
    return false;
  }
}
