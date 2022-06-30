package com.tyron.builder.workers.internal;

import com.google.common.collect.Lists;
import com.tyron.builder.initialization.MixInLegacyTypesClassLoader;
import com.tyron.builder.internal.classloader.VisitableURLClassLoader;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

import java.net.URL;
import java.util.List;

public class VisitableURLClassLoaderSpecSerializer implements Serializer<VisitableURLClassLoader.Spec> {
    private static final byte VISITABLE_URL_CLASSLOADER_SPEC = (byte) 0;
    private static final byte MIXIN_CLASSLOADER_SPEC = (byte) 1;

    @Override
    public void write(Encoder encoder, VisitableURLClassLoader.Spec spec) throws Exception {
        if (spec instanceof MixInLegacyTypesClassLoader.Spec) {
            encoder.writeByte(MIXIN_CLASSLOADER_SPEC);
        } else {
            encoder.writeByte(VISITABLE_URL_CLASSLOADER_SPEC);
        }

        encoder.writeString(spec.getName());
        encoder.writeInt(spec.getClasspath().size());
        for (URL url : spec.getClasspath()) {
            encoder.writeString(url.toString());
        }
    }

    @Override
    public VisitableURLClassLoader.Spec read(Decoder decoder) throws Exception {
        byte typeTag = decoder.readByte();
        String name = decoder.readString();
        List<URL> classpath = Lists.newArrayList();
        int classpathSize = decoder.readInt();
        for (int i=0; i<classpathSize; i++) {
            classpath.add(new URL(decoder.readString()));
        }

        switch(typeTag) {
            case VISITABLE_URL_CLASSLOADER_SPEC:
                return new VisitableURLClassLoader.Spec(name, classpath);
            case MIXIN_CLASSLOADER_SPEC:
                return new MixInLegacyTypesClassLoader.Spec(name, classpath);
            default:
                throw new IllegalArgumentException("Unexpected payload type.");
        }
    }
}
