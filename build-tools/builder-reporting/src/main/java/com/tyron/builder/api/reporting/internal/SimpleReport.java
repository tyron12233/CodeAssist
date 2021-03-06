/*
 * Copyright 2009 the original author or authors.
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

package com.tyron.builder.api.reporting.internal;

import groovy.lang.Closure;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.api.file.FileSystemLocationProperty;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.reporting.ConfigurableReport;
import com.tyron.builder.api.reporting.Report;
import com.tyron.builder.internal.deprecation.DeprecationLogger;
import com.tyron.builder.util.internal.ConfigureUtil;

import java.io.File;

public abstract class SimpleReport implements ConfigurableReport {
    private final String name;
    private final Describable displayName;
    private final OutputType outputType;

    public SimpleReport(String name, Describable displayName, OutputType outputType) {
        this.name = name;
        this.displayName = displayName;
        this.outputType = outputType;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return displayName.getDisplayName();
    }

    public String toString() {
        return "Report " + getName();
    }

    @Override
    public abstract FileSystemLocationProperty<? extends FileSystemLocation> getOutputLocation();

    @Override
    @Deprecated
    public File getDestination() {
        DeprecationLogger.deprecateProperty(Report.class, "destination")
            .replaceWith("outputLocation")
            .willBeRemovedInGradle8()
            .withDslReference()
            .nagUser();

        return getOutputLocation().getAsFile().getOrNull();
    }

    @Deprecated
    @Override
    public void setDestination(File file) {
        getOutputLocation().fileValue(file);
    }

    @Override
    public void setDestination(Provider<File> provider) {
        getOutputLocation().fileProvider(provider);
    }

    @Override
    public OutputType getOutputType() {
        return outputType;
    }

    @Override
    public Report configure(Closure configure) {
        return ConfigureUtil.configureSelf(configure, this);
    }

    @Override
    @Deprecated
    public boolean isEnabled() {
        DeprecationLogger.deprecateProperty(Report.class, "enabled")
            .replaceWith("required")
            .willBeRemovedInGradle8()
            .withDslReference()
            .nagUser();

        return getRequired().get();
    }

    @Override
    @Deprecated
    public void setEnabled(boolean enabled) {
        DeprecationLogger.deprecateProperty(Report.class, "enabled")
            .replaceWith("required")
            .willBeRemovedInGradle8()
            .withDslReference()
            .nagUser();

        getRequired().set(enabled);
    }

    @Override
    @Deprecated
    public void setEnabled(Provider<Boolean> enabled) {
        getRequired().set(enabled);
    }
}
