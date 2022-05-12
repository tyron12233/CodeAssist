package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.internal.serialize.AbstractSerializer;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;

public class BuildScriptDataSerializer extends AbstractSerializer<BuildScriptData> {
    @Override
    public BuildScriptData read(Decoder decoder) throws Exception {
        return new BuildScriptData(decoder.readBoolean());
    }

    @Override
    public void write(Encoder encoder, BuildScriptData value) throws Exception {
        encoder.writeBoolean(value.getHasImperativeStatements());
    }
}
