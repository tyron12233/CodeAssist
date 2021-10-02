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

import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ProteusView
 * <p>
 * This interface is just to add behaviour to Android views
 * so they can host the Proteus View Managers. Since Java &lt; JDK 8
 * do not have default implementations for interfaces all Views
 * which need to be inflated via proteus need to implement this
 * interface.
 * </p>
 *
 * @author adtityasharat
 */
public interface ProteusView {

  /**
   * @return The View Manager of this Proteus View.
   */
  Manager getViewManager();

  /**
   * @param manager Sets a View Manager on this View.
   */
  void setViewManager(@NonNull Manager manager);

  /**
   * @return The interface as an Android native View.
   */
  @NonNull
  View getAsView();

  /**
   * Manager
   *
   * @author aditya.sharat
   */
  interface Manager {

    /**
     * Update the {@link View} with new data.
     *
     * @param data New data for the view
     */
    void update(@Nullable ObjectValue data);

    /**
     * Look for a child view with the given id.  If this view has the given
     * id, return this view. Similar to {@link View#findViewById(int)}. Since
     * Proteus is a runtime inflater, layouts use String ids instead of int and it
     * generates unique int ids using the {@link IdGenerator}.
     *
     * @param id The  string id to search for.
     * @return The view that has the given id in the hierarchy or null
     */
    @Nullable
    View findViewById(@NonNull String id);

    /**
     * @return The Proteus Context associated with this Manager.
     */
    @NonNull
    ProteusContext getContext();

    /**
     * @return The Layout of View which is hosting this manager.
     */
    @NonNull
    Layout getLayout();

    /**
     * @return The Data Context of the view which is hosting this manager.
     */
    @NonNull
    DataContext getDataContext();

    /**
     * Returns this proteus view's extras.
     *
     * @return the Object stored in this view as a extra, or {@code null} if not set
     * @see #setExtras(Object)
     */
    @Nullable
    Object getExtras();

    /**
     * Sets the extra associated with this view. A extra can be used to mark a view in its hierarchy
     * and does not have to be unique within the hierarchy. Extras can also be used to store data
     * within a proteus view without resorting to another data structure.
     * It is similar to {@link View#setTag(Object)}
     *
     * @param extras The object to set as the extra.
     * @see #setExtras(Object)
     */
    void setExtras(@Nullable Object extras);

  }
}
