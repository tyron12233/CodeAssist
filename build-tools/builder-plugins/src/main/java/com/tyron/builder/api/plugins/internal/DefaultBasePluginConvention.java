/*
 * Copyright 2018 the original author or authors.
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

package com.tyron.builder.api.plugins.internal;

import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.plugins.BasePluginConvention;
import com.tyron.builder.api.plugins.BasePluginExtension;
import com.tyron.builder.api.reflect.HasPublicType;
import com.tyron.builder.api.reflect.TypeOf;

@Deprecated
public class DefaultBasePluginConvention extends BasePluginConvention implements HasPublicType {

    private BasePluginExtension extension;

    public DefaultBasePluginConvention(BasePluginExtension extension) {
        this.extension = extension;
    }
    @Override
    public TypeOf<?> getPublicType() {
        return TypeOf.typeOf(BasePluginConvention.class);
    }

    @Override
    public DirectoryProperty getDistsDirectory() {
        return extension.getDistsDirectory();
    }

    @Override
    public DirectoryProperty getLibsDirectory() {
        return extension.getLibsDirectory();
    }

    @Override
    public String getDistsDirName() {
        return extension.getDistsDirName();
    }

    @Override
    public void setDistsDirName(String distsDirName) {
        extension.setDistsDirName(distsDirName);
    }

    @Override
    public String getLibsDirName() {
        return extension.getLibsDirName();
    }

    @Override
    public void setLibsDirName(String libsDirName) {
        extension.setLibsDirName(libsDirName);
    }

    @Override
    public String getArchivesBaseName() {
        return extension.getArchivesBaseName();
    }

    @Override
    public void setArchivesBaseName(String archivesBaseName) {
        extension.setArchivesBaseName(archivesBaseName);
    }
}
