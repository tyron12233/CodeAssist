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
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.StyleResource;
import com.flipkart.android.proteus.value.Value;

import androidx.annotation.Nullable;

/**
 * @author kirankumar
 * @author aditya.sharat
 */
public abstract class StringAttributeProcessor<V extends View> extends AttributeProcessor<V> {

  /**
   * @param view  View
   * @param value
   */
  @Override
  public void handleValue(V view, Value value) {
    if (value.isPrimitive() || value.isNull()) {
      setString(view, value.getAsString());
    } else {
      setString(view, "[Object]");
    }
  }

  @Override
  public void handleResource(V view, Resource resource) {
    String string = resource.getString(view.getContext());
    setString(view, null == string ? ProteusConstants.EMPTY : string);
  }

  @Override
  public void handleAttributeResource(V view, AttributeResource attribute) {
    TypedArray a = attribute.apply(view.getContext());
    setString(view, a.getString(0));
  }

  @Override
  public void handleStyleResource(V view, StyleResource style) {
    TypedArray a = style.apply(view.getContext());
    setString(view, a.getString(0));
  }

  /**
   * @param view View
   */
  public abstract void setString(V view, String value);

  @Override
  public Value compile(@Nullable Value value, Context context) {
    if (null == value || value.isNull()) {
      return ProteusConstants.EMPTY_STRING;
    }
    return value;
  }
}
