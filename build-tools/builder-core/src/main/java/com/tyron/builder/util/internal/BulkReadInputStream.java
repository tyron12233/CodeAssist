package com.tyron.builder.util.internal;

import java.io.IOException;
import java.io.InputStream;

public abstract class BulkReadInputStream extends InputStream {
    @Override
    public int read() throws IOException {
        byte[] buffer = new byte[1];
        while (true) {
            int nread = read(buffer);
            if (nread < 0) {
                return -1;
            }
            if (nread == 1) {
                return 0xff & buffer[0];
            }
        }
    }

    @Override
    public abstract int read(byte[] bytes, int pos, int count) throws IOException;
}
