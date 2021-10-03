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

package com.flipkart.android.proteus.processor;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusLayoutInflater;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.StyleResource;
import com.flipkart.android.proteus.value.Value;

import androidx.annotation.Nullable;

/**
 * Use this as the base processor for references like @drawable or remote resources with http:// urls.
 */
public abstract class DrawableResourceProcessor<V extends View> extends AttributeProcessor<V> {

  @Nullable
  public static Drawable evaluate(Value value, View view) {
    if (null == value) {
      return null;
    }
    final Drawable[] d = new Drawable[1];
    DrawableResourceProcessor<View> processor = new DrawableResourceProcessor<View>() {
      @Override
      public void setDrawable(View view, Drawable drawable) {
        d[0] = drawable;
      }
    };
    processor.process(view, value);
    return d[0];
  }

  public static Value staticCompile(@Nullable Value value, Context context) {
    if (null == value) {
      return DrawableValue.ColorValue.BLACK;
    }
    if (value.isDrawable()) {
      return value;
    } else if (value.isPrimitive()) {
      Value precompiled = AttributeProcessor.staticPreCompile(value.getAsPrimitive(), context, null);
      if (null != precompiled) {
        return precompiled;
      }
      return DrawableValue.valueOf(value.getAsString(), context);
    } else if (value.isObject()) {
      return DrawableValue.valueOf(value.getAsObject(), context);
    } else {
      return DrawableValue.ColorValue.BLACK;
    }
  }

  @Override
  public void handleValue(final V view, Value value) {
    if (value.isDrawable()) {
      DrawableValue d = value.getAsDrawable();
      if (null != d) {
        ProteusContext context = (ProteusContext) view.getContext();
        ProteusLayoutInflater.ImageLoader loader = context.getLoader();
        d.apply(view, view.getContext(), loader, drawable -> {
          if (null != drawable) {
            setDrawable(view, drawable);
          }
        });
      }
    } else {
      process(view, precompile(value, (ProteusContext) view.getContext(), ((ProteusContext) view.getContext()).getFunctionManager()));
    }
  }

  @Override
  public void handleResource(V view, Resource resource) {
    Drawable d = resource.getDrawable(view.getContext());
    if (null != d) {
      setDrawable(view, d);
    }
  }

  @Override
  public void handleAttributeResource(V view, AttributeResource attribute) {
    TypedArray a = attribute.apply(view.getContext());
    set(view, a);
  }

  @Override
  public void handleStyleResource(V view, StyleResource style) {
    TypedArray a = style.apply(view.getContext());
    set(view, a);
  }

  private void set(V view, TypedArray a) {
    Drawable d = a.getDrawable(0);
    if (null != d) {
      setDrawable(view, d);
    }
  }

  public abstract void setDrawable(V view, Drawable drawable);

  @Override
  public Value compile(@Nullable Value value, ProteusContext context) {
    return staticCompile(value, context);
  }
}
