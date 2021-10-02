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
import android.view.View;

import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.StyleResource;
import com.flipkart.android.proteus.value.Value;

import androidx.annotation.Nullable;

/**
 * BooleanAttributeProcessor
 *
 * @author aditya.sharat
 */

public abstract class BooleanAttributeProcessor<V extends View> extends AttributeProcessor<V> {

  @Override
  public void handleValue(V view, Value value) {
    if (value.isPrimitive() && value.getAsPrimitive().isBoolean()) {
      setBoolean(view, value.getAsPrimitive().getAsBoolean());
    } else {
      process(view, precompile(value, view.getContext(), ((ProteusContext) view.getContext()).getFunctionManager()));
    }
  }

  @Override
  public void handleResource(V view, Resource resource) {
    Boolean bool = resource.getBoolean(view.getContext());
    setBoolean(view, null != bool ? bool : false);
  }

  @Override
  public void handleAttributeResource(V view, AttributeResource attribute) {
    TypedArray a = attribute.apply(view.getContext());
    setBoolean(view, a.getBoolean(0, false));
  }

  @Override
  public void handleStyleResource(V view, StyleResource style) {
    TypedArray a = style.apply(view.getContext());
    setBoolean(view, a.getBoolean(0, false));
  }

  public abstract void setBoolean(V view, boolean value);

  @Override
  public Value compile(@Nullable Value value, Context context) {
    return ParseHelper.parseBoolean(value) ? ProteusConstants.TRUE : ProteusConstants.FALSE;
  }
}
