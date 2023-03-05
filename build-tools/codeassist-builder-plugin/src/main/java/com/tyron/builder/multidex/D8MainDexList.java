package com.tyron.builder.multidex;

import static com.android.SdkConstants.DOT_DEX;
import static com.tyron.builder.dexing.D8ErrorMessagesKt.ERROR_DUPLICATE_HELP_PAGE;

import com.android.annotations.NonNull;
import com.tyron.builder.dexing.D8DiagnosticsHandler;
import com.android.ide.common.blame.Message;
import com.android.ide.common.blame.MessageReceiver;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.GenerateMainDexList;
import com.android.tools.r8.GenerateMainDexListCommand;
import com.android.tools.r8.errors.DuplicateTypesDiagnostic;
import com.android.tools.r8.origin.Origin;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** This is a utility class that is using D8 to get the main dex list. */
public final class D8MainDexList {

    public static class MainDexListException extends Exception {
        public MainDexListException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private D8MainDexList() {}

    /**
     * Returns the list of classes that should be kept in the main dex file for legacy multidex.
     *
     * @param mainDexRules Proguard rules written as strings
     * @param mainDexRulesFiles files containing the Proguard rules
     * @param programFiles dex files that will end up in the final binary
     * @param libraryFiles classes that are used only to resolve types in the program classes, but
     *     are not packaged in the final binary e.g. android.jar, provided classes etc.
     * @return a list of classes to be kept in the main dex file
     */
    @NonNull
    public static List<String> generate(
            @NonNull List<String> mainDexRules,
            @NonNull List<Path> mainDexRulesFiles,
            @NonNull Collection<Path> programFiles,
            @NonNull Collection<Path> libraryFiles,
            @NonNull MessageReceiver messageReceiver)
            throws MainDexListException {

        D8DiagnosticsHandler d8DiagnosticsHandler =
                new InterceptingDiagnosticsHandler(messageReceiver);
        try {
            GenerateMainDexListCommand.Builder command =
                    GenerateMainDexListCommand.builder(d8DiagnosticsHandler)
                            .addMainDexRules(mainDexRules, Origin.unknown())
                            .addMainDexRulesFiles(mainDexRulesFiles)
                            .addLibraryFiles(libraryFiles);

            for (Path program : programFiles) {
                if (Files.isRegularFile(program)) {
                    command.addProgramFiles(program);
                } else {
                    Preconditions.checkState(
                            Files.isDirectory(program), "Expected directory: " + program);
                    try (Stream<Path> classFiles = Files.walk(program)) {
                        List<Path> allDexFiles =
                                classFiles
                                        .filter(
                                                file -> {
                                                    Path relative = program.relativize(file);
                                                    return relative.toString().endsWith(DOT_DEX);
                                                })
                                        .collect(Collectors.toList());
                        command.addProgramFiles(allDexFiles);
                    }
                }
            }

            return ImmutableList.copyOf(
                    GenerateMainDexList.run(command.build(), ForkJoinPool.commonPool()));
        } catch (Exception e) {
            throw getExceptionToRethrow(e, d8DiagnosticsHandler);
        }
    }

    @NonNull
    private static MainDexListException getExceptionToRethrow(
            @NonNull Throwable t, D8DiagnosticsHandler d8DiagnosticsHandler) {
        StringBuilder msg = new StringBuilder("Error while merging dex archives: ");
        for (String hint : d8DiagnosticsHandler.getPendingHints()) {
            msg.append(System.lineSeparator());
            msg.append(hint);
        }
        return new MainDexListException(msg.toString(), t);
    }

    private static class InterceptingDiagnosticsHandler extends D8DiagnosticsHandler {
        public InterceptingDiagnosticsHandler(@NonNull MessageReceiver messageReceiver) {
            super(messageReceiver);
        }

        @Override
        protected Message convertToMessage(Message.Kind kind, Diagnostic diagnostic) {
            if (diagnostic instanceof DuplicateTypesDiagnostic) {
                addHint(diagnostic.getDiagnosticMessage());
                addHint(ERROR_DUPLICATE_HELP_PAGE);
            }

            return super.convertToMessage(kind, diagnostic);
        }
    }
}
