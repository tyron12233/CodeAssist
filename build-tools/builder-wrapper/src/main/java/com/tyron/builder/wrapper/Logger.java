package com.tyron.builder.wrapper;

public class Logger implements Appendable {

    private final boolean quiet;

    public Logger(boolean quiet) {
        this.quiet = quiet;
    }
    
    public void log(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }

    public Appendable append(CharSequence csq) {
        if (!quiet) {
            System.out.append(csq);
        }
        return this;
    }

    public Appendable append(CharSequence csq, int start, int end) {
        if (!quiet) {
            System.out.append(csq, start, end);
        }
        return this;
    }

    public Appendable append(char c) {
        if(!quiet) {
            System.out.append(c);
        }
        return this;
    }
}
