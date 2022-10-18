package com.tyron.builder.gradle.internal.api;

import static com.tyron.builder.gradle.internal.api.BaseVariantImpl.TASK_ACCESS_DEPRECATION_URL;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.variant.impl.VariantOutputImpl;
import com.tyron.builder.gradle.api.LibraryVariantOutput;
import com.tyron.builder.gradle.errors.DeprecationReporter;
import com.tyron.builder.gradle.internal.scope.TaskContainer;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.core.ComponentType;
import java.io.File;
import javax.inject.Inject;
import org.gradle.api.tasks.bundling.Zip;

/**
 * Implementation of variant output for library variants.
 *
 * This is a wrapper around the internal data model, in order to control what is accessible
 * through the external API.
 */
public class LibraryVariantOutputImpl extends BaseVariantOutputImpl implements LibraryVariantOutput {

    @Inject
    public LibraryVariantOutputImpl(
            @NonNull TaskContainer taskContainer,
            @NonNull DslServices services,
            @NonNull VariantOutputImpl variantOutput,
            @NonNull ComponentType ignored) {
        super(taskContainer, services, variantOutput);
    }

    @Nullable
    @Override
    public Zip getPackageLibrary() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getPackageLibraryProvider()",
                        "variantOutput.getPackageLibrary()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        return taskContainer.getBundleLibraryTask().getOrNull();
    }

    @NonNull
    @Override
    public File getOutputFile() {
        Zip packageTask = getPackageLibrary();
        if (packageTask != null) {
            return new File(
                    packageTask.getDestinationDirectory().get().getAsFile(),
                    variantOutput.getOutputFileName().get());
        } else {
            return super.getOutputFile();
        }
    }

    @Override
    public int getVersionCode() {
        throw new RuntimeException("Libraries are not versioned");
    }
}
