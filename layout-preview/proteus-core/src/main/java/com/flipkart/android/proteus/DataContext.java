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

package com.flipkart.android.proteus;

import com.flipkart.android.proteus.managers.ViewManager;
import com.flipkart.android.proteus.value.Binding.FunctionBinding;
import com.flipkart.android.proteus.value.Null;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * DataContext class hosts a the data, scope, index, and if
 * this context has it's own scope or if it inherits from a parent.
 * An instance of this class used in the update flow of a {@link ProteusView}
 * which is executed when {@link ViewManager#update(ObjectValue)} is
 * invoked. The {@link #data} is update on every update call.
 *
 * @author Aditya Sharat
 */
public class DataContext {

  /**
   * This property is used to identify whether
   * this data context is simply cloned from it's
   * parent data context. Which implies that the
   * {@link #scope} and {@link #data} were copied
   * from it's parents data context.
   */
  private final boolean hasOwnProperties;

  /**
   * This is the local isolated scope created for this
   * {@link ProteusView} hosting this instance of the
   * data context. This is populated from the {@code data}
   * attribute specified in the layout.
   */
  @Nullable
  private final Map<String, Value> scope;

  /**
   * This index is used to resolve the {@code $index} meta
   * values when dealing with arrays and data bound
   * {@code children} attribute.
   */
  private final int index;

  /**
   * The data which will be used to bind all data bound
   * attribute values of the layout.
   */
  private ObjectValue data;

  /**
   * This is the default constructor to create a new {@code DataContext}.
   * The {@link #hasOwnProperties} is initialized to {@code true} is and
   * only if the {@param scope} argument is non null. An empty non null,
   * null, a {@param scope} which does not refer to any data paths from
   * the parent scope implies that the hosting {@link ProteusView} is
   * completely isolated from the parent and cannot access any data from
   * above it in the hierarchy.
   *
   * @param scope the local isolate scope for this data context.
   * @param index the data index for this data context.
   */
  private DataContext(@Nullable Map<String, Value> scope, int index) {
    this.scope = scope;
    this.index = index;
    this.hasOwnProperties = scope != null;
  }

  /**
   * This is a copy constructor for creating a clone of
   * another data context. The {@link #hasOwnProperties}
   * is always {@code false} for a cloned data context.
   *
   * @param dataContext the parent data context to clone from.
   */
  public DataContext(DataContext dataContext) {
    this.data = dataContext.getData();
    this.scope = dataContext.getScope();
    this.index = dataContext.getIndex();
    this.hasOwnProperties = false;
  }

  /**
   * Utility method to create a new {@link DataContext} without any {@link #scope}.
   *
   * @param context   The proteus context to resolve {@link FunctionBinding} to evaluate the scope.
   * @param data      The data to be used by the data context.
   * @param dataIndex The data index to used by the data context.
   * @return A new data context with scope evaluated.
   */
  public static DataContext create(@NonNull ProteusContext context, @Nullable ObjectValue data, int dataIndex) {
    DataContext dataContext = new DataContext(null, dataIndex);
    dataContext.update(context, data);
    return dataContext;
  }

  /**
   * Utility method to create a new {@link DataContext} with a {@link #scope}.
   *
   * @param context   The proteus android context to resolve {@link FunctionBinding} to evaluate the scope.
   * @param data      The data to be used by the data context.
   * @param dataIndex The data index to used by the data context.
   * @param scope     The scope map used to create the {@link #data} of this data context.
   * @return A new data context with scope evaluated.
   */
  public static DataContext create(@NonNull ProteusContext context, @Nullable ObjectValue data,
                                   int dataIndex, @Nullable Map<String, Value> scope) {
    DataContext dataContext = new DataContext(scope, dataIndex);
    dataContext.update(context, data);
    return dataContext;
  }

  /**
   * Update this data context with new data.
   *
   * @param context The proteus context used to evaluate {@link FunctionBinding} to evaluate the scope.
   * @param in      The new data.
   */
  public void update(@NonNull ProteusContext context, @Nullable ObjectValue in) {
    if (in == null) {
      in = new ObjectValue();
    }

    if (scope == null) {
      data = in;
      return;
    }

    ObjectValue out = new ObjectValue();

    for (Map.Entry<String, Value> entry : scope.entrySet()) {
      String key = entry.getKey();
      Value value = entry.getValue();
      Value resolved;
      if (value.isBinding()) {
        resolved = value.getAsBinding().evaluate(context, out, index);
        if (resolved == Null.INSTANCE) {
          resolved = value.getAsBinding().evaluate(context, in, index);
        }
      } else {
        resolved = value;
      }
      out.add(key, resolved);
    }

    data = out;
  }

  /**
   * A utility method to create a child data context, with its own scope and index from the data
   * of this data context.
   *
   * @param context   The proteus context used to evaluate {@link FunctionBinding} to evaluate the scope.
   * @param scope     The scope for the new data context
   * @param dataIndex The data index to used by the new data context.
   * @return A new data context with scope evaluated.
   */
  public DataContext createChild(@NonNull ProteusContext context, @NonNull Map<String, Value> scope, int dataIndex) {
    return create(context, data, dataIndex, scope);
  }

  /**
   * Returns a clone of this data context with {@link #hasOwnProperties} set to {@code false}.
   *
   * @return a new cloned data context.
   */
  public DataContext copy() {
    return new DataContext(this);
  }

  public ObjectValue getData() {
    return data;
  }

  public void setData(ObjectValue data) {
    this.data = data;
  }

  @Nullable
  public Map<String, Value> getScope() {
    return scope;
  }

  public boolean hasOwnProperties() {
    return hasOwnProperties;
  }

  public int getIndex() {
    return index;
  }
}
