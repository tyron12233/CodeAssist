package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.internal.SystemProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

public class LineFilter extends Reader {
    private static enum State {
        NORMAL,
        SKIP_LINE,
        EOF
    };

    private final Transformer<String, String> transformer;
    private String transformedLine;
    private int transformedIndex;
    private final BufferedReader bufferedIn;
    private final Reader in;
    private State state = State.NORMAL;

    /**
     * Creates a new filtered reader.
     *
     * @param transformer a transformer to filter each line
     * @throws NullPointerException if <code>in</code> is <code>null</code>
     */
    public LineFilter(Reader in, Transformer<String, String> transformer) {
        this.in = in;
        this.bufferedIn = new BufferedReader(in);
        this.transformer = transformer;
    }

    private void readTransformedLine() throws IOException {
        StringBuilder line = new StringBuilder();
        boolean eol = false;
        int ch;
        while (!eol && (ch = bufferedIn.read()) >= 0) {
            if (ch == '\n') {
                eol = true;
            } else if (ch == '\r') {
                eol = true;
                bufferedIn.mark(1);
                if (bufferedIn.read() != '\n') {
                    bufferedIn.reset();
                }
            } else {
                line.append((char) ch);
            }
        }
        if (line.length() == 0 && !eol) {
            state = State.EOF;
            return;
        }
        String result = transformer.transform(line.toString());
        if (result == null) {
            state = State.SKIP_LINE;
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(result);
        if (eol) {
            builder.append(SystemProperties.getInstance().getLineSeparator());
        }
        state = State.NORMAL;
        transformedLine = builder.toString();
    }

    private void ensureData() throws IOException {
        while (state == State.SKIP_LINE || state == State.NORMAL && (transformedLine == null || transformedIndex >= transformedLine.length())) {
            readTransformedLine();
            transformedIndex = 0;
        }
    }

    @Override
    public int read() throws IOException {
        ensureData();
        if (state == State.EOF) {
            return -1;
        }
        return transformedLine.charAt(transformedIndex++);
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            final int c = read();
            if (c == -1) {
                if (i == 0) {
                    return -1;
                } else {
                    return i;
                }
            }
            cbuf[off + i] = (char) c;
        }
        return len;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
