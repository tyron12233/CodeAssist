package com.tyron.builder.dexing;

import com.android.tools.r8.AssertionsConfiguration;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.StringConsumer.FileConsumer;
import com.google.common.util.concurrent.MoreExecutors;
import com.android.ide.common.blame.Message;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

final class D8DexArchiveBuilder extends DexArchiveBuilder {

    private static final String INVOKE_CUSTOM =
            "Invoke-customs are only supported starting with Android O (--min-api 26)";

    private static final String DEFAULT_INTERFACE_METHOD =
            "Default interface methods are only supported starting with Android N (--min-api 24)";

    private static final String STATIC_INTERFACE_METHOD =
            "Static interface methods are only supported starting with Android N (--min-api 24)";

    @NotNull
    private DexParameters dexParams;

    public D8DexArchiveBuilder(@NotNull DexParameters dexParams) {
        this.dexParams = dexParams;
    }

    @Override
    public void convert(
            @NotNull Stream<ClassFileEntry> input,
            @NotNull Path output,
            @Nullable DependencyGraphUpdater<File> desugarGraphUpdater)
            throws DexArchiveBuilderException {
        InterceptingDiagnosticsHandler diagnosticsHandler = new InterceptingDiagnosticsHandler();
        try {

            D8Command.Builder builder = D8Command.builder(diagnosticsHandler);
            AtomicInteger entryCount = new AtomicInteger();
            input.forEach(
                    entry -> {
                        builder.addClassProgramData(
                                readAllBytes(entry), D8DiagnosticsHandler.getOrigin(entry));
                        entryCount.incrementAndGet();
                    });
            if (entryCount.get() == 0) {
                // nothing to do here, just return
                return;
            }

            builder.setMode(
                            dexParams.getDebuggable()
                                    ? CompilationMode.DEBUG
                                    : CompilationMode.RELEASE)
                    .setMinApiLevel(dexParams.getMinSdkVersion())
                    .setIntermediate(true)
                    .setOutput(
                            output,
                            dexParams.getDexPerClass()
                                    ? OutputMode.DexFilePerClassFile
                                    : OutputMode.DexIndexed)
                    .setIncludeClassesChecksum(dexParams.getDebuggable());

            if (dexParams.getDebuggable()) {
                builder.addAssertionsConfiguration(
                        AssertionsConfiguration.Builder::enableAllAssertions);
            }

            if (dexParams.getWithDesugaring()) {
                builder.addLibraryResourceProvider(
                        dexParams.getDesugarBootclasspath().getOrderedProvider());
                builder.addClasspathResourceProvider(
                        dexParams.getDesugarClasspath().getOrderedProvider());

                if (dexParams.getCoreLibDesugarConfig() != null) {
                    builder.addSpecialLibraryConfiguration(dexParams.getCoreLibDesugarConfig());
                    if (dexParams.getCoreLibDesugarOutputKeepRuleFile() != null) {
                        builder.setDesugaredLibraryKeepRuleConsumer(
                                new FileConsumer(
                                        dexParams.getCoreLibDesugarOutputKeepRuleFile().toPath()));
                    }
                }
                if (desugarGraphUpdater != null) {
                    builder.setDesugarGraphConsumer(
                            new D8DesugarGraphConsumerAdapter(desugarGraphUpdater));
                }
            } else {
                builder.setDisableDesugaring(true);
            }

            D8.run(builder.build(), MoreExecutors.newDirectExecutorService());
        } catch (Throwable e) {
            throw getExceptionToRethrow(e, diagnosticsHandler, dexParams.getWithDesugaring());
        }
    }

    @NotNull
    private static byte[] readAllBytes(@NotNull ClassFileEntry entry) {
        try {
            return entry.readAllBytes();
        } catch (IOException ex) {
            throw new DexArchiveBuilderException(ex);
        }
    }

