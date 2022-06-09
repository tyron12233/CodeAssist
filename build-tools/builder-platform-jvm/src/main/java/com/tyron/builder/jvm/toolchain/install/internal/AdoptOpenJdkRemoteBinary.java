package com.tyron.builder.jvm.toolchain.install.internal;

import net.rubygrapefruit.platform.SystemInfo;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.internal.deprecation.DeprecationLogger;
import com.tyron.builder.internal.jvm.inspection.JvmVendor;
import com.tyron.builder.internal.os.OperatingSystem;
import com.tyron.builder.jvm.toolchain.JavaLanguageVersion;
import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;
import com.tyron.builder.jvm.toolchain.JvmImplementation;
import com.tyron.builder.jvm.toolchain.internal.DefaultJvmVendorSpec;

import javax.inject.Inject;
import java.io.File;
import java.net.URI;
import java.util.Optional;

public class AdoptOpenJdkRemoteBinary {

    private final SystemInfo systemInfo;
    private final OperatingSystem operatingSystem;
    private final AdoptOpenJdkDownloader downloader;
    private final Provider<String> rootUrl;

    @Inject
    public AdoptOpenJdkRemoteBinary(SystemInfo systemInfo, OperatingSystem operatingSystem, AdoptOpenJdkDownloader downloader, ProviderFactory providerFactory) {
        this.systemInfo = systemInfo;
        this.operatingSystem = operatingSystem;
        this.downloader = downloader;
        rootUrl = providerFactory.gradleProperty("com.tyron.builder.jvm.toolchain.install.adoptopenjdk.baseUri");
    }

    public Optional<File> download(JavaToolchainSpec spec, File destinationFile) {
        if (!canProvideMatchingJdk(spec)) {
            return Optional.empty();
        }
        URI source = toDownloadUri(spec);
        downloader.download(source, destinationFile);
        return Optional.of(destinationFile);
    }

    public boolean canProvideMatchingJdk(JavaToolchainSpec spec) {
        final boolean matchesLanguageVersion = getLanguageVersion(spec).canCompileOrRun(8);
        boolean j9Requested = spec.getImplementation().get() == JvmImplementation.J9;
        boolean matchesVendor = matchesVendor(spec, j9Requested);
        return matchesLanguageVersion && matchesVendor;
    }

    private boolean matchesVendor(JavaToolchainSpec spec, boolean j9Requested) {
        final DefaultJvmVendorSpec vendorSpec = (DefaultJvmVendorSpec) spec.getVendor().get();
        if (vendorSpec == DefaultJvmVendorSpec.any()) {
            return true;
        }

        if (vendorSpec.test(JvmVendor.KnownJvmVendor.ADOPTOPENJDK.asJvmVendor())) {
            DeprecationLogger.deprecateBehaviour("Due to changes in AdoptOpenJDK download endpoint, downloading a JDK with an explicit vendor of AdoptOpenJDK should be replaced with a spec without a vendor or using Eclipse Temurin / IBM Semeru.")
                .willBeRemovedInGradle8().withUpgradeGuideSection(7, "adoptopenjdk_download").nagUser();
            return true;
        }

        if (vendorSpec.test(JvmVendor.KnownJvmVendor.ADOPTIUM.asJvmVendor()) && !j9Requested) {
            return true;
        }

        return vendorSpec.test(JvmVendor.KnownJvmVendor.IBM_SEMERU.asJvmVendor());
    }

    URI toDownloadUri(JavaToolchainSpec spec) {
        return constructUri(spec);
    }

    private URI constructUri(JavaToolchainSpec spec) {
        return URI.create(getServerBaseUri() +
            "v3/binary/latest/" + getLanguageVersion(spec).toString() +
            "/" +
            determineReleaseState() +
            "/" +
            determineOs() +
            "/" +
            determineArch() +
            "/jdk/" +
            determineImplementation(spec) +
            "/normal/adoptopenjdk");
    }

    private String determineImplementation(JavaToolchainSpec spec) {
        final JvmImplementation implementation = spec.getImplementation().getOrNull();
        String openj9 = "openj9";
        if (implementation == JvmImplementation.J9) {
            return openj9;
        }
        DefaultJvmVendorSpec vendorSpec = (DefaultJvmVendorSpec) spec.getVendor().get();
        if (vendorSpec != DefaultJvmVendorSpec.any() && vendorSpec.test(JvmVendor.KnownJvmVendor.IBM_SEMERU.asJvmVendor())) {
            return openj9;
        }
        return "hotspot";
    }

    public String toFilename(JavaToolchainSpec spec) {
        return String.format("adoptopenjdk-%s-%s-%s.%s", getLanguageVersion(spec).toString(), determineArch(), determineOs(), determineFileExtension());
    }

    private String determineFileExtension() {
        if (operatingSystem.isWindows()) {
            return "zip";
        }
        return "tar.gz";
    }

    private JavaLanguageVersion getLanguageVersion(JavaToolchainSpec spec) {
        return spec.getLanguageVersion().get();
    }

    private String determineArch() {
        switch (systemInfo.getArchitecture()) {
            case i386:
                return "x32";
            case amd64:
                return "x64";
            case aarch64:
                return "aarch64";
        }
        return systemInfo.getArchitectureName();
    }

    private String determineOs() {
        if (operatingSystem.isWindows()) {
            return "windows";
        } else if (operatingSystem.isMacOsX()) {
            return "mac";
        } else if (operatingSystem.isLinux()) {
            return "linux";
        }
        return operatingSystem.getFamilyName();
    }

    private String determineReleaseState() {
        return "ga";
    }

    private String getServerBaseUri() {
        String baseUri = rootUrl.getOrElse("https://api.adoptopenjdk.net/");
        if (!baseUri.endsWith("/")) {
            baseUri += "/";
        }
        return baseUri;
    }

}
