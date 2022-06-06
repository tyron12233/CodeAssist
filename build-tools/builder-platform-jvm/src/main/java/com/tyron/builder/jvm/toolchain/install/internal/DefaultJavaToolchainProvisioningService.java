package com.tyron.builder.jvm.toolchain.install.internal;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.cache.FileLock;
import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.CallableBuildOperation;
import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.util.Optional;
import java.util.concurrent.Callable;

public class DefaultJavaToolchainProvisioningService implements JavaToolchainProvisioningService {

    public static final String AUTO_DOWNLOAD = "com.tyron.builder.java.installations.auto-download";

    @Contextual
    private static class MissingToolchainException extends BuildException {

        public MissingToolchainException(JavaToolchainSpec spec, @Nullable Throwable cause) {
            super("Unable to download toolchain matching these requirements: " + spec.getDisplayName(), cause);
        }

    }

    private final AdoptOpenJdkRemoteBinary openJdkBinary;
    private final JdkCacheDirectory cacheDirProvider;
    private final Provider<Boolean> downloadEnabled;
    private final BuildOperationExecutor buildOperationExecutor;
    private static final Object PROVISIONING_PROCESS_LOCK = new Object();

    @Inject
    public DefaultJavaToolchainProvisioningService(AdoptOpenJdkRemoteBinary openJdkBinary, JdkCacheDirectory cacheDirProvider, ProviderFactory factory, BuildOperationExecutor executor) {
        this.openJdkBinary = openJdkBinary;
        this.cacheDirProvider = cacheDirProvider;
        this.downloadEnabled = factory.gradleProperty(AUTO_DOWNLOAD).map(Boolean::parseBoolean);
        this.buildOperationExecutor = executor;
    }

    public Optional<File> tryInstall(JavaToolchainSpec spec) {
        if (!isAutoDownloadEnabled() || !openJdkBinary.canProvideMatchingJdk(spec)) {
            return Optional.empty();
        }
        return provisionInstallation(spec);
    }

    private Optional<File> provisionInstallation(JavaToolchainSpec spec) {
        synchronized (PROVISIONING_PROCESS_LOCK) {
            String destinationFilename = openJdkBinary.toFilename(spec);
            File destinationFile = cacheDirProvider.getDownloadLocation(destinationFilename);
            final FileLock fileLock = cacheDirProvider.acquireWriteLock(destinationFile, "Downloading toolchain");
            try {
                return wrapInOperation(
                    "Provisioning toolchain " + destinationFile.getName(),
                    () -> provisionJdk(spec, destinationFile));
            } catch (Exception e) {
                throw new MissingToolchainException(spec, e);
            } finally {
                fileLock.close();
            }
        }
    }

    private Optional<File> provisionJdk(JavaToolchainSpec spec, File destinationFile) {
        final Optional<File> jdkArchive;
        if (destinationFile.exists()) {
            jdkArchive = Optional.of(destinationFile);
        } else {
            jdkArchive = openJdkBinary.download(spec, destinationFile);
        }
        return wrapInOperation("Unpacking toolchain archive", () -> jdkArchive.map(cacheDirProvider::provisionFromArchive));
    }

    private boolean isAutoDownloadEnabled() {
        return downloadEnabled.getOrElse(true);
    }

    private <T> T wrapInOperation(String displayName, Callable<T> provisioningStep) {
        return buildOperationExecutor.call(new ToolchainProvisioningBuildOperation<>(displayName, provisioningStep));
    }

    private static class ToolchainProvisioningBuildOperation<T> implements CallableBuildOperation<T> {
        private final String displayName;
        private final Callable<T> provisioningStep;

        public ToolchainProvisioningBuildOperation(String displayName, Callable<T> provisioningStep) {
            this.displayName = displayName;
            this.provisioningStep = provisioningStep;
        }

        @Override
        public T call(BuildOperationContext context) throws Exception {
            return provisioningStep.call();
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor
                .displayName(displayName)
                .progressDisplayName(displayName);
        }
    }
}
