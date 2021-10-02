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

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.processor.DrawableResourceProcessor;
import com.flipkart.android.proteus.processor.GravityAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.view.ProteusLinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Created by kiran.kumar on 12/05/14.
 */
public class LinearLayoutParser<T extends LinearLayout> extends ViewTypeParser<T> {

  @NonNull
  @Override
  public String getType() {
    return "LinearLayout";
  }

  @Nullable
  @Override
  public String getParentType() {
    return "ViewGroup";
  }

  @NonNull
  @Override
  public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data,
                                @Nullable ViewGroup parent, int dataIndex) {
    return new ProteusLinearLayout(context);
  }

  @Override
  protected void addAttributeProcessors() {

    addAttributeProcessor(Attributes.LinearLayout.Orientation, new StringAttributeProcessor<T>() {
      @Override
      public void setString(T view, String value) {
        if ("horizontal".equals(value)) {
          view.setOrientation(ProteusLinearLayout.HORIZONTAL);
        } else {
          view.setOrientation(ProteusLinearLayout.VERTICAL);
        }
      }
    });

    addAttributeProcessor(Attributes.View.Gravity, new GravityAttributeProcessor<T>() {
      @Override
      public void setGravity(T view, @Gravity int gravity) {
        view.setGravity(gravity);
      }
    });

    addAttributeProcessor(Attributes.LinearLayout.Divider, new DrawableResourceProcessor<T>() {
      @SuppressLint("NewApi")
      @Override
      public void setDrawable(T view, Drawable drawable) {

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.GINGERBREAD_MR1) {
          view.setDividerDrawable(drawable);
        }
      }
    });

    addAttributeProcessor(Attributes.LinearLayout.DividerPadding, new DimensionAttributeProcessor<T>() {
      @SuppressLint("NewApi")
      @Override
      public void setDimension(T view, float dimension) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.GINGERBREAD_MR1) {
          view.setDividerPadding((int) dimension);
        }
      }
    });

    addAttributeProcessor(Attributes.LinearLayout.ShowDividers, new StringAttributeProcessor<T>() {
      @SuppressLint("NewApi")
      @Override
      public void setString(T view, String value) {

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.GINGERBREAD_MR1) {
          int dividerMode = ParseHelper.parseDividerMode(value);
          // noinspection ResourceType
          view.setShowDividers(dividerMode);
        }
      }
    });

    addAttributeProcessor(Attributes.LinearLayout.WeightSum, new StringAttributeProcessor<T>() {
      @SuppressLint("NewApi")
      @Override
      public void setString(T view, String value) {
        view.setWeightSum(ParseHelper.parseFloat(value));
      }
    });
  }
}
