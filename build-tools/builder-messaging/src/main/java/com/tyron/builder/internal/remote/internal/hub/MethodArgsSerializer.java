package com.tyron.builder.internal.remote.internal.hub;

import com.tyron.builder.internal.serialize.Serializer;

public interface MethodArgsSerializer {
    Serializer<Object[]> forTypes(Class<?>[] types);
}
