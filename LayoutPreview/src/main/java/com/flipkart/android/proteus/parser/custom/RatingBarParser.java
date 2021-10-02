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

import android.graphics.drawable.Drawable;
import android.view.ViewGroup;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.processor.DrawableResourceProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.view.ProteusFixedRatingBar;
import com.flipkart.android.proteus.view.custom.FixedRatingBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Created by kiran.kumar on 12/05/14.
 */
public class RatingBarParser<T extends FixedRatingBar> extends ViewTypeParser<T> {

  @NonNull
  @Override
  public String getType() {
    return "RatingBar";
  }

  @Nullable
  @Override
  public String getParentType() {
    return "View";
  }

  @NonNull
  @Override
  public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data,
                                @Nullable ViewGroup parent, int dataIndex) {
    return new ProteusFixedRatingBar(context);
  }

  @Override
  protected void addAttributeProcessors() {

    addAttributeProcessor(Attributes.RatingBar.NumStars, new StringAttributeProcessor<T>() {
      @Override
      public void setString(T view, String value) {
        view.setNumStars(ParseHelper.parseInt(value));
      }
    });
    addAttributeProcessor(Attributes.RatingBar.Rating, new StringAttributeProcessor<T>() {
      @Override
      public void setString(T view, String value) {
        view.setRating(ParseHelper.parseFloat(value));
      }
    });
    addAttributeProcessor(Attributes.RatingBar.IsIndicator, new BooleanAttributeProcessor<T>() {
      @Override
      public void setBoolean(T view, boolean value) {
        view.setIsIndicator(value);
      }
    });
    addAttributeProcessor(Attributes.RatingBar.StepSize, new StringAttributeProcessor<T>() {
      @Override
      public void setString(T view, String value) {
        view.setStepSize(ParseHelper.parseFloat(value));
      }
    });
    addAttributeProcessor(Attributes.RatingBar.MinHeight, new DimensionAttributeProcessor<T>() {
      @Override
      public void setDimension(T view, float dimension) {
        view.setMinimumHeight((int) dimension);
      }
    });
    addAttributeProcessor(Attributes.RatingBar.ProgressDrawable, new DrawableResourceProcessor<T>() {
      @Override
      public void setDrawable(T view, Drawable drawable) {
        drawable = view.getTiledDrawable(drawable, false);
        view.setProgressDrawable(drawable);
      }
    });
  }
}
