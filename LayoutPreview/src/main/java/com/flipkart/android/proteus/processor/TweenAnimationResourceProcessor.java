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

package com.flipkart.android.proteus.processor;

import android.util.Log;
import android.view.View;
import android.view.animation.Animation;

import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.toolbox.AnimationUtils;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.StyleResource;
import com.flipkart.android.proteus.value.Value;

/**
 * TweenAnimationResourceProcessor
 * <p>
 * TODO: implement pre compiling animation blocks
 * </p>
 *
 * @author yasirmhd
 */
public abstract class TweenAnimationResourceProcessor<V extends View> extends AttributeProcessor<V> {

  private static final String TAG = "TweenAnimationResource";

  @Override
  public void handleValue(V view, Value value) {
    Animation animation = AnimationUtils.loadAnimation(view.getContext(), value);
    if (null != animation) {
      setAnimation(view, animation);
    } else {
      if (ProteusConstants.isLoggingEnabled()) {
        Log.e(TAG, "Animation Resource must be a primitive or an object. value -> " + value.toString());
      }
    }
  }

  @Override
  public void handleResource(V view, Resource resource) {

  }

  @Override
  public void handleAttributeResource(V view, AttributeResource attribute) {

  }

  @Override
  public void handleStyleResource(V view, StyleResource style) {

  }

  public abstract void setAnimation(V view, Animation animation);
}
