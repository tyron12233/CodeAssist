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

import android.view.View;
import android.view.ViewGroup;

import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * @author kirankumar
 * @author adityasharat
 */
public interface ProteusLayoutInflater {

  /**
   * This methods inflates a {@link ProteusView}
   *
   * @param layout    The {@link Layout} which defines the layout for the {@link View} to be inflated.
   * @param data      The {@link ObjectValue} which will be used to replace bindings with values in the {@link View}.
   * @param parent    The intended parent view for the {@link View} that will be inflated.
   * @param dataIndex An index of data, if it is associated with some index of an array. Pass 0 by default.
   * @return An native android view
   */
  @NonNull
  ProteusView inflate(@NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex);

  /**
   * This methods inflates a {@link ProteusView}
   *
   * @param layout    The {@link Layout} which defines the layout for the {@link View} to be inflated.
   * @param data      The {@link ObjectValue} which will be used to replace bindings with values in the {@link View}.
   * @param dataIndex An index of data, if it is associated with some index of an array. Pass 0 by default.
   * @return An native android view
   */
  @NonNull
  ProteusView inflate(@NonNull Layout layout, @NonNull ObjectValue data, int dataIndex);

  /**
   * This methods inflates a {@link ProteusView}
   *
   * @param layout The {@link Layout} which defines the layout for the {@link View} to be inflated.
   * @param data   The {@link ObjectValue} which will be used to replace bindings with values in the {@link View}.
   * @return An native android view
   */
  @NonNull
  ProteusView inflate(@NonNull Layout layout, @NonNull ObjectValue data);

  /**
   * This methods inflates a {@link ProteusView}
   *
   * @param name      The name of the layout to be inflated
   * @param data      The {@link ObjectValue} which will be used to replace bindings with values in the {@link View}.
   * @param parent    The intended parent view for the {@link View} that will be inflated.
   * @param dataIndex An index of data, if it is associated with some index of an array. Pass 0 by default.
   * @return An native android view
   */
  @NonNull
  ProteusView inflate(@NonNull String name, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex);

  /**
   * This methods inflates a {@link ProteusView}
   *
   * @param name      The name of the layout to be inflated
   * @param data      The {@link ObjectValue} which will be used to replace bindings with values in the {@link View}.
   * @param dataIndex An index of data, if it is associated with some index of an array. Pass 0 by default.
   * @return An native android view
   */
  @NonNull
  ProteusView inflate(@NonNull String name, @NonNull ObjectValue data, int dataIndex);

  /**
   * This methods inflates a {@link ProteusView}
   *
   * @param name The name of the layout to be inflated
   * @param data The {@link ObjectValue} which will be used to replace bindings with values in the {@link View}.
   * @return An native android view
   */
  @NonNull
  ProteusView inflate(@NonNull String name, @NonNull ObjectValue data);

  /**
   * Returns the {@link ViewTypeParser} for the specified view type.
   *
   * @param type The name of the view type.
   * @return The {@link ViewTypeParser} associated to the specified view type
   */
  @Nullable
  ViewTypeParser getParser(@NonNull Layout type);

  /**
   * Give the View ID for this string. This will generally be given by the instance of ID Generator
   * which will be available with the Layout Inflater.
   * This is similar to R.id auto generated
   *
   * @return int value for this id. This will never be -1.
   */
  int getUniqueViewId(@NonNull String id);

  /**
   * All consumers of this should ensure that they save the instance state of
   * the ID generator along with the activity/fragment and resume it when the
   * Layout Inflater is being re-initialized
   *
   * @return Returns the Id Generator for this Layout Inflater
   */
  @NonNull
  IdGenerator getIdGenerator();

  /**
   * The Layout Inflaters callback interface
   */
  interface Callback {

    /**
     * This call back is called when the inflater encounters a view type which is no registered with it.
     *
     * @param context The Proteus Context
     * @param type    The type of the layout
     * @param layout  The layout
     * @param data    The data which should be bound to this view
     * @param index   The data index
     * @return The native view to be returned
     */
    @NonNull
    ProteusView onUnknownViewType(ProteusContext context, ViewGroup parent, String type, Layout layout, ObjectValue data, int index);

    /**
     * This callback is called when any user interaction occurs on a view
     *
     * @param event The Event type
     * @param value Value set to the event attribute
     * @param view  The view that triggered the event
     */
    void onEvent(String event, Value value, ProteusView view);

  }

  /**
   * Used for loading drawables/images/bitmaps asynchronously
   */
  interface ImageLoader {
    /**
     * Useful for asynchronous download of bitmap.
     *
     * @param url      the url for the drawable/bitmap/image
     * @param callback the callback to set the drawable/bitmap
     */
    void getBitmap(View view, String url, DrawableValue.AsyncCallback callback);
  }
}
