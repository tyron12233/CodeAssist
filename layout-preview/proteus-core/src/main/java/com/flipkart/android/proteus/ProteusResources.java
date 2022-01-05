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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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

    private final DrawableManager drawableManager;

    private final ColorManager colorManager;

    private final DimensionManager dimensionManager;


    ProteusResources(@NonNull Map<String, ViewTypeParser> parsers, @Nullable LayoutManager layoutManager, @NonNull FunctionManager functionManager, @Nullable StyleManager styleManager, StringManager stringManager, DrawableManager drawableManager, ColorManager colorManager, DimensionManager dimensionManager) {
        this.parsers = parsers;
        this.layoutManager = layoutManager;
        this.functionManager = functionManager;
        this.styleManager = styleManager;
        this.stringManager = stringManager;
        this.drawableManager = drawableManager;
        this.colorManager = colorManager;
        this.dimensionManager = dimensionManager;
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

    public DrawableValue getDrawable(String name) {
        return null != drawableManager ? drawableManager.get(name) : null;
    }

    public Value getColor(String name) {
        if (name.startsWith("@color/")) {
            name = name.substring("@color/".length());
        }
        return null != colorManager ? colorManager.getColor(name) : null;
    }

    public List<Style> findStyle(String name) {
        return styleManager.getStyles().entrySet().stream()
                .filter(e -> e.getKey().contains(name))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    @NonNull
    public Map<String, ViewTypeParser> getParsers() {
        return parsers;
    }

    @Nullable
    public Style getStyle(String name) {
        if (name.startsWith("@style/")) {
            name = name.substring("@style/".length());
        }
        return null != styleManager ? styleManager.get(name) : null;
    }

    public Value getDimension(String name) {
        if (name.startsWith("@dimen/")) {
            name = name.substring("@dimen/".length());
        }
        return null != dimensionManager ? dimensionManager.getDimension(name) : null;
    }
}
