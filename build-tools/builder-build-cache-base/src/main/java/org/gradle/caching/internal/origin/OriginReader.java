package org.gradle.caching.internal.origin;

import java.io.IOException;
import java.io.InputStream;

public interface OriginReader {
    OriginMetadata execute(InputStream inputStream) throws IOException;
}