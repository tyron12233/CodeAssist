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

import java.util.HashMap;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Size;

/**
 * Proteus
 *
 * @author aditya.sharat
 */

public final class Proteus {

  @NonNull
  public final FunctionManager functions;

  @NonNull
  private final Map<String, Type> types;

  @NonNull
  private final Map<String, ViewTypeParser> parsers;

  Proteus(@NonNull Map<String, Type> types, @NonNull final Map<String, Function> functions) {
    this.types = types;
    this.functions = new FunctionManager(functions);
    this.parsers = map(types);
  }

  public boolean has(@NonNull @Size(min = 1) String type) {
    return types.containsKey(type);
  }

  @Nullable
  public ViewTypeParser.AttributeSet.Attribute getAttributeId(@NonNull @Size(min = 1) String name, @NonNull @Size(min = 1) String type) {
    return types.get(type).getAttributeId(name);
  }

  private Map<String, ViewTypeParser> map(Map<String, Type> types) {
    Map<String, ViewTypeParser> parsers = new HashMap<>(types.size());
    for (Map.Entry<String, Type> entry : types.entrySet()) {
      parsers.put(entry.getKey(), entry.getValue().parser);
    }
    return parsers;
  }

  @NonNull
  public ProteusContext createContext(@NonNull Context base) {
    return createContextBuilder(base).build();
  }

  @NonNull
  public ProteusContext.Builder createContextBuilder(@NonNull Context base) {
    return new ProteusContext.Builder(base, parsers, functions);
  }

  public static class Type {

    public final int id;
    public final String type;
    public final ViewTypeParser parser;

    private final ViewTypeParser.AttributeSet attributes;

    Type(int id, @NonNull String type, @NonNull ViewTypeParser parser, @NonNull ViewTypeParser.AttributeSet attributes) {
      this.id = id;
      this.type = type;
      this.parser = parser;
      this.attributes = attributes;
    }

    @Nullable
    public ViewTypeParser.AttributeSet.Attribute getAttributeId(String name) {
      return attributes.getAttribute(name);
    }
  }
}
