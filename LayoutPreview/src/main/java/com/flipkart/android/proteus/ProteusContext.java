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

import android.content.Context;
import android.content.ContextWrapper;

import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.Value;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ProteusContext
 *
 * @author aditya.sharat
 */

public class ProteusContext extends ContextWrapper {

  @NonNull
  private final ProteusResources resources;

  @Nullable
  private final ProteusLayoutInflater.Callback callback;

  @Nullable
  private final ProteusLayoutInflater.ImageLoader loader;

  private ProteusLayoutInflater inflater;

  ProteusContext(Context base, @NonNull ProteusResources resources,
                 @Nullable ProteusLayoutInflater.ImageLoader loader,
                 @Nullable ProteusLayoutInflater.Callback callback) {
    super(base);
    this.callback = callback;
    this.loader = loader;
    this.resources = resources;
  }

  @Nullable
  public ProteusLayoutInflater.Callback getCallback() {
    return callback;
  }

  @NonNull
  public FunctionManager getFunctionManager() {
    return resources.getFunctionManager();
  }

  @NonNull
  public Function getFunction(@NonNull String name) {
    return resources.getFunction(name);
  }

  @Nullable
  public Layout getLayout(@NonNull String name) {
    return resources.getLayout(name);
  }

  @Nullable
  public ProteusLayoutInflater.ImageLoader getLoader() {
    return loader;
  }

  @NonNull
  public ProteusLayoutInflater getInflater(@NonNull IdGenerator idGenerator) {
    if (null == this.inflater) {
      this.inflater = new SimpleLayoutInflater(this, idGenerator);
    }
    return this.inflater;
  }

  @NonNull
  public ProteusLayoutInflater getInflater() {
    return getInflater(new SimpleIdGenerator());
  }

  @Nullable
  public ViewTypeParser getParser(String type) {
    return resources.getParsers().get(type);
  }

  @NonNull
  public ProteusResources getProteusResources() {
    return resources;
  }

  @Nullable
  public Map<String, Value> getStyle(String name) {
    return resources.getStyle(name);
  }

  /**
   * Builder
   *
   * @author adityasharat
   */
  public static class Builder {

    @NonNull
    private final Context base;

    @NonNull
    private final FunctionManager functionManager;

    @NonNull
    private final Map<String, ViewTypeParser> parsers;

    @Nullable
    private ProteusLayoutInflater.ImageLoader loader;

    @Nullable
    private ProteusLayoutInflater.Callback callback;

    @Nullable
    private LayoutManager layoutManager;

    @Nullable
    private StyleManager styleManager;

    Builder(@NonNull Context context, @NonNull Map<String, ViewTypeParser> parsers, @NonNull FunctionManager functionManager) {
      this.base = context;
      this.parsers = parsers;
      this.functionManager = functionManager;
    }

    public Builder setImageLoader(@Nullable ProteusLayoutInflater.ImageLoader loader) {
      this.loader = loader;
      return this;
    }

    public Builder setCallback(@Nullable ProteusLayoutInflater.Callback callback) {
      this.callback = callback;
      return this;
    }

    public Builder setLayoutManager(@Nullable LayoutManager layoutManager) {
      this.layoutManager = layoutManager;
      return this;
    }

    public Builder setStyleManager(@Nullable StyleManager styleManager) {
      this.styleManager = styleManager;
      return this;
    }

    public ProteusContext build() {
      ProteusResources resources = new ProteusResources(parsers, layoutManager, functionManager, styleManager);
      return new ProteusContext(base, resources, loader, callback);
    }

  }
}
