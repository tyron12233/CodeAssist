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
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.flipkart.android.proteus.ProteusConstants;
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
public class LinearLayoutParser<T extends View> extends ViewTypeParser<T> {

  @NonNull
  @Override
  public String getType() {
    return "android.widget.LinearLayout";
  }

  @Nullable
  @Override
  public String getParentType() {
    return "android.view.ViewGroup";
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
        if (view instanceof LinearLayout) {
          if ("horizontal".equals(value)) {
            ((LinearLayout) view).setOrientation(ProteusLinearLayout.HORIZONTAL);
          } else {
            ((LinearLayout) view).setOrientation(ProteusLinearLayout.VERTICAL);
          }
        }
      }
    });

    addAttributeProcessor(Attributes.View.Gravity, new GravityAttributeProcessor<T>() {
      @Override
      public void setGravity(T view, @Gravity int gravity) {
        if (view instanceof LinearLayout) {
          ((LinearLayout) view).setGravity(gravity);
        }
      }
    });

    addAttributeProcessor(Attributes.LinearLayout.Divider, new DrawableResourceProcessor<T>() {
      @SuppressLint("NewApi")
      @Override
      public void setDrawable(T view, Drawable drawable) {
        if (view instanceof LinearLayout) {
          ((LinearLayout) view).setDividerDrawable(drawable);
        }
      }
    });

    addAttributeProcessor(Attributes.LinearLayout.DividerPadding, new DimensionAttributeProcessor<T>() {
      @SuppressLint("NewApi")
      @Override
      public void setDimension(T view, float dimension) {
        if (view instanceof LinearLayout) {
          ((LinearLayout) view).setDividerPadding((int) dimension);
        }
      }
    });

    addAttributeProcessor(Attributes.LinearLayout.ShowDividers, new StringAttributeProcessor<T>() {
      @SuppressLint("NewApi")
      @Override
      public void setString(T view, String value) {

        int dividerMode = ParseHelper.parseDividerMode(value);
        // noinspection ResourceType
        if (view instanceof LinearLayout) {
          ((LinearLayout) view).setShowDividers(dividerMode);
        }
      }
    });

    addAttributeProcessor(Attributes.LinearLayout.WeightSum, new StringAttributeProcessor<T>() {
      @SuppressLint("NewApi")
      @Override
      public void setString(T view, String value) {
        if (view instanceof LinearLayout) {
          ((LinearLayout) view).setWeightSum(ParseHelper.parseFloat(value));
        }
      }
    });

    addLayoutParamsAttributeProcessor(Attributes.View.Weight, new StringAttributeProcessor<T>() {
      @Override
      public void setString(View view, String value) {
        LinearLayout.LayoutParams layoutParams;
        if (view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
          layoutParams = (LinearLayout.LayoutParams) view.getLayoutParams();
          layoutParams.weight = ParseHelper.parseFloat(value);
          view.setLayoutParams(layoutParams);
        } else {
          if (ProteusConstants.isLoggingEnabled()) {
            Log.e("LinearLayoutParser", "'weight' is only supported for LinearLayouts");
          }
        }
      }
    });
  }
}
