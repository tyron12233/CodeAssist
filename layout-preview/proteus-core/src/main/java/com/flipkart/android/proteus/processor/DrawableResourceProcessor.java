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

import android.content.res.TypedArray;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusLayoutInflater;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Color;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
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
    processor.process((View) view.getParent(), view, value);
    return d[0];
  }

  public static Value staticCompile(@Nullable Value value, ProteusContext context) {
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
  public void handleValue(View parent, final V view, Value value) {
    if (value.isDrawable()) {
      DrawableValue d = value.getAsDrawable();
      if (null != d) {
        ProteusContext context = ProteusHelper.getProteusContext(view);
        ProteusLayoutInflater.ImageLoader loader = context.getLoader();
        d.apply(view, context, loader, drawable -> {
          setDrawable(view, drawable);
        });
      }
    } else {
      if (value.isPrimitive() && value.toString().equals("@null")) {
        setDrawable(view, null);
      } else {
        process(parent, view, precompile(value, ProteusHelper.getProteusContext(view), (ProteusHelper.getProteusContext(view)).getFunctionManager()));
      }
    }
  }

  @Override
  public void handleResource(View parent, V view, Resource resource) {
    ProteusContext context = ProteusHelper.getProteusContext(view);
    DrawableValue drawableValue = resource.getProteusDrawable(context);
    if (drawableValue == null) {
      Drawable d = resource.getDrawable(context);
      if (d == null) {
        Color color = resource.getColor(context);
        if (color != null) {
          d = new ColorDrawable(color.apply(parent, view).color);
        }
      }
      if (null != d) {
        setDrawable(view, d);
      }
    } else {
      drawableValue.apply(view, context, context.getLoader(), drawable -> {
        setDrawable(view, drawable);
      });
    }
  }

  @Override
  public void handleAttributeResource(View parent, V view, AttributeResource attribute) {
    TypedArray a = attribute.apply(view.getContext());
    set(view, a);
  }

  @Override
  public void handleStyle(View parent, V view, Style style) {
//    TypedArray a = style.apply(view.getContext());
//    set(view, a);
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
