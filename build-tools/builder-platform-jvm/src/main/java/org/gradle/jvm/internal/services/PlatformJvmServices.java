package org.gradle.jvm.internal.services;

import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;
import org.gradle.jvm.toolchain.install.internal.AdoptOpenJdkDownloader;
import org.gradle.jvm.toolchain.install.internal.AdoptOpenJdkRemoteBinary;
import org.gradle.jvm.toolchain.install.internal.DefaultJavaToolchainProvisioningService;
import org.gradle.jvm.toolchain.install.internal.JdkCacheDirectory;
import org.gradle.jvm.toolchain.internal.DefaultJavaToolchainService;
import org.gradle.jvm.toolchain.internal.JavaInstallationRegistry;
import org.gradle.jvm.toolchain.internal.JavaToolchainFactory;
import org.gradle.jvm.toolchain.internal.JavaToolchainQueryService;
import org.gradle.jvm.toolchain.internal.LinuxInstallationSupplier;

public class PlatformJvmServices extends AbstractPluginServiceRegistry {

    @Override
    public void registerBuildServices(ServiceRegistration registration) {
        registration.add(JdkCacheDirectory.class);
        registration.add(JavaInstallationRegistry.class);
        registerJavaInstallationSuppliers(registration);
    }

    private void registerJavaInstallationSuppliers(ServiceRegistration registration) {
//        registration.add(AsdfInstallationSupplier.class);
//        registration.add(AutoInstalledInstallationSupplier.class);
//        registration.add(CurrentInstallationSupplier.class);
//        registration.add(EnvironmentVariableListInstallationSupplier.class);
//        registration.add(JabbaInstallationSupplier.class);
        registration.add(LinuxInstallationSupplier.class);
//        registration.add(LocationListInstallationSupplier.class);
//        registration.add(MavenToolchainsInstallationSupplier.class);
//        registration.add(OsXInstallationSupplier.class);
//        registration.add(SdkmanInstallationSupplier.class);
//        registration.add(WindowsInstallationSupplier.class);
    }

    @Override
    public void registerProjectServices(ServiceRegistration registration) {
        registration.add(JavaToolchainFactory.class);
        registration.add(DefaultJavaToolchainProvisioningService.class);
        registration.add(AdoptOpenJdkRemoteBinary.class);
        registration.add(AdoptOpenJdkDownloader.class);
        registration.add(JavaToolchainQueryService.class);
        registration.add(DefaultJavaToolchainService.class);
    }

}
