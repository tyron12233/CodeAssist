package com.tyron.builder.gradle.internal.tasks;

/**
 * Task installing an app variant. It looks at connected device and install the best matching
 * variant output on each device.
 */

import static com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.ANDROID_PRIVACY_SANDBOX_SDK_APKS;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.sdklib.AndroidVersion;
import com.android.utils.ILogger;
import com.tyron.builder.api.artifact.SingleArtifact;
import com.tyron.builder.api.dsl.Installation;
import com.tyron.builder.api.variant.impl.BuiltArtifactsLoaderImpl;
import com.tyron.builder.api.variant.impl.VariantApiExtensionsKt;
import com.tyron.builder.gradle.internal.BuildToolsExecutableInput;
import com.tyron.builder.gradle.internal.LoggerWrapper;
import com.tyron.builder.gradle.internal.TaskManager;
import com.tyron.builder.gradle.internal.component.ApkCreationConfig;
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts;
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction;
import com.tyron.builder.gradle.internal.test.BuiltArtifactsSplitOutputMatcher;
import com.tyron.builder.gradle.options.BooleanOption;
import com.tyron.builder.testing.api.DeviceConfigProvider;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.util.ApkInstaller;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.DEPLOYMENT)
public abstract class InstallVariantTask extends NonIncrementalTask {

    private int timeOutInMs = 0;

    private Collection<String> installOptions;

    private String variantName;
    private Set<String> supportedAbis;
    private AndroidVersion minSdkVersion;

    @Inject
    public InstallVariantTask() {
        this.getOutputs().upToDateWhen(task -> {
            getLogger().debug("Install task is always run.");
            return false;
        });
    }

    @Override
    protected void doTaskAction() throws Exception {
        final ILogger iLogger = new LoggerWrapper(getLogger());

        DeviceConfigProvider provider = new DeviceConfigProvider() {
            @NotNull
            @Override
            public String getConfigFor(String abi) {
                return abi;
            }

            @Override
            public int getDensity() {
                return 0;
            }

            @Nullable
            @Override
            public String getLanguage() {
                return null;
            }

            @Nullable
            @Override
            public String getRegion() {
                return null;
            }

            @NotNull
            @Override
            public List<String> getAbis() {
                return Arrays.asList(getStringList("ro.product.cpu.abilist", ","));
            }
        };
        List<File> apkFiles = BuiltArtifactsSplitOutputMatcher.INSTANCE.computeBestOutput(provider,
                Objects.requireNonNull(new BuiltArtifactsLoaderImpl().load(getApkDirectory().get())),
                supportedAbis);
        if (apkFiles.isEmpty()) {
            getLogger().lifecycle("No APKS to install.");
        } else {
            getLogger().debug("Installing one APK in " + apkFiles);

            File file = apkFiles.get(0);
            ApkInstaller.installApplication(
                    "com.tyron.code",
                    file.getAbsolutePath()
            );
        }
    }

    private static String[] getStringList(String property, String separator) {
        String value = System.getProperty(property, "");
        if (value.isEmpty()) {
            return new String[0];
        } else {
            return value.split(separator);
        }
    }

    @Input
    public int getTimeOutInMs() {
        return timeOutInMs;
    }

    public void setTimeOutInMs(int timeOutInMs) {
        this.timeOutInMs = timeOutInMs;
    }

    @Input
    @Optional
    public Collection<String> getInstallOptions() {
        return installOptions;
    }

    public void setInstallOptions(Collection<String> installOptions) {
        this.installOptions = installOptions;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getApkDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    public abstract ConfigurableFileCollection getPrivacySandboxSdksApksFiles();

    @Nested
    public abstract BuildToolsExecutableInput getBuildTools();

    public static class CreationAction extends VariantTaskCreationAction<InstallVariantTask,
            ApkCreationConfig> {

        public CreationAction(@NonNull ApkCreationConfig creationConfig) {
            super(creationConfig);
        }

        @NonNull
        @Override
        public String getName() {
            return computeTaskName("install");
        }

        @NonNull
        @Override
        public Class<InstallVariantTask> getType() {
            return InstallVariantTask.class;
        }

        @Override
        public void configure(@NonNull InstallVariantTask task) {
            super.configure(task);

            task.variantName = creationConfig.getBaseName();
            task.supportedAbis = creationConfig.getSupportedAbis();
            task.minSdkVersion =
                    VariantApiExtensionsKt.toSharedAndroidVersion(creationConfig.getMinSdkVersion());

            task.setDescription("Installs the " + creationConfig.getDescription() + ".");
            task.setGroup(TaskManager.INSTALL_GROUP);
            creationConfig.getArtifacts()
                    .setTaskInputToFinalProduct(SingleArtifact.APK.INSTANCE,
                            task.getApkDirectory());
            if (creationConfig.getServices()
                    .getProjectOptions()
                    .get(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT)) {
                task.getPrivacySandboxSdksApksFiles()
                        .setFrom(creationConfig.getVariantDependencies()
                                .getArtifactFileCollection(AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                                        AndroidArtifacts.ArtifactScope.ALL,
                                        ANDROID_PRIVACY_SANDBOX_SDK_APKS));
            }
            task.getPrivacySandboxSdksApksFiles().disallowChanges();

            Installation installationOptions = creationConfig.getGlobal().getInstallationOptions();
            task.setTimeOutInMs(installationOptions.getTimeOutInMs());
            task.setInstallOptions(installationOptions.getInstallOptions());

//            SdkComponentsKt.initialize(task.getBuildTools(), creationConfig);
        }

        @Override
        public void handleProvider(@NonNull TaskProvider<InstallVariantTask> taskProvider) {
            super.handleProvider(taskProvider);
            creationConfig.getTaskContainer().setInstallTask(taskProvider);
        }
    }
}