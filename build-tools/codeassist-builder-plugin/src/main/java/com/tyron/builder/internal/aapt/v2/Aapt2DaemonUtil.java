package com.tyron.builder.internal.aapt.v2;

import com.android.annotations.NonNull;
import com.google.common.collect.ImmutableList;
import com.android.ide.common.resources.CompileResourceRequest;
import com.tyron.builder.internal.aapt.AaptConvertConfig;
import com.tyron.builder.internal.aapt.AaptException;
import com.tyron.builder.internal.aapt.AaptPackageConfig;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;

public class Aapt2DaemonUtil {

    public static final String DAEMON_MODE_COMMAND = "m";

    public static void requestCompile(
            @NonNull Writer writer, @NonNull CompileResourceRequest command) throws IOException {
        request(writer, "c", AaptV2CommandBuilder.makeCompileCommand(command));
    }

    public static void requestLink(@NonNull Writer writer, @NonNull AaptPackageConfig command)
            throws IOException {
        ImmutableList<String> args;
        try {
            args = AaptV2CommandBuilder.makeLinkCommand(command);
        } catch (AaptException e) {
            throw new IOException("Unable to make AAPT link command.", e);
        }
        request(writer, "l", args);
    }

    public static void requestConvert(@NonNull Writer writer, @NonNull AaptConvertConfig command)
            throws IOException {
        request(writer, "convert", AaptV2CommandBuilder.makeConvertCommand(command));
    }

    public static void requestShutdown(@NonNull Writer writer) throws IOException {
        request(writer, "quit", Collections.emptyList());
    }

    private static void request(Writer writer, String command, Iterable<String> args)
            throws IOException {
        writer.write(command);
        writer.write('\n');
        for (String s : args) {
            writer.write(s);
            writer.write('\n');
        }
        // Finish the request
        writer.write('\n');
        writer.write('\n');
        writer.flush();
    }
}