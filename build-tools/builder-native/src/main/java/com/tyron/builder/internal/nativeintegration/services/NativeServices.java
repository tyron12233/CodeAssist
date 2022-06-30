package com.tyron.builder.internal.nativeintegration.services;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.file.temp.GradleUserHomeTemporaryFileProvider;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.os.OperatingSystem;
import com.tyron.builder.internal.nativeintegration.network.HostnameLookup;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.service.ServiceCreationException;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.initialization.GradleUserHomeDirProvider;
import com.tyron.builder.internal.SystemProperties;

import net.rubygrapefruit.platform.Native;
import net.rubygrapefruit.platform.NativeException;
import net.rubygrapefruit.platform.NativeIntegrationUnavailableException;
import net.rubygrapefruit.platform.ProcessLauncher;
import net.rubygrapefruit.platform.SystemInfo;
import net.rubygrapefruit.platform.WindowsRegistry;
import net.rubygrapefruit.platform.file.FileEvents;
import net.rubygrapefruit.platform.file.FileSystems;
import net.rubygrapefruit.platform.file.PosixFiles;
import net.rubygrapefruit.platform.internal.DefaultProcessLauncher;
import net.rubygrapefruit.platform.memory.Memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumSet;

public class NativeServices extends DefaultServiceRegistry implements ServiceRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeServices.class);
    private static final NativeServices INSTANCE = new NativeServices();

    public static final String NATIVE_DIR_OVERRIDE = "org.gradle.native.dir";

    private boolean initialized;
    private boolean useNativeIntegrations;
    private File userHomeDir;
    private File nativeBaseDir;
    private final EnumSet<NativeFeatures> initializedFeatures = EnumSet.noneOf(NativeFeatures.class);
    private final EnumSet<NativeFeatures> enabledFeatures = EnumSet.noneOf(NativeFeatures.class);

    public enum NativeFeatures {
        FILE_SYSTEM_WATCHING {
            @Override
            public boolean initialize(File nativeBaseDir, boolean useNativeIntegrations) {
                if (useNativeIntegrations) {
                    try {
                        FileEvents.init(nativeBaseDir);
                        LOGGER.info("Initialized file system watching services in: {}", nativeBaseDir);
                        return true;
                    } catch (NativeIntegrationUnavailableException ex) {
                        LOGGER.debug("Native file system watching is not available for this operating system.", ex);
                        return false;
                    }
                }
                return false;
            }
        },
        JANSI {
            @Override
            public boolean initialize(File nativeBaseDir, boolean canUseNativeIntegrations) {
//                JANSI_BOOT_PATH_CONFIGURER.configure(nativeBaseDir);
                LOGGER.info("Initialized jansi services in: {}", nativeBaseDir);
                return true;
            }
        };

//        private static final JansiBootPathConfigurer JANSI_BOOT_PATH_CONFIGURER = new JansiBootPathConfigurer();

        public abstract boolean initialize(File nativeBaseDir, boolean canUseNativeIntegrations);
    }

    /**
     * Initializes the native services to use the given user home directory to store native libs and other resources. Does nothing if already initialized.
     *
     * Initializes all the services needed for the Gradle daemon.
     */
    public static void initializeOnDaemon(File userHomeDir) {
        INSTANCE.initialize(userHomeDir, EnumSet.allOf(NativeFeatures.class));
    }

    /**
     * Initializes the native services to use the given user home directory to store native libs and other resources. Does nothing if already initialized.
     *
     * Initializes all the services needed for the CLI or the Tooling API.
     */
    public static void initializeOnClient(File userHomeDir) {
        INSTANCE.initialize(userHomeDir, EnumSet.of(NativeFeatures.JANSI));
    }

    /**
     * Initializes the native services to use the given user home directory to store native libs and other resources. Does nothing if already initialized.
     *
     * Initializes all the services needed for the CLI or the Tooling API.
     */
    public static void initializeOnWorker(File userHomeDir) {
        INSTANCE.initialize(userHomeDir, EnumSet.noneOf(NativeFeatures.class));
    }

    /**
     * Initializes the native services to use the given user home directory to store native libs and other resources. Does nothing if already initialized.
     *
     * @param requestedFeatures Whether to initialize additional native libraries like jansi and file-events.
     */
    private void initialize(File userHomeDir, EnumSet<NativeFeatures> requestedFeatures) {
        if (!initialized) {
            try {
                initializeNativeIntegrations(userHomeDir);
                initialized = true;
                initializeFeatures(requestedFeatures);
            } catch (RuntimeException e) {
                throw new ServiceCreationException("Could not initialize native services.", e);
            }
        }
    }

    private void initializeNativeIntegrations(File userHomeDir) {
        this.userHomeDir = userHomeDir;
        useNativeIntegrations = isNativeIntegrationsEnabled();
        nativeBaseDir = getNativeServicesDir(userHomeDir).getAbsoluteFile();
        if (useNativeIntegrations) {
            try {
                net.rubygrapefruit.platform.Native.init(nativeBaseDir);
            } catch (NativeIntegrationUnavailableException ex) {
                LOGGER.debug("Native-platform is not available.", ex);
                useNativeIntegrations = false;
            } catch (NativeException ex) {
                if (ex.getCause() instanceof UnsatisfiedLinkError && ex.getCause().getMessage().toLowerCase().contains("already loaded in another classloader")) {
                    LOGGER.debug("Unable to initialize native-platform. Failure: {}", format(ex));
                    useNativeIntegrations = false;
                } else if (ex.getMessage().equals("Could not extract native JNI library.")
                           && ex.getCause().getMessage().contains("native-platform.dll (The process cannot access the file because it is being used by another process)")) {
                    //triggered through tooling API of Gradle <2.3 - native-platform.dll is shared by tooling client (<2.3) and daemon (current) and it is locked by the client (<2.3 issue)
                    LOGGER.debug("Unable to initialize native-platform. Failure: {}", format(ex));
                    useNativeIntegrations = false;
                } else {
                    throw ex;
                }
            }
            LOGGER.info("Initialized native services in: {}", nativeBaseDir);
        }
    }

    private void initializeFeatures(EnumSet<NativeFeatures> requestedFeatures) {
        if (isNativeIntegrationsEnabled()) {
            for (NativeFeatures requestedFeature : requestedFeatures) {
                if (initializedFeatures.add(requestedFeature)) {
                    if (requestedFeature.initialize(nativeBaseDir, useNativeIntegrations)) {
                        enabledFeatures.add(requestedFeature);
                    }
                }
            }
        }
    }

    private static boolean isNativeIntegrationsEnabled() {
        return "true".equalsIgnoreCase(System.getProperty("org.gradle.native", "true"));
    }

    private boolean isFeatureEnabled(NativeFeatures feature) {
        return enabledFeatures.contains(feature);
    }

    private static File getNativeServicesDir(File userHomeDir) {
        String overrideProperty = getNativeDirOverride();
        if (overrideProperty == null) {
            return new File(userHomeDir, "native");
        } else {
            return new File(overrideProperty);
        }
    }

    private static String getNativeDirOverride() {
        return System.getProperty(NATIVE_DIR_OVERRIDE, System.getenv(NATIVE_DIR_OVERRIDE));
    }

    public static synchronized NativeServices getInstance() {
        if (!INSTANCE.initialized) {
            // If this occurs while running gradle or running integration tests, it is indicative of a problem.
            // If this occurs while running unit tests, then either use the NativeServicesTestFixture or the '@UsesNativeServices' annotation.
            throw new IllegalStateException("Cannot get an instance of NativeServices without first calling initialize().");
        }
        return INSTANCE;
    }

    private NativeServices() {
//        addProvider(new FileSystemServices());
        register(registration -> registration.add(GradleUserHomeTemporaryFileProvider.class));
    }

    @Override
    public void close() {
        // Don't close
    }

    protected GradleUserHomeDirProvider createGradleUserHomeDirProvider() {
        return new GradleUserHomeDirProvider() {
            @Override
            public File getGradleUserHomeDirectory() {
                return userHomeDir;
            }
        };
    }

    protected OperatingSystem createOperatingSystem() {
        return OperatingSystem.current();
    }

