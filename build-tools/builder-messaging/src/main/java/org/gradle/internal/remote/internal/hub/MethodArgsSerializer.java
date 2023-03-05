package org.gradle.internal.remote.internal.hub;

import org.gradle.internal.serialize.Serializer;

public interface MethodArgsSerializer {
    Serializer<Object[]> forTypes(Class<?>[] types);
}
