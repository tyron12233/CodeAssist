package com.tyron.builder.internal.logging;

import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.api.logging.LoggingOutput;
import com.tyron.builder.api.logging.configuration.ConsoleOutput;
import com.tyron.builder.internal.nativeintegration.console.ConsoleMetaData;

import javax.annotation.Nullable;
import java.io.OutputStream;

/**
 * Allows various logging consumers to be attached to the output of the logging system.
 */
public interface LoggingOutputInternal extends LoggingOutput {
    /**
     * Adds System.out and System.err as logging destinations. The output will include plain text only, with no color or dynamic text.
     */
    void attachSystemOutAndErr();

    /**
     * Adds the current processes' stdout and stderr as logging destinations. The output will also include color and dynamic text when one of these
     * is connected to a console.
     *
     * <p>Removes standard output and/or error as a side-effect.
     */
    void attachProcessConsole(ConsoleOutput consoleOutput);

    /**
     * Adds the given {@link java.io.OutputStream} as a logging destination. The stream receives stdout and stderr logging formatted according to the current logging settings and encoded using the system character encoding. The output also includes color and dynamic text encoded using ANSI control sequences, depending on the requested output format.
     *
     * Assumes that a console is attached to stderr.
     *
     * <p>Removes System.out and System.err as logging destinations, if present, as a side-effect.
     *
     * @param outputStream Receives formatted output.
     * @param errorStream Receives formatted error output. Note that this steam may not necessarily be used, depending on the console mode requested.
     * @param consoleOutput The output format.
     */
    void attachConsole(OutputStream outputStream, OutputStream errorStream, ConsoleOutput consoleOutput);

    /**
     * Adds the given {@link java.io.OutputStream} as a logging destination. The stream receives stdout and stderr logging formatted according to the current logging settings and encoded using the system character encoding. The output also includes color and dynamic text encoded using ANSI control sequences, depending on the requested output format.
     *
     * <p>Removes System.out and System.err as logging destinations, if present, as a side-effect.
     *
     * @param outputStream Receives formatted output.
     * @param errorStream Receives formatted error output. Note that this steam may not necessarily be used, depending on the console mode requested.
     * @param consoleMetadata The metadata associated with this console
     * @param consoleOutput The output format.
     */
    void attachConsole(OutputStream outputStream, OutputStream errorStream, ConsoleOutput consoleOutput, @Nullable ConsoleMetaData consoleMetadata);

    /**
     * Adds the given {@link java.io.OutputStream} as a logging destination. The stream receives stdout logging formatted according to the current logging settings and
     * encoded using the system character encoding.
     */
    void addStandardOutputListener(OutputStream outputStream);

    /**
     * Adds the given {@link java.io.OutputStream} as a logging destination. The stream receives stderr logging formatted according to the current logging settings and
     * encoded using the system character encoding.
     */
    void addStandardErrorListener(OutputStream outputStream);

    /**
     * Adds the given listener as a logging destination.
     */
//    @UsedByScanPlugin
    void addOutputEventListener(OutputEventListener listener);

    /**
     * Adds the given listener.
     */
//    @UsedByScanPlugin
    void removeOutputEventListener(OutputEventListener listener);

    /**
     * Flush any outstanding output.
     */
    void flush();
}
