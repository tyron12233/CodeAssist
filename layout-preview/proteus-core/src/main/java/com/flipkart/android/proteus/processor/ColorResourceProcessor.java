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

import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Color;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

import androidx.annotation.Nullable;

public abstract class ColorResourceProcessor<V extends View> extends AttributeProcessor<V> {

  public static Color.Result evaluate(Value value, View view) {
    final Color.Result[] result = new Color.Result[1];
    ColorResourceProcessor<View> processor = new ColorResourceProcessor<View>() {
      @Override
      public void setColor(View view, int color) {
        result[0] = Color.Result.color(color);
      }

      @Override
      public void setColor(View view, ColorStateList colors) {
        result[0] = Color.Result.colors(colors);
      }
    };
    processor.process((View) view.getParent(), view, value);
    return result[0];
  }

  public static Value staticCompile(@Nullable Value value, ProteusContext context) {
    if (null == value) {
      return Color.Int.getDefaultColor();
    }
    if (value.isColor()) {
      return value;
    } else if (value.isObject()) {
      return Color.valueOf(value.getAsObject(), context);
    } else if (value.isPrimitive()) {
      if ("@null".equals(value.toString())) {
        return Color.Int.valueOf(android.graphics.Color.TRANSPARENT);
      }
      Value precompiled = staticPreCompile(value.getAsPrimitive(), context, null);
      if (null != precompiled) {
        return precompiled;
      }
      return Color.valueOf(value.getAsString(), Color.Int.getDefaultColor());
    } else {
      return Color.Int.getDefaultColor();
    }
  }

  @Override
  public void handleValue(View parent, final V view, Value value) {
    if (value.isColor()) {
      apply(parent, view, value.getAsColor());
    } else if  (value.isResource()) {
      handleResource(parent, view, value.getAsResource());
    } else if (value.isPrimitive()) {
      process(parent, view, precompile(value, ProteusHelper.getProteusContext(view), (ProteusHelper.getProteusContext(view)).getFunctionManager()));
    }
  }

  @Override
  public void handleResource(View parent, V view, Resource resource) {
    ColorStateList colors = resource.getColorStateList(parent, view);
    if (null != colors) {
      setColor(view, colors);
    } else {
      Color color = resource.getColor(ProteusHelper.getProteusContext(view));
      setColor(view, null == color ? Color.Int.getDefaultColor().value : color.getAsInt());
    }
  }

  @Override
  public void handleAttributeResource(View parent, V view, AttributeResource attribute) {
    String name = attribute.getName();
    ProteusView.Manager viewManager = ((ProteusView) view).getViewManager();
    ProteusContext context = viewManager.getContext();
    Value value = context.obtainStyledAttribute(parent, view, name);
    process(parent, view, value != null ? value : Color.Int.getDefaultColor());
  }

  @Override
  public void handleStyle(View parent, V view, Style style) {
//    TypedArray a = style.apply(view.getContext());
//    set(view, a);
  }

  private void set(V view, TypedArray a) {
    ColorStateList colors = a.getColorStateList(0);
    if (null != colors) {
      setColor(view, colors);
    } else {
      setColor(view, a.getColor(0, Color.Int.getDefaultColor().value));
    }
  }

//  private void apply(ProteusContext context, V view, Value value) {
//    if (value.isResource()) {
//      value = value.getAsResource().getColor(context);
//    }
//    if (value != null) {
//      apply(parent, view, value.getAsColor());
//    }
//  }

  private void apply(View parent, V view, Color color) {
    Color.Result result = color.apply(parent, view);
    if (null != result.colors) {
      setColor(view, result.colors);
    } else {
      setColor(view, result.color);
    }
  }

  public abstract void setColor(V view, int color);

  public abstract void setColor(V view, ColorStateList colors);

  @Override
  public Value compile(@Nullable Value value, ProteusContext context) {
    return staticCompile(value, context);
  }
}
