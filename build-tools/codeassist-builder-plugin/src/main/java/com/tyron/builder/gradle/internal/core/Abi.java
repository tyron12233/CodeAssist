package com.tyron.builder.gradle.internal.core;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.collect.ImmutableList;
import java.util.Collection;

/**
 * Enum of valid ABI you can specify for NDK.
 */
public enum Abi {
    ARMEABI(
            SdkConstants.ABI_ARMEABI,
            SdkConstants.CPU_ARCH_ARM,
            "arm-linux-androideabi",
            "arm-linux-androideabi",
            false,
            false),
    ARMEABI_V7A(
            SdkConstants.ABI_ARMEABI_V7A,
            SdkConstants.CPU_ARCH_ARM,
            "arm-linux-androideabi",
            "arm-linux-androideabi",
            false,
            true),
    ARM64_V8A(
            SdkConstants.ABI_ARM64_V8A,
            SdkConstants.CPU_ARCH_ARM64,
            "aarch64-linux-android",
            "aarch64-linux-android",
            true,
            true),
    X86(
            SdkConstants.ABI_INTEL_ATOM,
            SdkConstants.CPU_ARCH_INTEL_ATOM,
            "x86",
            "i686-linux-android",
            false,
            true),
    X86_64(
            SdkConstants.ABI_INTEL_ATOM64,
            SdkConstants.CPU_ARCH_INTEL_ATOM64,
            "x86_64",
            "x86_64-linux-android",
            true,
            true),
    MIPS(
            SdkConstants.ABI_MIPS,
            SdkConstants.CPU_ARCH_MIPS,
            "mipsel-linux-android",
            "mipsel-linux-android",
            false,
            false),
    MIPS64(
            SdkConstants.ABI_MIPS64,
            SdkConstants.CPU_ARCH_MIPS64,
            "mips64el-linux-android",
            "mips64el-linux-android",
            true,
            false);


    @NonNull
    private final String name;
    @NonNull
    final String architecture;
    @NonNull
    private final String gccToolchainPrefix;
    @NonNull
    private final String gccExecutablePrefix;
    private final boolean supports64Bits;
    private final boolean isDefault;

    private static ImmutableList<Abi> defaultValues = null;

    Abi(
            @NonNull String name,
            @NonNull String architecture,
            @NonNull String gccToolchainPrefix,
            @NonNull String gccExecutablePrefix,
            boolean supports64Bits,
            boolean isDefault) {
        this.name = name;
        this.architecture = architecture;
        this.gccToolchainPrefix = gccToolchainPrefix;
        this.gccExecutablePrefix = gccExecutablePrefix;
        this.supports64Bits = supports64Bits;
        this.isDefault = isDefault;
    }

    /**
     * Returns the ABI Enum with the specified name.
     */
    @Nullable
    public static Abi getByName(@NonNull String name) {
        for (Abi abi : values()) {
            if (abi.name.equals(name)) {
                return abi;
            }
        }
        return null;
    }
    
    /**
     * Returns name of the ABI like "armeabi-v7a". Not called getName(...) because that conflicts
     * confusingly with Kotlin's Enum::name.
     */
    @NonNull
    public String getTag() {
        return name;
    }

    /**
     * Returns the CPU architecture like "arm".
     */
    @NonNull
    public String getArchitecture() {
        return architecture;
    }

    /**
     * Returns the platform string for locating the toolchains in the NDK.
     */
    @NonNull
    public String getGccToolchainPrefix() {
        return gccToolchainPrefix;
    }

    /**
     * Returns the prefix of GCC tools for the ABI.
     */
    @NonNull
    public String getGccExecutablePrefix() {
        return gccExecutablePrefix;
    }

    /**
     * Returns whether the ABI supports 64-bits.
     */
    public boolean supports64Bits() {
        return supports64Bits;
    }


    /**
     * Return the list of values that should be used when a user requests the default list of ABIs.
     */
    @NonNull
    public static Collection<Abi> getDefaultValues() {
        if (defaultValues != null) {
            return defaultValues;
        }

        ImmutableList.Builder<Abi> builder = ImmutableList.builder();
        for (Abi abi : Abi.values()) {
            if (abi.isDefault) {
                builder.add(abi);
            }
        }
        defaultValues = builder.build();
        return defaultValues;
    }
}