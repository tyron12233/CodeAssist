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

package com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.parser;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.internal.resource.ExternalResource;

@Contextual
public class MetaDataParseException extends BuildException {
    public MetaDataParseException(String message) {
        super(message);
    }

    public MetaDataParseException(String typeName, ExternalResource resource, Throwable cause) {
        super(String.format("Could not parse %s %s", typeName, resource.getDisplayName()), cause);
    }

    public MetaDataParseException(String typeName, ExternalResource resource, String details, Throwable cause) {
        super(String.format("Could not parse %s %s: %s", typeName, resource.getDisplayName(), details), cause);
    }
}