//    protected Jvm createJvm() {
//        return Jvm.current();
//    }
//
//    protected ProcessEnvironment createProcessEnvironment(OperatingSystem operatingSystem) {
//        if (useNativeIntegrations) {
//            try {
//                net.rubygrapefruit.platform.Process process = net.rubygrapefruit.platform.Native.get(Process.class);
//                return new NativePlatformBackedProcessEnvironment(process);
//            } catch (NativeIntegrationUnavailableException ex) {
//                LOGGER.debug("Native-platform process integration is not available. Continuing with fallback.");
//            }
//        }
//
//        return new UnsupportedEnvironment();
//    }

//    protected ConsoleDetector createConsoleDetector(OperatingSystem operatingSystem) {
//        return new TestOverrideConsoleDetector(backingConsoleDetector(operatingSystem));
//    }
//
//    private ConsoleDetector backingConsoleDetector(OperatingSystem operatingSystem) {
//        if (useNativeIntegrations) {
//            try {
//                Terminals terminals = net.rubygrapefruit.platform.Native.get(Terminals.class);
//                return new NativePlatformConsoleDetector(terminals);
//            } catch (NativeIntegrationUnavailableException ex) {
//                LOGGER.debug("Native-platform terminal integration is not available. Continuing with fallback.");
//            } catch (NativeException ex) {
//                LOGGER.debug("Unable to load from native-platform backed ConsoleDetector. Continuing with fallback. Failure: {}", format(ex));
//            }
//
//            try {
//                if (operatingSystem.isWindows()) {
//                    return new WindowsConsoleDetector();
//                }
//            } catch (LinkageError e) {
//                // Thrown when jna cannot initialize the native stuff
//                LOGGER.debug("Unable to load native library. Continuing with fallback. Failure: {}", format(e));
//            }
//        }
//
//        return new FallbackConsoleDetector();
//    }

    protected WindowsRegistry createWindowsRegistry(OperatingSystem operatingSystem) {
        if (useNativeIntegrations && operatingSystem.isWindows()) {
            return net.rubygrapefruit.platform.Native.get(WindowsRegistry.class);
        }
        return notAvailable(WindowsRegistry.class);
    }

    protected SystemInfo createSystemInfo() {
        if (useNativeIntegrations) {
            try {
                return net.rubygrapefruit.platform.Native.get(SystemInfo.class);
            } catch (NativeIntegrationUnavailableException e) {
                LOGGER.debug("Native-platform system info is not available. Continuing with fallback.");
            }
        }
        return notAvailable(SystemInfo.class);
    }

    protected Memory createMemory() {
        if (useNativeIntegrations) {
            try {
                return net.rubygrapefruit.platform.Native.get(Memory.class);
            } catch (NativeIntegrationUnavailableException e) {
                LOGGER.debug("Native-platform memory integration is not available. Continuing with fallback.");
            }
        }
        return notAvailable(Memory.class);
    }

    protected ProcessLauncher createProcessLauncher() {
        if (useNativeIntegrations) {
            try {
                return net.rubygrapefruit.platform.Native.get(ProcessLauncher.class);
            } catch (NativeIntegrationUnavailableException e) {
                LOGGER.debug("Native-platform process launcher is not available. Continuing with fallback.");
            }
        }
        return new DefaultProcessLauncher();
    }

    protected PosixFiles createPosixFiles() {
        if (useNativeIntegrations) {
            try {
                return net.rubygrapefruit.platform.Native.get(PosixFiles.class);
            } catch (NativeIntegrationUnavailableException e) {
                LOGGER.debug("Native-platform posix files integration is not available. Continuing with fallback.");
            }
        }
        throw new UnsupportedOperationException();
//        return notAvailable(UnavailablePosixFiles.class);
    }

    protected HostnameLookup createHostnameLookup() {
        if (useNativeIntegrations) {
            try {
                String hostname = Native.get(SystemInfo.class).getHostname();
                return new FixedHostname(hostname);
            } catch (NativeIntegrationUnavailableException e) {
                LOGGER.debug("Native-platform posix files integration is not available. Continuing with fallback.");
            }
        }
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            hostname = InetAddress.getLoopbackAddress().getHostAddress();
        }
        return new FixedHostname(hostname);
    }

