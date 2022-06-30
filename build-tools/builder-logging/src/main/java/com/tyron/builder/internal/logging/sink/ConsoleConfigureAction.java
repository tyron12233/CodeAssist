package com.tyron.builder.internal.logging.sink;

import com.tyron.builder.api.logging.configuration.ConsoleOutput;
import com.tyron.builder.internal.logging.console.AnsiConsole;
import com.tyron.builder.internal.logging.console.ColorMap;
import com.tyron.builder.internal.logging.console.Console;
import com.tyron.builder.internal.nativeintegration.console.ConsoleMetaData;
import com.tyron.builder.internal.nativeintegration.console.FallbackConsoleMetaData;

import java.io.OutputStream;
import java.io.OutputStreamWriter;

public class ConsoleConfigureAction {
    public static void execute(OutputEventRenderer renderer, ConsoleOutput consoleOutput) {
        execute(renderer, consoleOutput, getConsoleMetaData(), renderer.getOriginalStdOut(), renderer.getOriginalStdErr());
    }

    public static void execute(OutputEventRenderer renderer, ConsoleOutput consoleOutput, ConsoleMetaData consoleMetadata, OutputStream stdout, OutputStream stderr) {
        if (consoleOutput == ConsoleOutput.Auto) {
            configureAutoConsole(renderer, consoleMetadata, stdout, stderr);
        } else if (consoleOutput == ConsoleOutput.Rich) {
            configureRichConsole(renderer, consoleMetadata, stdout, stderr, false);
        } else if (consoleOutput == ConsoleOutput.Verbose) {
            configureRichConsole(renderer, consoleMetadata, stdout, stderr, true);
        } else if (consoleOutput == ConsoleOutput.Plain) {
            configurePlainConsole(renderer, consoleMetadata, stdout, stderr);
        }
    }

    private static ConsoleMetaData getConsoleMetaData() {
//        ConsoleDetector consoleDetector = NativeServices.getInstance().get(ConsoleDetector.class);
//        ConsoleMetaData metaData = consoleDetector.getConsole();
//        if (metaData != null) {
//            return metaData;
//        }
        return new ConsoleMetaData() {
            @Override
            public boolean isStdOut() {
                return true;
            }

            @Override
            public boolean isStdErr() {
                return true;
            }

            @Override
            public int getCols() {
                return 65;
            }

            @Override
            public int getRows() {
                return 56;
            }

            @Override
            public boolean isWrapStreams() {
                return true;
            }
        };
        //return FallbackConsoleMetaData.ATTACHED;
    }

    private static void configureAutoConsole(OutputEventRenderer renderer, ConsoleMetaData consoleMetaData, OutputStream stdout, OutputStream stderr) {
        if (consoleMetaData.isStdOut() && consoleMetaData.isStdErr()) {
            // Redirect stderr to stdout when both stdout and stderr are attached to a console. Assume that they are attached to the same console
            // This avoids interleaving problems when stdout and stderr end up at the same location
            Console console = consoleFor(stdout, consoleMetaData, renderer.getColourMap());
            renderer.addRichConsoleWithErrorOutputOnStdout(console, consoleMetaData, false);
        } else if (consoleMetaData.isStdOut()) {
            // Write rich content to stdout and plain content to stderr
            Console console = consoleFor(stdout, consoleMetaData, renderer.getColourMap());
            renderer.addRichConsole(console, stderr, consoleMetaData, false);
        } else if (consoleMetaData.isStdErr()) {
            // Write plain content to stdout and rich content to stderr
            Console stderrConsole = consoleFor(stderr, consoleMetaData, renderer.getColourMap());
            renderer.addRichConsole(stdout, stderrConsole, true);
        } else {
            renderer.addPlainConsole(stdout, stderr);
        }
    }

    private static void configurePlainConsole(OutputEventRenderer renderer, ConsoleMetaData consoleMetaData, OutputStream stdout, OutputStream stderr) {
        if (consoleMetaData.isStdOut() && consoleMetaData.isStdErr()) {
            // Redirect stderr to stdout when both stdout and stderr are attached to a console. Assume that they are attached to the same console
            // This avoids interleaving problems when stdout and stderr end up at the same location
            renderer.addPlainConsoleWithErrorOutputOnStdout(stdout);
        } else {
            renderer.addPlainConsole(stdout, stderr);
        }
    }

    private static void configureRichConsole(OutputEventRenderer renderer, ConsoleMetaData consoleMetaData, OutputStream stdout, OutputStream stderr, boolean verbose) {
        if (consoleMetaData.isStdOut() && consoleMetaData.isStdErr()) {
            // Redirect stderr to stdout when both stdout and stderr are attached to a console. Assume that they are attached to the same console
            // This avoids interleaving problems when stdout and stderr end up at the same location
            Console console = consoleFor(stdout, consoleMetaData, renderer.getColourMap());
            renderer.addRichConsoleWithErrorOutputOnStdout(console, consoleMetaData, verbose);
        } else {
            // Write rich content to both stdout and stderr
            Console stdoutConsole = consoleFor(stdout, consoleMetaData, renderer.getColourMap());
            Console stderrConsole = consoleFor(stderr, consoleMetaData, renderer.getColourMap());
            renderer.addRichConsole(stdoutConsole, stderrConsole, consoleMetaData, verbose);
        }
    }

    private static Console consoleFor(OutputStream stdout, ConsoleMetaData consoleMetaData, ColorMap colourMap) {
        boolean force = !consoleMetaData.isWrapStreams();
        OutputStreamWriter outStr = new OutputStreamWriter(force ? stdout : AnsiConsoleUtil.wrapOutputStream(stdout));
        return new AnsiConsole(outStr, outStr, colourMap, consoleMetaData, force);
    }
}
