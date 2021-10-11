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

package com.flipkart.android.proteus.managers;

import android.view.View;

import com.flipkart.android.proteus.DataContext;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Layout;

import androidx.annotation.NonNull;

/**
 * AdapterBasedViewManager.
 *
 * @author adityasharat
 */
public class AdapterBasedViewManager extends ViewGroupManager {

  public AdapterBasedViewManager(@NonNull ProteusContext context, @NonNull ViewTypeParser parser,
                                 @NonNull View view, @NonNull Layout layout, @NonNull DataContext dataContext) {
    super(context, parser, view, layout, dataContext);
  }

  /**
   * Ignore updating the children in this case, that
   * should be handled by the adapter attached to the view.
   */
  @Override
  protected void updateChildren() {

  }
}
