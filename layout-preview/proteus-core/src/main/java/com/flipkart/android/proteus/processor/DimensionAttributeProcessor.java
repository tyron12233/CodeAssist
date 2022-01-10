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
import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Dimension;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

import androidx.annotation.Nullable;

/**
 *
 */
public abstract class DimensionAttributeProcessor<T extends View> extends AttributeProcessor<T> {

  public static float evaluate(Value value, View view) {
    if (value == null) {
      return Dimension.ZERO.apply(view.getContext());
    }

    final float[] result = new float[1];
    DimensionAttributeProcessor<View> processor = new DimensionAttributeProcessor<View>() {
      @Override
      public void setDimension(View view, float dimension) {
        result[0] = dimension;
      }
    };
    processor.process((View) view.getParent(), view, value);

    return result[0];
  }

  public static Value staticCompile(@Nullable Value value, ProteusContext context) {
    if (null == value || !value.isPrimitive()) {
      return Dimension.ZERO;
    }
    if (value.isDimension()) {
      return value;
    }
    Value precompiled = staticPreCompile(value.getAsPrimitive(), context, null);
    if (null != precompiled) {
      return precompiled;
    }
    return Dimension.valueOf(value.getAsString());
  }

  @Override
  public final void handleValue(View parent, T view, Value value) {
    if (value.isDimension()) {
      setDimension(view, value.getAsDimension().apply(view.getContext()));
    } else if (value.isPrimitive()) {
      process(parent, view, precompile(value, ProteusHelper.getProteusContext(view), (ProteusHelper.getProteusContext(view)).getFunctionManager()));
    }
  }

  @Override
  public void handleResource(View parent, T view, Resource resource) {
    Dimension dimension = resource.getDimension(ProteusHelper.getProteusContext(view));
    setDimension(view, null == dimension ? 0 : dimension.apply(ProteusHelper.getProteusContext(view)));
  }

  @Override
  public void handleAttributeResource(View parent, T view, AttributeResource attribute) {
    TypedArray a = attribute.apply(view.getContext());
    setDimension(view, a.getDimensionPixelSize(0, 0));
  }

  @Override
  public void handleStyle(View parent, T view, Style style) {
    System.out.println("Handle style: " + style);
//    TypedArray a = style.apply(view.getContext());
//    setDimension(view, a.getDimensionPixelSize(0, 0));
  }

  /**
   * @param view View
   */
  public abstract void setDimension(T view, float dimension);

  @Override
  public Value compile(@Nullable Value value, ProteusContext context) {
    return staticCompile(value, context);
  }

}
