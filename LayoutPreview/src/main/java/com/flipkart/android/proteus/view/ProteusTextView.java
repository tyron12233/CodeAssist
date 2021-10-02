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

package com.flipkart.android.proteus.view;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import com.flipkart.android.proteus.ProteusView;

import androidx.annotation.NonNull;

/**
 * TextView
 *
 * @author aditya.sharat
 */
public class ProteusTextView extends android.widget.TextView implements ProteusView {

  private Manager viewManager;

  public ProteusTextView(Context context) {
    super(context);
  }

  public ProteusTextView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public ProteusTextView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  public ProteusTextView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
  }

  @Override
  public Manager getViewManager() {
    return viewManager;
  }

  @Override
  public void setViewManager(@NonNull Manager manager) {
    this.viewManager = manager;
  }

  @NonNull
  @Override
  public View getAsView() {
    return this;
  }
}
