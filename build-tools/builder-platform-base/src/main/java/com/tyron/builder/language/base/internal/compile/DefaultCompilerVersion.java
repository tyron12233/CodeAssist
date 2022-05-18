package com.tyron.builder.language.base.internal.compile;

import com.tyron.builder.language.base.compile.CompilerVersion;
import com.tyron.builder.util.internal.VersionNumber;

//@NonNullApi
public class DefaultCompilerVersion implements CompilerVersion {

    private final String type;
    private final String vendor;
    private final VersionNumber version;

    public DefaultCompilerVersion(String type, String vendor, VersionNumber version) {
        this.type = type;
        this.vendor = vendor;
        this.version = version;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getVendor() {
        return vendor;
    }

    @Override
    public String getVersion() {
        return version.toString();
    }

    @Override
    public String toString() {
        return "CompilerVersion{" + "type='" + type + '\'' + ", vendor='" + vendor + '\'' + ", version=" + version + '}';
    }
}