    @NotNull
    private static DexArchiveBuilderException getExceptionToRethrow(
            @NotNull Throwable t,
            InterceptingDiagnosticsHandler diagnosticsHandler,
            boolean isDesugaring) {
        StringBuilder msg = new StringBuilder();
        msg.append("Error while dexing.");
        Set<String> unsupportedFeatures = diagnosticsHandler.getUnsupportedFeatures();
        if (!unsupportedFeatures.isEmpty()) {
            // Get the largest required level needed to support the features.
            int minSdkVersion = diagnosticsHandler.getRequiredSdkVersion();
            if (!isDesugaring) {
                diagnosticsHandler.addHint(getEnableDesugaringHint(minSdkVersion));
            } else if (minSdkVersion != -1) {
                diagnosticsHandler.addHint(
                        "Increase the minSdkVersion to " + minSdkVersion + " or above.\n");
            }
            // Construct a new exception to replace the D8 thrown exception.
            // This avoids the need to maintain pattern-match on D8 exceptions and
            // instead base matching on the stable diagnostics API.
            StringBuilder builder = new StringBuilder();
            if (unsupportedFeatures.contains("invoke-custom")) {
                builder.append("Error: ").append(INVOKE_CUSTOM);
            } else if (unsupportedFeatures.contains("default-interface-method")) {
                builder.append("Error: ").append(DEFAULT_INTERFACE_METHOD);
            } else if (unsupportedFeatures.contains("static-interface-method")) {
                builder.append("Error: ").append(STATIC_INTERFACE_METHOD);
            } else {
                // If not one of the three above legacy cases, construct an error message with a
                // line
                // for each unsupported feature. These are not currently pattern-matched on, so
                // generalizing the reporting of these can be changed at a later point.
                List<String> sorted = new ArrayList<>(unsupportedFeatures);
                sorted.sort(String::compareTo);
                for (String featureDescriptor : sorted) {
                    builder.append("Error: UnsupportedFeature(")
                            .append(featureDescriptor)
                            .append(")\n");
                }
            }
            Throwable rt = new RuntimeException(builder.toString());
            rt.addSuppressed(t);
            t = rt;
        }
        for (String hint : diagnosticsHandler.getPendingHints()) {
            msg.append(System.lineSeparator());
            msg.append(hint);
        }
        return new DexArchiveBuilderException(msg.toString(), t);
    }

    private static String getEnableDesugaringHint(int minSdkVersion) {
        return "The dependency contains Java 8 bytecode. Please enable desugaring by "
                + "adding the following to build.gradle\n"
                + "android {\n"
                + "    compileOptions {\n"
                + "        sourceCompatibility 1.8\n"
                + "        targetCompatibility 1.8\n"
                + "    }\n"
                + "}\n"
                + "See https://developer.android.com/studio/write/java8-support.html for "
                + "details. Alternatively, increase the minSdkVersion to "
                + minSdkVersion
                + " or above.\n";
    }

    private class InterceptingDiagnosticsHandler extends D8DiagnosticsHandler {

        private Set<String> unsupportedFeatureDescriptors = new HashSet<>();
        private int requiredSdkVersion = -1;

        public InterceptingDiagnosticsHandler() {
            super(D8DexArchiveBuilder.this.dexParams.getMessageReceiver());
        }

        public int getRequiredSdkVersion() {
            return requiredSdkVersion;
        }

        public Set<String> getUnsupportedFeatures() {
            return unsupportedFeatureDescriptors;
        }

        @Override
        protected Message convertToMessage(Message.Kind kind, Diagnostic diagnostic) {
//            if (diagnostic instanceof UnsupportedFeatureDiagnostic) {
//                UnsupportedFeatureDiagnostic feature = (UnsupportedFeatureDiagnostic) diagnostic;
//                String featureDescriptor = feature.getFeatureDescriptor();
//                int minSdkVersion = feature.getSupportedApiLevel();
//                unsupportedFeatureDescriptors.add(featureDescriptor);
//                if (requiredSdkVersion < minSdkVersion) {
//                    requiredSdkVersion = minSdkVersion;
//                }
//            }
            return super.convertToMessage(kind, diagnostic);
        }
    }
}