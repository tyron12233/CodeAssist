/*
 * Copyright 2020 the original author or authors.
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
package com.tyron.builder.api.plugins.catalog.internal;

import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.api.internal.catalog.DefaultVersionCatalog;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.tasks.CacheableTask;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.OutputFile;
import com.tyron.builder.api.tasks.TaskAction;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

@CacheableTask
public abstract class TomlFileGenerator extends DefaultTask {
    @Input
    public abstract Property<DefaultVersionCatalog> getDependenciesModel();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    void generateToml() throws IOException {
        DefaultVersionCatalog model = getDependenciesModel().get();
        File outputFile = getOutputFile().getAsFile().get();
        File outputDir = outputFile.getParentFile();
        if (outputDir.exists() || outputFile.mkdirs()) {
            doGenerate(model, outputFile);
        } else {
            throw new BuildException("Unable to generate TOML dependencies file into " + outputDir);
        }
    }

    private void doGenerate(DefaultVersionCatalog model, File outputFile) throws FileNotFoundException, UnsupportedEncodingException {
        try (PrintWriter writer = new PrintWriter(outputFile, "UTF-8")) {
            TomlWriter ctx = new TomlWriter(writer);
            ctx.generate(model);
        }
    }


}
