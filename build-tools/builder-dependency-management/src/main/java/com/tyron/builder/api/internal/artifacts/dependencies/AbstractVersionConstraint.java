package com.tyron.builder.api.internal.artifacts.dependencies;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.tyron.builder.api.artifacts.VersionConstraint;

import java.util.List;

public abstract class AbstractVersionConstraint implements VersionConstraint {
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || o.getClass() != getClass()) {
            return false;
        }

        AbstractVersionConstraint that = (AbstractVersionConstraint) o;

        return Objects.equal(getRequiredVersion(), that.getRequiredVersion())
            && Objects.equal(getPreferredVersion(), that.getPreferredVersion())
            && Objects.equal(getStrictVersion(), that.getStrictVersion())
            && Objects.equal(getBranch(), that.getBranch())
            && Objects.equal(getRejectedVersions(), that.getRejectedVersions());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getRequiredVersion(), getPreferredVersion(), getStrictVersion(), getRejectedVersions());
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    private void append(String name, String version, StringBuilder builder) {
        if (version == null || version.isEmpty()) {
            return;
        }
        if (builder.length() != 1) {
            builder.append("; ");
        }
        builder.append(name);
        builder.append(" ");
        builder.append(version);
    }

    @Override
    public String getDisplayName() {
        String requiredVersion = getRequiredVersion();
        if (requiredOnly()) {
            return requiredVersion;
        }

        String strictVersion = getStrictVersion();
        String preferVersion = getPreferredVersion();
        StringBuilder builder = new StringBuilder();
        builder.append("{");
        append("strictly", strictVersion, builder);
        if (!requiredVersion.equals(strictVersion)) {
            append("require", requiredVersion, builder);
        }
        if (!(preferVersion.equals(requiredVersion) || preferVersion.equals(strictVersion))) {
            append("prefer", getPreferredVersion(), builder);
        }
        append("reject", rejectedVersionsString(), builder);
        append("branch", getBranch(), builder);
        builder.append("}");
        return builder.toString();
    }

    private boolean requiredOnly() {
        return (getPreferredVersion().isEmpty() || getRequiredVersion().equals(getPreferredVersion()))
                && getStrictVersion().isEmpty()
                && getRejectedVersions().isEmpty()
                && getBranch() == null;
    }

    private String rejectedVersionsString() {
        List<String> rejectedVersions = getRejectedVersions();
        if (rejectedVersions.size() == 1 && rejectedVersions.get(0).equals("+")) {
            return "all versions";
        } else {
            return Joiner.on(" & ").join(rejectedVersions);
        }
    }
}
