/*
 * Copyright 2016 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result;

import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.internal.artifacts.DefaultBuildIdentifier;
import com.tyron.builder.api.internal.artifacts.ForeignBuildIdentifier;
import com.tyron.builder.internal.serialize.AbstractSerializer;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;

import java.io.IOException;

public class BuildIdentifierSerializer extends AbstractSerializer<BuildIdentifier> {
    private static final byte ROOT = 0;
    private static final byte LOCAL = 1;
    private static final byte FOREIGN = 2;

    @Override
    public BuildIdentifier read(Decoder decoder) throws IOException {
        byte type = decoder.readByte();
        switch (type) {
            case ROOT:
                return DefaultBuildIdentifier.ROOT;
            case LOCAL:
                return new DefaultBuildIdentifier(decoder.readString());
            case FOREIGN:
                return new ForeignBuildIdentifier(decoder.readString(), decoder.readString());
            default:
                throw new IllegalArgumentException("Unexpected build identifier type.");
        }
    }

    @Override
    public void write(Encoder encoder, BuildIdentifier value) throws IOException {
        if (value == DefaultBuildIdentifier.ROOT) {
            encoder.writeByte(ROOT);
        } else if (value instanceof ForeignBuildIdentifier) {
            ForeignBuildIdentifier foreignBuildIdentifier = (ForeignBuildIdentifier) value;
            encoder.writeByte(FOREIGN);
            encoder.writeString(foreignBuildIdentifier.getIdName());
            encoder.writeString(value.getName());
        } else {
            encoder.writeByte(LOCAL);
            encoder.writeString(value.getName());
        }
    }
}
