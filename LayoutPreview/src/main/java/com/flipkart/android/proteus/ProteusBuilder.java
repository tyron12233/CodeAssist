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

import com.flipkart.android.proteus.parser.IncludeParser;
import com.flipkart.android.proteus.parser.ViewParser;
import com.flipkart.android.proteus.parser.custom.ButtonParser;
import com.flipkart.android.proteus.parser.custom.CheckBoxParser;
import com.flipkart.android.proteus.parser.custom.EditTextParser;
import com.flipkart.android.proteus.parser.custom.FrameLayoutParser;
import com.flipkart.android.proteus.parser.custom.HorizontalProgressBarParser;
import com.flipkart.android.proteus.parser.custom.HorizontalScrollViewParser;
import com.flipkart.android.proteus.parser.custom.ImageButtonParser;
import com.flipkart.android.proteus.parser.custom.ImageViewParser;
import com.flipkart.android.proteus.parser.custom.LinearLayoutParser;
import com.flipkart.android.proteus.parser.custom.ProgressBarParser;
import com.flipkart.android.proteus.parser.custom.RatingBarParser;
import com.flipkart.android.proteus.parser.custom.RelativeLayoutParser;
import com.flipkart.android.proteus.parser.custom.ScrollViewParser;
import com.flipkart.android.proteus.parser.custom.TextViewParser;
import com.flipkart.android.proteus.parser.custom.ViewGroupParser;
import com.flipkart.android.proteus.parser.custom.WebViewParser;
import com.flipkart.android.proteus.processor.AttributeProcessor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ProteusBuilder
 *
 * @author aditya.sharat
 */

public class ProteusBuilder {

  /**
   * The Default Module of Proteus.
   */
  public static final Module DEFAULT_MODULE = new Module() {

    @Override
    public void registerWith(ProteusBuilder builder) {

      // register the default parsers
      builder.register(new ViewParser());
      builder.register(new IncludeParser());
      builder.register(new ViewGroupParser());
      builder.register(new RelativeLayoutParser());
      builder.register(new LinearLayoutParser());
      builder.register(new FrameLayoutParser());
      builder.register(new ScrollViewParser());
      builder.register(new HorizontalScrollViewParser());
      builder.register(new ImageViewParser());
      builder.register(new TextViewParser());
      builder.register(new EditTextParser());
      builder.register(new ButtonParser());
      builder.register(new ImageButtonParser());
      builder.register(new WebViewParser());
      builder.register(new RatingBarParser());
      builder.register(new CheckBoxParser());
      builder.register(new ProgressBarParser());
      builder.register(new HorizontalProgressBarParser());

      // register the default functions
      builder.register(Function.DATE);
      builder.register(Function.FORMAT);
      builder.register(Function.JOIN);
      builder.register(Function.NUMBER);

      builder.register(Function.ADD);
      builder.register(Function.SUBTRACT);
      builder.register(Function.MULTIPLY);
      builder.register(Function.DIVIDE);
      builder.register(Function.MODULO);

      builder.register(Function.AND);
      builder.register(Function.OR);

      builder.register(Function.NOT);

      builder.register(Function.EQUALS);
      builder.register(Function.LESS_THAN);
      builder.register(Function.GREATER_THAN);
      builder.register(Function.LESS_THAN_OR_EQUALS);
      builder.register(Function.GREATER_THAN_OR_EQUALS);

      builder.register(Function.TERNARY);

      builder.register(Function.CHAR_AT);
      builder.register(Function.CONTAINS);
      builder.register(Function.IS_EMPTY);
      builder.register(Function.LENGTH);
      builder.register(Function.TRIM);

      builder.register(Function.MAX);
      builder.register(Function.MIN);

      builder.register(Function.SLICE);
    }
  };

  private static final int ID = -1;
  private Map<String, Map<String, AttributeProcessor>> processors = new LinkedHashMap<>();
  private Map<String, ViewTypeParser> parsers = new LinkedHashMap<>();
  private HashMap<String, Function> functions = new HashMap<>();

  public ProteusBuilder() {
    DEFAULT_MODULE.registerWith(this);
  }

  public ProteusBuilder register(@NonNull String type, @NonNull Map<String, AttributeProcessor> processors) {
    Map<String, AttributeProcessor> map = getExtraAttributeProcessors(type);
    map.putAll(processors);
    return this;
  }

  public ProteusBuilder register(@NonNull String type, @NonNull String name, @NonNull AttributeProcessor processor) {
    Map<String, AttributeProcessor> map = getExtraAttributeProcessors(type);
    map.put(name, processor);
    return this;
  }

  public ProteusBuilder register(@NonNull ViewTypeParser parser) {
    String parentType = parser.getParentType();
    if (parentType != null && !parsers.containsKey(parentType)) {
      throw new IllegalStateException(parentType + " is not a registered type parser");
    }
    parsers.put(parser.getType(), parser);
    return this;
  }

  public ProteusBuilder register(@NonNull Function function) {
    functions.put(function.getName(), function);
    return this;
  }

  public ProteusBuilder register(@NonNull Module module) {
    module.registerWith(this);
    return this;
  }

  @Nullable
  public ViewTypeParser get(@NonNull String type) {
    return parsers.get(type);
  }

  public Proteus build() {
    Map<String, Proteus.Type> types = new HashMap<>();
    for (Map.Entry<String, ViewTypeParser> entry : parsers.entrySet()) {
      types.put(entry.getKey(), prepare(entry.getValue()));
    }
    return new Proteus(types, functions);
  }

  protected Proteus.Type prepare(ViewTypeParser parser) {
    String name = parser.getType();
    ViewTypeParser parent = parsers.get(parser.getParentType());
    Map<String, AttributeProcessor> extras = this.processors.get(name);

    //noinspection unchecked
    return new Proteus.Type(ID, name, parser, parser.prepare(parent, extras));
  }

  protected Map<String, AttributeProcessor> getExtraAttributeProcessors(String type) {
    Map<String, AttributeProcessor> map = this.processors.get(type);
    if (map == null) {
      map = new LinkedHashMap<>();
      this.processors.put(type, map);
    }
    return map;
  }

  public interface Module {

    /**
     * @param builder
     */
    void registerWith(ProteusBuilder builder);

  }
}
