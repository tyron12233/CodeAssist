package org.gradle.internal.logging.text;

import org.gradle.api.logging.StandardOutputListener;

import java.io.*;

public class StreamBackedStandardOutputListener implements StandardOutputListener {
    private final Appendable appendable;
    private final Flushable flushable;

    public StreamBackedStandardOutputListener(Appendable appendable) {
        this.appendable = appendable;
        if (appendable instanceof Flushable) {
            flushable = (Flushable) appendable;
        } else {
            flushable = new Flushable() {
                @Override
                public void flush() throws IOException {
                }
            };
        }
    }

    public StreamBackedStandardOutputListener(OutputStream outputStream) {
        this(new OutputStreamWriter(outputStream));
    }

    @Override
    public void onOutput(CharSequence output) {
        try {
            appendable.append(output);
            flushable.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
