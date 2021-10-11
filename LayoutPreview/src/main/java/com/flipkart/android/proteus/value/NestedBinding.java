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

package com.flipkart.android.proteus.value;

import android.content.Context;

import java.util.Iterator;
import java.util.Map;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusContext;

/**
 * NestedBinding
 *
 * @author adityasharat
 */

public class NestedBinding extends Binding {

  public static final String NESTED_BINDING_KEY = "@";

  private final Value value;

  private NestedBinding(Value value) {
    this.value = value;
  }

  /**
   * @param value
   * @return
   */
  @NonNull
  public static NestedBinding valueOf(@NonNull final Value value) {
    return new NestedBinding(value);
  }

  @NonNull
  public Value getValue() {
    return value;
  }

  @NonNull
  @Override
  public Value evaluate(ProteusContext context, Value data, int index) {
    return evaluate(context, value, data, index);
  }

  @NonNull
  @Override
  public String toString() {
    return getClass().getName() + "@" + Integer.toHexString(hashCode());
  }

  private Value evaluate(ProteusContext context, Binding binding, Value data, int index) {
    return binding.getAsBinding().evaluate(context, data, index);
  }

  private Value evaluate(Context context, ObjectValue object, Value data, int index) {
    ObjectValue evaluated = new ObjectValue();
    String key;
    Value value;
    for (Map.Entry<String, Value> entry : object.entrySet()) {
      key = entry.getKey();
      value = evaluate(context, entry.getValue(), data, index);
      evaluated.add(key, value);
    }

    return evaluated;
  }

  private Value evaluate(Context context, Array array, Value data, int index) {
    Array evaluated = new Array(array.size());
    Iterator<Value> iterator = array.iterator();
    while (iterator.hasNext()) {
      evaluated.add(evaluate(context, iterator.next(), data, index));
    }
    return evaluated;
  }

  private Value evaluate(Context context, Value value, Value data, int index) {
    Value evaluated = value;
    if (value.isBinding()) {
      evaluated = evaluate(context, value.getAsBinding(), data, index);
    } else if (value.isObject()) {
      evaluated = evaluate(context, value.getAsObject(), data, index);
    } else if (value.isArray()) {
      evaluated = evaluate(context, value.getAsArray(), data, index);
    }
    return evaluated;
  }

  @Override
  public Binding copy() {
    return this;
  }
}
