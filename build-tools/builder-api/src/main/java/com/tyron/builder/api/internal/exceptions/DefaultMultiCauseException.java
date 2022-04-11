package com.tyron.builder.api.internal.exceptions;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.internal.Factory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DefaultMultiCauseException extends BuildException implements MultiCauseException {
    private final List<Throwable> causes = new CopyOnWriteArrayList<Throwable>();
    private transient ThreadLocal<Boolean> hideCause = threadLocal();
    private transient Factory<String> messageFactory;
    private String message;

    public DefaultMultiCauseException(String message) {
        super(message);
        this.message = message;
    }

    public DefaultMultiCauseException(String message, Throwable... causes) {
        super(message);
        this.message = message;
        this.causes.addAll(Arrays.asList(causes));
    }

    public DefaultMultiCauseException(String message, Iterable<? extends Throwable> causes) {
        super(message);
        this.message = message;
        initCauses(causes);
    }

    public DefaultMultiCauseException(Factory<String> messageFactory) {
        this.messageFactory = messageFactory;
    }

    public DefaultMultiCauseException(Factory<String> messageFactory, Throwable... causes) {
        this(messageFactory);
        this.causes.addAll(Arrays.asList(causes));
    }

    public DefaultMultiCauseException(Factory<String> messageFactory, Iterable<? extends Throwable> causes) {
        this(messageFactory);
        initCauses(causes);
    }

    private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
        inputStream.defaultReadObject();
        hideCause = threadLocal();
    }

    private void writeObject(java.io.ObjectOutputStream out) throws IOException {
        getMessage();
        out.defaultWriteObject();
    }

    private ThreadLocal<Boolean> threadLocal() {
        return new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() {
                return false;
            }
        };
    }

    @Override
    public List<? extends Throwable> getCauses() {
        return causes;
    }

    @Override
    public Throwable initCause(Throwable throwable) {
        causes.clear();
        causes.add(throwable);
        return null;
    }

    public void initCauses(Iterable<? extends Throwable> causes) {
        this.causes.clear();
        for (Throwable cause : causes) {
            this.causes.add(cause);
        }
    }

    @Override
    public Throwable getCause() {
        if (hideCause.get()) {
            return null;
        }
        return causes.isEmpty() ? null : causes.get(0);
    }

    @Override
    public void printStackTrace(PrintStream printStream) {
        PrintWriter writer = new PrintWriter(printStream);
        printStackTrace(writer);
        writer.flush();
    }

    @Override
    public void printStackTrace(PrintWriter printWriter) {
        if (causes.isEmpty()) {
            super.printStackTrace(printWriter);
            return;
        }

        hideCause.set(true);
        try {
            super.printStackTrace(printWriter);

            if (causes.size() == 1) {
                printSingleCauseStackTrace(printWriter);
            } else {
                printMultiCauseStackTrace(printWriter);
            }
        } finally {
            hideCause.set(false);
        }
    }

    private void printSingleCauseStackTrace(PrintWriter printWriter) {
        Throwable cause = causes.get(0);
        printWriter.print("Caused by: ");
        cause.printStackTrace(printWriter);
    }

    private void printMultiCauseStackTrace(PrintWriter printWriter) {
        for (int i = 0; i < causes.size(); i++) {
            Throwable cause = causes.get(i);
            printWriter.format("Cause %s: ", i + 1);
            cause.printStackTrace(printWriter);
        }
    }

    @Override
    public String getMessage() {
        if (messageFactory != null) {
            message = messageFactory.create();
            messageFactory = null;
            return message;
        }
        return message;
    }
}