//    protected FileMetadataAccessor createFileMetadataAccessor(OperatingSystem operatingSystem) {
//        // Based on the benchmark found in org.gradle.internal.nativeintegration.filesystem.FileMetadataAccessorBenchmark
//        // and the results in the PR https://github.com/gradle/gradle/pull/12966
//        // we're using "native platform" for all OSes if available.
//        // If it isn't available, we fall back to using Java NIO and, if that fails, to using the old `File` APIs.
//
//        if (useNativeIntegrations) {
//            try {
//                return new NativePlatformBackedFileMetadataAccessor(net.rubygrapefruit.platform.Native.get(Files.class));
//            } catch (NativeIntegrationUnavailableException e) {
//                LOGGER.debug("Native-platform files integration is not available. Continuing with fallback.");
//            }
//        }
//
//        if (JavaVersion.current().isJava7Compatible()) {
//            return newInstanceOrFallback("org.gradle.internal.nativeintegration.filesystem.jdk7.NioFileMetadataAccessor", NativeServices.class.getClassLoader(), FallbackFileMetadataAccessor.class);
//        }
//
//        return new FallbackFileMetadataAccessor();
//    }

//    protected NativeCapabilities createNativeCapabilities() {
//        return new NativeCapabilities() {
//            @Override
//            public boolean useNativeIntegrations() {
//                return useNativeIntegrations;
//            }
//
//            @Override
//            public boolean useFileSystemWatching() {
//                return isFeatureEnabled(NativeFeatures.FILE_SYSTEM_WATCHING);
//            }
//        };
//    }

    protected FileSystems createFileSystems() {
        if (useNativeIntegrations) {
            try {
                return net.rubygrapefruit.platform.Native.get(FileSystems.class);
            } catch (NativeIntegrationUnavailableException e) {
                LOGGER.debug("Native-platform file systems information is not available. Continuing with fallback.");
            }
        }
        return notAvailable(FileSystems.class);
    }

    private <T> T notAvailable(Class<T> type) {
        return Cast.uncheckedNonnullCast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new BrokenService(type.getSimpleName())));
    }

    private static String format(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        builder.append(throwable.toString());
        for (Throwable current = throwable.getCause(); current != null; current = current.getCause()) {
            builder.append(SystemProperties.getInstance().getLineSeparator());
            builder.append("caused by: ");
            builder.append(current);
        }
        return builder.toString();
    }

    private static class BrokenService implements InvocationHandler {
        private final String type;

        private BrokenService(String type) {
            this.type = type;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            throw new NativeIntegrationUnavailableException(String.format("%s is not supported on this operating system.", type));
        }
    }

    private static class FixedHostname implements HostnameLookup {
        private final String hostname;

        public FixedHostname(String hostname) {
            this.hostname = hostname;
        }

        @Override
        public String getHostname() {
            return hostname;
        }
    }
}
