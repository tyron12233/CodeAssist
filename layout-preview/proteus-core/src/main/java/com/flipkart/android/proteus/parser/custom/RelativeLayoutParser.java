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


import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.GravityAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.view.ProteusRelativeLayout;

/**
 * Created by kirankumar on 10/07/14.
 */
public class RelativeLayoutParser<T extends View> extends ViewTypeParser<T> {

  @NonNull
  @Override
  public String getType() {
    return "android.widget.RelativeLayout";
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
    return new ProteusRelativeLayout(context);
  }

  @Override
  protected void addAttributeProcessors() {

    addAttributeProcessor(Attributes.View.Gravity, new GravityAttributeProcessor<T>() {
      @Override
      public void setGravity(T view, @Gravity int gravity) {
        if (view instanceof RelativeLayout) {
          ((RelativeLayout) view).setGravity(gravity);
        }
      }
    });

    addLayoutParamsAttributeProcessor(Attributes.View.Above, createRelativeLayoutRuleProcessor(RelativeLayout.ABOVE));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignBaseline, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_BASELINE));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignBottom, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_BOTTOM));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignLeft, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_LEFT));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignRight, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_RIGHT));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignTop, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_TOP));
    addLayoutParamsAttributeProcessor(Attributes.View.Below, createRelativeLayoutRuleProcessor(RelativeLayout.BELOW));
    addLayoutParamsAttributeProcessor(Attributes.View.ToLeftOf, createRelativeLayoutRuleProcessor(RelativeLayout.LEFT_OF));
    addLayoutParamsAttributeProcessor(Attributes.View.ToRightOf, createRelativeLayoutRuleProcessor(RelativeLayout.RIGHT_OF));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignEnd, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_END));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignStart, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_START));
    addLayoutParamsAttributeProcessor(Attributes.View.ToEndOf, createRelativeLayoutRuleProcessor(RelativeLayout.END_OF));
    addLayoutParamsAttributeProcessor(Attributes.View.ToStartOf, createRelativeLayoutRuleProcessor(RelativeLayout.START_OF));

    addLayoutParamsAttributeProcessor(Attributes.View.AlignParentTop, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_TOP));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignParentRight, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_RIGHT));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignParentBottom, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_BOTTOM));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignParentLeft, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_LEFT));
    addLayoutParamsAttributeProcessor(Attributes.View.CenterHorizontal, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.CENTER_HORIZONTAL));
    addLayoutParamsAttributeProcessor(Attributes.View.CenterVertical, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.CENTER_VERTICAL));
    addLayoutParamsAttributeProcessor(Attributes.View.CenterInParent, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.CENTER_IN_PARENT));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignParentStart, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_START));
    addLayoutParamsAttributeProcessor(Attributes.View.AlignParentEnd, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_END));
  }

  private AttributeProcessor<T> createRelativeLayoutRuleProcessor(final int rule) {
    return new StringAttributeProcessor<T>() {
      @Override
      public void setString(T view, String value) {
        if (view instanceof ProteusView) {
          int id = ((ProteusView) view).getViewManager()
                  .getContext()
                  .getInflater()
                  .getUniqueViewId(ParseHelper.parseViewId(value));
          ParseHelper.addRelativeLayoutRule(view, rule, id);
        }
      }
    };
  }

  private AttributeProcessor<T> createRelativeLayoutBooleanRuleProcessor(final int rule) {
    return new BooleanAttributeProcessor<T>() {
      @Override
      public void setBoolean(T view, boolean value) {
        int trueOrFalse = ParseHelper.parseRelativeLayoutBoolean(value);
        ParseHelper.addRelativeLayoutRule(view, rule, trueOrFalse);
      }
    };
  }
}
