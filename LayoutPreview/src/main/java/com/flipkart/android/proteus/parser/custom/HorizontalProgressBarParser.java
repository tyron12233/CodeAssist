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

package com.flipkart.android.proteus.parser.custom;

import android.view.ViewGroup;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.view.ProteusHorizontalProgressBar;
import com.flipkart.android.proteus.view.custom.HorizontalProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * HorizontalProgressBarParser
 *
 * @author Aditya Sharat
 */
public class HorizontalProgressBarParser<T extends HorizontalProgressBar> extends ViewTypeParser<T> {

  @NonNull
  @Override
  public String getType() {
    return "android.widget.HorizontalProgressBar";
  }

  @Nullable
  @Override
  public String getParentType() {
    return "android.widget.ProgressBar";
  }

  @NonNull
  @Override
  public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data,
                                @Nullable ViewGroup parent, int dataIndex) {
    return new ProteusHorizontalProgressBar(context);
  }

  @Override
  protected void addAttributeProcessors() {
  }

}
