package com.tyron.builder.internal.process;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.UncheckedIOException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class ArgWriter implements ArgCollector {

    private static final Pattern WHITESPACE = Pattern.compile("\\s");
    private static final Pattern WHITESPACE_OR_HASH = Pattern.compile("\\s|#");

    private final PrintWriter writer;
    private final boolean backslashEscape;
    private final Pattern quotablePattern;

    private ArgWriter(PrintWriter writer, boolean backslashEscape, Pattern quotablePattern) {
        this.writer = writer;
        this.backslashEscape = backslashEscape;
        this.quotablePattern = quotablePattern;
    }

    /**
     * Double quotes around args containing whitespace, backslash chars are escaped using double backslash, platform line separators.
     */
    public static ArgWriter unixStyle(PrintWriter writer) {
        return new ArgWriter(writer, true, WHITESPACE);
    }

    public static Transformer<ArgWriter, PrintWriter> unixStyleFactory() {
        return new Transformer<ArgWriter, PrintWriter>() {
            @Override
            public ArgWriter transform(PrintWriter original) {
                return unixStyle(original);
            }
        };
    }

    /**
     * Double quotes around args containing whitespace or #, backslash chars are escaped using double backslash, platform line separators.
     *
     * See <a href='https://docs.oracle.com/javase/9/tools/java.htm#JSWOR-GUID-4856361B-8BFD-4964-AE84-121F5F6CF111'>java Command-Line Argument Files</a>.
     */
    public static ArgWriter javaStyle(PrintWriter writer) {
        return new ArgWriter(writer, true, WHITESPACE_OR_HASH);
    }

    public static Transformer<ArgWriter, PrintWriter> javaStyleFactory() {
        return new Transformer<ArgWriter, PrintWriter>() {
            @Override
            public ArgWriter transform(PrintWriter original) {
                return javaStyle(original);
            }
        };
    }

    /**
     * Double quotes around args containing whitespace, platform line separators.
     */
    public static ArgWriter windowsStyle(PrintWriter writer) {
        return new ArgWriter(writer, false, WHITESPACE);
    }

    public static Transformer<ArgWriter, PrintWriter> windowsStyleFactory() {
        return new Transformer<ArgWriter, PrintWriter>() {
            @Override
            public ArgWriter transform(PrintWriter original) {
                return windowsStyle(original);
            }
        };
    }

    /**
     * Returns an args transformer that replaces the provided args with a generated args file containing the args. Uses platform text encoding.
     */
    public static Transformer<List<String>, List<String>> argsFileGenerator(final File argsFile, final Transformer<ArgWriter, PrintWriter> argWriterFactory) {
        return new Transformer<List<String>, List<String>>() {
            @Override
            public List<String> transform(List<String> args) {
                if (args.isEmpty()) {
                    return args;
                }
                argsFile.getParentFile().mkdirs();
                try {
                    PrintWriter writer = new PrintWriter(argsFile);
                    try {
                        ArgWriter argWriter = argWriterFactory.transform(writer);
                        argWriter.args(args);
                    } finally {
                        writer.close();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(String.format("Could not write options file '%s'.", argsFile.getAbsolutePath()), e);
                }
                return Collections.singletonList("@" + argsFile.getAbsolutePath());
            }
        };
    }

    /**
     * Writes a set of args on a single line, escaping and quoting as required.
     */
    @Override
    public ArgWriter args(Object... args) {
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (i > 0) {
                writer.print(' ');
            }
            String str = arg.toString();
            if (backslashEscape) {
                str = str.replace("\\", "\\\\").replace("\"", "\\\"");
            }
            if (str.isEmpty()) {
                writer.print("\"\"");
            } else if (needsQuoting(str)) {
                writer.print('\"');
                writer.print(str);
                writer.print('\"');
            } else {
                writer.print(str);
            }
        }
        writer.println();
        return this;
    }

    private boolean needsQuoting(String str) {
        return quotablePattern.matcher(str).find();
    }

    @Override
    public ArgCollector args(Iterable<?> args) {
        for (Object arg : args) {
            args(arg);
        }
        return this;
    }
}
