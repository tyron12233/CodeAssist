/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.api.reporting.components.internal;

import org.apache.commons.lang3.StringUtils;
import com.tyron.builder.api.tasks.diagnostics.internal.text.TextReportBuilder;
import com.tyron.builder.language.base.LanguageSourceSet;
import com.tyron.builder.platform.base.BinarySpec;
import com.tyron.builder.platform.base.ComponentSpec;
import com.tyron.builder.platform.base.SourceComponentSpec;
import com.tyron.builder.platform.base.VariantComponentSpec;
import com.tyron.builder.reporting.ReportRenderer;
import com.tyron.builder.util.internal.CollectionUtils;

public class ComponentRenderer extends ReportRenderer<ComponentSpec, TextReportBuilder> {
    private final ReportRenderer<LanguageSourceSet, TextReportBuilder> sourceSetRenderer;
    private final ReportRenderer<BinarySpec, TextReportBuilder> binaryRenderer;

    public ComponentRenderer(ReportRenderer<LanguageSourceSet, TextReportBuilder> sourceSetRenderer, ReportRenderer<BinarySpec, TextReportBuilder> binaryRenderer) {
        this.sourceSetRenderer = sourceSetRenderer;
        this.binaryRenderer = binaryRenderer;
    }

    @Override
    public void render(ComponentSpec component, TextReportBuilder builder) {
        builder.heading(StringUtils.capitalize(component.getDisplayName()));
        if (component instanceof SourceComponentSpec) {
            SourceComponentSpec sourceComponentSpec = (SourceComponentSpec) component;
            builder.getOutput().println();
            builder.collection("Source sets", CollectionUtils.sort(sourceComponentSpec.getSources().values(), SourceSetRenderer.SORT_ORDER), sourceSetRenderer, "source sets");
        }
        if (component instanceof VariantComponentSpec) {
            VariantComponentSpec variantComponentSpec = (VariantComponentSpec) component;
            builder.getOutput().println();
            builder.collection("Binaries", CollectionUtils.sort(variantComponentSpec.getBinaries().values(), TypeAwareBinaryRenderer.SORT_ORDER), binaryRenderer, "binaries");
        }
    }
}
