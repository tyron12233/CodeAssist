/*
 * Copyright 2012 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

import java.io.IOException;

public class ResolvedConfigurationIdentifierSerializer implements Serializer<ResolvedConfigurationIdentifier> {
    private final ModuleVersionIdentifierSerializer idSerializer;

    public ResolvedConfigurationIdentifierSerializer(ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        idSerializer = new ModuleVersionIdentifierSerializer(moduleIdentifierFactory);
    }

    @Override
    public ResolvedConfigurationIdentifier read(Decoder decoder) throws IOException {
        ModuleVersionIdentifier id = idSerializer.read(decoder);
        String configuration = decoder.readString();
        return new ResolvedConfigurationIdentifier(id, configuration);
    }

    @Override
    public void write(Encoder encoder, ResolvedConfigurationIdentifier value) throws IOException {
        idSerializer.write(encoder, value.getId());
        encoder.writeString(value.getConfiguration());
    }
}
