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
import com.flipkart.android.proteus.value.Binding;
import com.flipkart.android.proteus.value.ObjectValue;

import androidx.annotation.NonNull;

/**
 * BoundAttribute holds the attribute id to binding pair
 * which is used in the update flow of a {@link ProteusView}
 * which is executed when {@link ViewManager#update(ObjectValue)}
 * is invoked.
 *
 * @author kirankumar
 * @author adityasharat
 */
public class BoundAttribute {

  /**
   * The {@code int} attribute id of the pair.
   */
  public final int attributeId;

  /**
   * The {@link Binding} for the layout attributes value.
   */
  @NonNull
  public final Binding binding;

  public BoundAttribute(int attributeId, @NonNull Binding binding) {
    this.attributeId = attributeId;
    this.binding = binding;
  }
}
