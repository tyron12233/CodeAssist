package com.tyron.code.lint;

import com.tyron.lint.api.Issue;
import com.tyron.lint.api.Location;
import com.tyron.lint.api.Severity;

public class LintIssue {

    private final Issue mIssue;

    private final Severity mSeverity;

    private final Location mLocation;

    public LintIssue(Issue mIssue, Severity mSeverity, Location mLocation) {
        this.mIssue = mIssue;
        this.mSeverity = mSeverity;
        this.mLocation = mLocation;
    }

    public Issue getIssue() {
        return mIssue;
    }

    public Severity getSeverity() {
        return mSeverity;
    }

    public Location getLocation() {
        return mLocation;
    }
}
