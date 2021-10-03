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

import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.Value;
import com.tyron.layoutpreview.StringManager;

import java.util.Locale;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ProteusResources
 *
 * @author adityasharat
 */

public class ProteusResources {

  @NonNull
  private final Map<String, ViewTypeParser> parsers;

  @Nullable
  private final LayoutManager layoutManager;

  @NonNull
  private final FunctionManager functionManager;

  @Nullable
  private final StyleManager styleManager;

  private final StringManager stringManager;

  ProteusResources(@NonNull Map<String, ViewTypeParser> parsers, @Nullable LayoutManager layoutManager,
                   @NonNull FunctionManager functionManager, @Nullable StyleManager styleManager,
                   StringManager stringManager) {
    this.parsers = parsers;
    this.layoutManager = layoutManager;
    this.functionManager = functionManager;
    this.styleManager = styleManager;
    this.stringManager = stringManager;
  }

  @NonNull
  public FunctionManager getFunctionManager() {
    return this.functionManager;
  }

  @NonNull
  public Function getFunction(@NonNull String name) {
    return functionManager.get(name);
  }

  @Nullable
  public Layout getLayout(@NonNull String name) {
    return null != layoutManager ? layoutManager.get(name) : null;
  }

  public Value getString(String name) {
    return null != stringManager ? stringManager.get(name, Locale.getDefault()) : null;
  }

  @NonNull
  public Map<String, ViewTypeParser> getParsers() {
    return parsers;
  }

  @Nullable
  public Map<String, Value> getStyle(String name) {
    return null != styleManager ? styleManager.get(name) : null;
  }
}
