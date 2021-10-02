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

package com.flipkart.android.proteus.toolbox;

import android.view.View;

import com.flipkart.android.proteus.DataContext;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ManagerWrapper
 * <p>
 * Proxies the implementation of {@link ProteusView.Manager} that simply delegates
 * all of its calls to another Manager. Can be subclassed to modify or to add new behavior
 * without changing the original Manager.
 * </p>
 *
 * @author adityasharat
 */
public class ManagerWrapper implements ProteusView.Manager {

  private final ProteusView.Manager base;

  public ManagerWrapper(ProteusView.Manager base) {
    this.base = base;
  }

  @Override
  public void update(@Nullable ObjectValue data) {
    base.update(data);
  }

  @Nullable
  @Override
  public View findViewById(@NonNull String id) {
    return base.findViewById(id);
  }

  @NonNull
  @Override
  public ProteusContext getContext() {
    return base.getContext();
  }

  @NonNull
  @Override
  public Layout getLayout() {
    return base.getLayout();
  }

  @NonNull
  @Override
  public DataContext getDataContext() {
    return base.getDataContext();
  }

  @Nullable
  @Override
  public Object getExtras() {
    return base.getExtras();
  }

  @Override
  public void setExtras(@Nullable Object extras) {
    base.setExtras(extras);
  }

  public ProteusView.Manager getBaseManager() {
    return base;
  }
}
