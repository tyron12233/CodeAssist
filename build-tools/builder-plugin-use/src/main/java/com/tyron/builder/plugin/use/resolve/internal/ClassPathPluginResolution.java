/*
 * Copyright 2013 the original author or authors.
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

package com.tyron.builder.plugin.use.resolve.internal;

import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.plugins.DefaultPluginRegistry;
import com.tyron.builder.api.internal.plugins.PluginImplementation;
import com.tyron.builder.api.internal.plugins.PluginInspector;
import com.tyron.builder.api.internal.plugins.PluginRegistry;
import com.tyron.builder.api.plugins.UnknownPluginException;
import com.tyron.builder.plugin.use.PluginId;

public class ClassPathPluginResolution implements PluginResolution {

    private final PluginId pluginId;
    private final ClassLoaderScope parent;
    private final PluginInspector pluginInspector;

    public ClassPathPluginResolution(PluginId pluginId, ClassLoaderScope parent, PluginInspector pluginInspector) {
        this.pluginId = pluginId;
        this.parent = parent;
        this.pluginInspector = pluginInspector;
    }

    @Override
    public PluginId getPluginId() {
        return pluginId;
    }

    @Override
    public void execute(PluginResolveContext pluginResolveContext) {
        PluginRegistry pluginRegistry = new DefaultPluginRegistry(pluginInspector, parent);
        PluginImplementation<?> plugin = pluginRegistry.lookup(pluginId);
        if (plugin == null) {
            throw new UnknownPluginException("Plugin with id '" + pluginId + "' not found.");
        }
        pluginResolveContext.add(plugin);
    }
}
