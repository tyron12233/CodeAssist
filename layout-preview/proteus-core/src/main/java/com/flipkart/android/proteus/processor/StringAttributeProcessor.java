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
import android.util.Log;
import android.view.View;

import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

import androidx.annotation.Nullable;

/**
 * @author kirankumar
 * @author aditya.sharat
 */
public abstract class StringAttributeProcessor<V extends View> extends AttributeProcessor<V> {

  /**
   * @param parent
   * @param view  View
   * @param value
   */
  @Override
  public void handleValue(View parent, V view, Value value) {
    if (value.isPrimitive() || value.isNull()) {
      setString(view, value.getAsString());
    } else {
      setString(view, "[Object]");
    }
  }

  @Override
  public void handleResource(View parent, V view, Resource resource) {
    String string = resource.getString(ProteusHelper.getProteusContext(view));
    setString(view, null == string ? ProteusConstants.EMPTY : string);
  }

  @Override
  public void handleAttributeResource(View parent, V view, AttributeResource attribute) {
    TypedArray a = attribute.apply(view.getContext());
    setString(view, a.getString(0));
  }

  @Override
  public void handleStyle(View parent, V view, Style style) {
    Log.d("TEST", "Handle style called: " + style.toString());
//    TypedArray a = style.apply(view.getContext());
//    setString(view, a.getString(0));
  }

  /**
   * @param view View
   */
  public abstract void setString(V view, String value);

  @Override
  public Value compile(@Nullable Value value, ProteusContext context) {
    if (null == value || value.isNull()) {
      return ProteusConstants.EMPTY_STRING;
    }
    return value;
  }
}
