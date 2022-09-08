package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.AndroidArtifact;
import com.tyron.builder.model.JavaArtifact;
import com.tyron.builder.model.ProductFlavor;
import com.tyron.builder.model.TestedTargetVariant;
import com.tyron.builder.model.Variant;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of Variant that is serializable.
 */
@Immutable
final class VariantImpl implements Variant, Serializable {
    private static final long serialVersionUID = 3L;

    @NonNull
    private final String name;
    @NonNull
    private final String displayName;
    @NonNull
    private final String buildTypeName;
    @NonNull
    private final List<String> productFlavorNames;
    @NonNull
    private final ProductFlavor mergedFlavor;
    @NonNull
    private final AndroidArtifact mainArtifactInfo;
    @NonNull
    private final Collection<AndroidArtifact> extraAndroidArtifacts;
    @NonNull
    private final Collection<JavaArtifact> extraJavaArtifacts;
    @NonNull
    private final Collection<TestedTargetVariant> testedTargetVariants;

    private final boolean instantAppCompatible;

    @NonNull private final List<String> desugaredMethods;

    VariantImpl(
            @NonNull String name,
            @NonNull String displayName,
            @NonNull String buildTypeName,
            @NonNull List<String> productFlavorNames,
            @NonNull ProductFlavorImpl mergedFlavor,
            @NonNull AndroidArtifact mainArtifactInfo,
            @NonNull Collection<AndroidArtifact> extraAndroidArtifacts,
            @NonNull Collection<JavaArtifact> extraJavaArtifacts,
            @NonNull Collection<TestedTargetVariant> testedTargetVariants,
            boolean instantAppCompatible,
            @NonNull List<String> desugaredMethods) {
        this.name = name;
        this.displayName = displayName;
        this.buildTypeName = buildTypeName;
        this.productFlavorNames = productFlavorNames;
        this.mergedFlavor = mergedFlavor;
        this.mainArtifactInfo = mainArtifactInfo;
        this.extraAndroidArtifacts = extraAndroidArtifacts;
        this.extraJavaArtifacts = extraJavaArtifacts;
        this.testedTargetVariants = testedTargetVariants;
        this.instantAppCompatible = instantAppCompatible;
        this.desugaredMethods = desugaredMethods;
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @Override
    @NonNull
    public String getBuildType() {
        return buildTypeName;
    }

    @Override
    @NonNull
    public List<String> getProductFlavors() {
        return productFlavorNames;
    }

    @Override
    @NonNull
    public ProductFlavor getMergedFlavor() {
        return mergedFlavor;
    }

    @NonNull
    @Override
    public AndroidArtifact getMainArtifact() {
        return mainArtifactInfo;
    }

    @NonNull
    @Override
    public Collection<AndroidArtifact> getExtraAndroidArtifacts() {
        return extraAndroidArtifacts;
    }

    @NonNull
    @Override
    public Collection<JavaArtifact> getExtraJavaArtifacts() {
        return extraJavaArtifacts;
    }

    @NonNull
    @Override
    public Collection<TestedTargetVariant> getTestedTargetVariants() {
        return testedTargetVariants;
    }

    @Override
    public boolean isInstantAppCompatible() {
        return instantAppCompatible;
    }

    @NonNull
    @Override
    public List<String> getDesugaredMethods() {
        return desugaredMethods;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        VariantImpl variant = (VariantImpl) o;
        return Objects.equals(name, variant.name)
                && Objects.equals(displayName, variant.displayName)
                && Objects.equals(buildTypeName, variant.buildTypeName)
                && Objects.equals(productFlavorNames, variant.productFlavorNames)
                && Objects.equals(mergedFlavor, variant.mergedFlavor)
                && Objects.equals(mainArtifactInfo, variant.mainArtifactInfo)
                && Objects.equals(extraAndroidArtifacts, variant.extraAndroidArtifacts)
                && Objects.equals(extraJavaArtifacts, variant.extraJavaArtifacts)
                && Objects.equals(testedTargetVariants, variant.testedTargetVariants)
                && Objects.equals(instantAppCompatible, variant.instantAppCompatible)
                && Objects.equals(desugaredMethods, variant.desugaredMethods);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                name,
                displayName,
                buildTypeName,
                productFlavorNames,
                mergedFlavor,
                mainArtifactInfo,
                extraAndroidArtifacts,
                extraJavaArtifacts,
                testedTargetVariants,
                instantAppCompatible,
                desugaredMethods);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("displayName", displayName)
                .add("buildTypeName", buildTypeName)
                .add("productFlavorNames", productFlavorNames)
                .add("mergedFlavor", mergedFlavor)
                .add("mainArtifactInfo", mainArtifactInfo)
                .add("extraAndroidArtifacts", extraAndroidArtifacts)
                .add("extraJavaArtifacts", extraJavaArtifacts)
                .add("testedTargetVariants", testedTargetVariants)
                .add("instantAppCompatible", instantAppCompatible)
                .add("desugaredMethods", desugaredMethods)
                .toString();
    }
}