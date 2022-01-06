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

import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

import androidx.annotation.Nullable;

/**
 * BooleanAttributeProcessor
 *
 * @author aditya.sharat
 */

public abstract class BooleanAttributeProcessor<V extends View> extends AttributeProcessor<V> {

  @Override
  public void handleValue(View parent, V view, Value value) {
    if (value.isPrimitive() && value.getAsPrimitive().isBoolean()) {
      setBoolean(view, value.getAsPrimitive().getAsBoolean());
    } else {
      process(parent, view, precompile(value, (ProteusContext) view.getContext(), ProteusHelper.getProteusContext(view).getFunctionManager()));
    }
  }

  @Override
  public void handleResource(View parent, V view, Resource resource) {
    Boolean bool = resource.getBoolean(view.getContext());
    setBoolean(view, null != bool ? bool : false);
  }

  @Override
  public void handleAttributeResource(View parent, V view, AttributeResource attribute) {
    TypedArray a = attribute.apply(view.getContext());
    setBoolean(view, a.getBoolean(0, false));
  }

  @Override
  public void handleStyle(View parent, V view, Style style) {
//    TypedArray a = style.apply(view.getContext());
//    setBoolean(view, a.getBoolean(0, false));
  }

  public abstract void setBoolean(V view, boolean value);

  @Override
  public Value compile(@Nullable Value value, ProteusContext context) {
    return ParseHelper.parseBoolean(value) ? ProteusConstants.TRUE : ProteusConstants.FALSE;
  }
}
