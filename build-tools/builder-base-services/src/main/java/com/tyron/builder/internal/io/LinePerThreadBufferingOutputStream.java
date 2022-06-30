package com.tyron.builder.internal.io;

import com.tyron.builder.internal.SystemProperties;

import org.apache.commons.io.output.NullOutputStream;

import java.io.PrintStream;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;

public class LinePerThreadBufferingOutputStream extends PrintStream {
    private final TextStream handler;
    private final ThreadLocal<PrintStream> stream = new ThreadLocal<PrintStream>();
    private final String lineSeparator = SystemProperties.getInstance().getLineSeparator();
    public LinePerThreadBufferingOutputStream(TextStream handler) {
        super(NullOutputStream.NULL_OUTPUT_STREAM, true);
        this.handler = handler;
    }

    private PrintStream getStream() {
        PrintStream printStream = stream.get();
        if (printStream == null) {
            printStream = new PrintStream(new LineBufferingOutputStream(handler, lineSeparator));
            stream.set(printStream);
        }
        return printStream;
    }

    private PrintStream maybeGetStream() {
        return stream.get();
    }

    @Override
    public PrintStream append(CharSequence csq) {
        getStream().append(csq);
        return this;
    }

    @Override
    public PrintStream append(char c) {
        getStream().append(c);
        return this;
    }

    @Override
    public PrintStream append(CharSequence csq, int start, int end) {
        getStream().append(csq, start, end);
        return this;
    }

    @Override
    public boolean checkError() {
        return getStream().checkError();
    }

    @Override
    public void close() {
        PrintStream currentStream = maybeGetStream();
        if (currentStream != null) {
            stream.remove();
            currentStream.close();
        } else {
            handler.endOfStream(null);
        }
    }

    @Override
    public void flush() {
        PrintStream stream = maybeGetStream();
        if (stream != null) {
            stream.flush();
        }
    }

    @Override
    public PrintStream format(String format, Object... args) {
        getStream().format(format, args);
        return this;
    }

    @Override
    public PrintStream format(Locale l, String format, Object... args) {
        getStream().format(l, format, args);
        return this;
    }

    @Override
    public void print(boolean b) {
        getStream().print(b);
    }

    @Override
    public void print(char c) {
        getStream().print(c);
    }

    @Override
    public void print(double d) {
        getStream().print(d);
    }

    @Override
    public void print(float f) {
        getStream().print(f);
    }

    @Override
    public void print(int i) {
        getStream().print(i);
    }

    @Override
    public void print(long l) {
        getStream().print(l);
    }

    @Override
    public void print(Object obj) {
        getStream().print(obj);
    }

    @Override
    public void print(char[] s) {
        getStream().print(s);
    }

    @Override
    public void print(String s) {
        getStream().print(s);
    }

    @Override
    public PrintStream printf(String format, Object... args) {
        getStream().printf(format, args);
        return this;
    }

    @Override
    public PrintStream printf(Locale l, String format, Object... args) {
        getStream().printf(l, format, args);
        return this;
    }

    @Override
    public void println() {
        getStream().print(lineSeparator);
    }

    @Override
    public void println(boolean x) {
        PrintStream stream = getStream();
        synchronized (this) {
            stream.print(x);
            stream.print(lineSeparator);
        }
    }

    @Override
    public void println(char x) {
        PrintStream stream = getStream();
        synchronized (this) {
            stream.print(x);
            stream.print(lineSeparator);
        }
    }

    @Override
    public void println(char[] x) {
        PrintStream stream = getStream();
        synchronized (this) {
            stream.print(x);
            stream.print(lineSeparator);
        }
    }

    @Override
    public void println(double x) {
        PrintStream stream = getStream();
        synchronized (this) {
            stream.print(x);
            stream.print(lineSeparator);
        }
    }

    @Override
    public void println(float x) {
        PrintStream stream = getStream();
        synchronized (this) {
            stream.print(x);
            stream.print(lineSeparator);
        }
    }

    @Override
    public void println(int x) {
        PrintStream stream = getStream();
        synchronized (this) {
            stream.print(x);
            stream.print(lineSeparator);
        }
    }

    @Override
    public void println(long x) {
        PrintStream stream = getStream();
        synchronized (this) {
            stream.print(x);
            stream.print(lineSeparator);
        }
    }

    @Override
    public void println(Object x) {
        PrintStream stream = getStream();
        synchronized (this) {
            stream.print(x);
            stream.print(lineSeparator);
        }
    }

    @Override
    public void println(String x) {
        PrintStream stream = getStream();
        synchronized (this) {
            stream.print(x);
            stream.print(lineSeparator);
        }
    }

    @Override
    public void write(int b) {
        getStream().write(b);
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        getStream().write(buf, off, len);
    }

    @Override
    public void write(byte[] b) throws IOException {
        getStream().write(b);
    }
}
