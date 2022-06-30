package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.classloader.ClassLoaderSpec;
import com.tyron.builder.internal.classloader.FilteringClassLoader;
import com.tyron.builder.internal.classloader.VisitableURLClassLoader;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

public class HierarchicalClassLoaderStructureSerializer implements Serializer<HierarchicalClassLoaderStructure> {
    private static final byte ROOT = (byte) 0;
    private static final byte HAS_PARENT = (byte) 1;

    private static final byte FILTERING_SPEC = (byte) 0;
    private static final byte VISITABLE_URL_CLASSLOADER_SPEC = (byte) 1;

    private final FilteringClassLoaderSpecSerializer filteringClassLoaderSpecSerializer = new FilteringClassLoaderSpecSerializer();
    private final VisitableURLClassLoaderSpecSerializer visitableURLClassLoaderSpecSerializer = new VisitableURLClassLoaderSpecSerializer();

    @Override
    public void write(Encoder encoder, HierarchicalClassLoaderStructure classLoaderStructure) throws Exception {
        if (classLoaderStructure.getParent() == null) {
            encoder.writeByte(ROOT);
        } else {
            encoder.writeByte(HAS_PARENT);
            write(encoder, classLoaderStructure.getParent());
        }

        if (classLoaderStructure.getSpec() instanceof FilteringClassLoader.Spec) {
            encoder.writeByte(FILTERING_SPEC);
            filteringClassLoaderSpecSerializer.write(encoder, (FilteringClassLoader.Spec) classLoaderStructure.getSpec());
        } else if (classLoaderStructure.getSpec() instanceof VisitableURLClassLoader.Spec) {
            encoder.writeByte(VISITABLE_URL_CLASSLOADER_SPEC);
            visitableURLClassLoaderSpecSerializer.write(encoder, (VisitableURLClassLoader.Spec) classLoaderStructure.getSpec());
        }
    }

    @Override
    public HierarchicalClassLoaderStructure read(Decoder decoder) throws Exception {
        byte parentTag = decoder.readByte();
        HierarchicalClassLoaderStructure parent;
        switch (parentTag) {
            case ROOT:
                parent = null;
                break;
            case HAS_PARENT:
                parent = read(decoder);
                break;
            default:
                throw new IllegalArgumentException("Unexpected payload type.");
        }

        byte specTag = decoder.readByte();
        ClassLoaderSpec spec;
        switch (specTag) {
            case FILTERING_SPEC:
                spec = filteringClassLoaderSpecSerializer.read(decoder);
                break;
            case VISITABLE_URL_CLASSLOADER_SPEC:
                spec = visitableURLClassLoaderSpecSerializer.read(decoder);
                break;
            default:
                throw new IllegalArgumentException("Unexpected payload type.");
        }

        return new HierarchicalClassLoaderStructure(spec, parent);
    }
}
