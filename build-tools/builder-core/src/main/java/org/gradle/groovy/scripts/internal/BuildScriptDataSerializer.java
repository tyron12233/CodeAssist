package org.gradle.groovy.scripts.internal;

import org.gradle.internal.serialize.AbstractSerializer;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;

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
