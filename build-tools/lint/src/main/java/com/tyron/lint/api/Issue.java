package com.tyron.lint.api;

import static com.tyron.lint.api.TextFormat.RAW;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Issue implements Comparable<Issue> {

    private final String mId;
    private final String mBriefDescription;
    private final String mExplanation;
    private final Category mCategory;
    private final int mPriority;
    private final Severity mSeverity;
    private Implementation mImplementation;

    private Object mMoreInfoUrls;
    private boolean mEnabledByDefault = true;

    /// use factory methods
    private Issue(
            @NonNull String mId,
            @NonNull String mBriefDescription,
            @NonNull String mExplanation,
            @NonNull Category mCategory,
            int mPriority,
            @NonNull Severity mSeverity,
            @NonNull Implementation mImplementation) {
        this.mId = mId;
        this.mBriefDescription = mBriefDescription;
        this.mExplanation = mExplanation;
        this.mCategory = mCategory;
        this.mPriority = mPriority;
        this.mSeverity = mSeverity;
        this.mImplementation = mImplementation;
    }

    /**
     * Creates a new issue. The description strings can use some simple markup;
     * see the {@link TextFormat#RAW} documentation
     * for details.
     *
     * @param id the fixed id of the issue
     * @param briefDescription short summary (typically 5-6 words or less), typically
     *                         describing the <b>problem</b> rather than the <b>fix</b>
     *                         (e.g. "Missing minSdkVersion")
     * @param explanation a full explanation of the issue, with suggestions for
     *            how to fix it
     * @param category the associated category, if any
     * @param priority the priority, a number from 1 to 10 with 10 being most
     *            important/severe
     * @param severity the default severity of the issue
     * @return a new {@link Issue}
     */
    @NonNull
    public static Issue create(
            @NonNull String id,
            @NonNull String briefDescription,
            @NonNull String explanation,
            @NonNull Category category,
            int priority,
            @NonNull Severity severity,
            @NonNull Implementation mImplementation) {
        return new Issue(id, briefDescription, explanation, category, priority,
                severity, mImplementation);
    }

    /*
     * Returns the unique id of this issue. These should not change over time
     * since they are used to persist the names of issues suppressed by the user
     * etc. It is typically a single camel-cased word.
     *
     * @return the associated fixed id, never null and always unique
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Briefly (in a couple of words) describes these errors
     *
     * @return a brief summary of the issue, never null, never empty
     */
    @NonNull
    public String getBriefDescription(@NonNull TextFormat format) {
        return RAW.convertTo(mBriefDescription, format);
    }

    /**
     * Describes the error found by this rule, e.g.
     * "Buttons must define contentDescriptions". Preferably the explanation
     * should also contain a description of how the problem should be solved.
     * Additional info can be provided via {@link #getMoreInfo()}.
     *
     * @param format the format to write the format as
     * @return an explanation of the issue, never null, never empty
     */
    @NonNull
    public String getExplanation(@NonNull TextFormat format) {
        return RAW.convertTo(mExplanation, format);
    }

    /**
     * The primary category of the issue
     *
     * @return the primary category of the issue, never null
     */
    @NonNull
    public Category getCategory() {
        return mCategory;
    }

    /**
     * Returns a priority, in the range 1-10, with 10 being the most severe and
     * 1 the least
     *
     * @return a priority from 1 to 10
     */
    public int getPriority() {
        return mPriority;
    }

    /**
     * Returns the default severity of the issues found by this detector (some
     * tools may allow the user to specify custom severities for detectors).
     * <p>
     * Note that even though the normal way for an issue to be disabled is for
     * the {Configuration} to return {@link Severity#IGNORE}, there is a
     * {@link #isEnabledByDefault()} method which can be used to turn off issues
     * by default. This is done rather than just having the severity as the only
     * attribute on the issue such that an issue can be configured with an
     * appropriate severity (such as {@link Severity#ERROR}) even when issues
     * are disabled by default for example because they are experimental or not
     * yet stable.
     *
     * @return the severity of the issues found by this detector
     */
    @NonNull
    public Severity getDefaultSeverity() {
        return mSeverity;
    }

    /**
     * Returns a link (a URL string) to more information, or null
     *
     * @return a link to more information, or null
     */
    @NonNull
    public List<String> getMoreInfo() {
        if (mMoreInfoUrls == null) {
            return Collections.emptyList();
        } else if (mMoreInfoUrls instanceof String) {
            return Collections.singletonList((String) mMoreInfoUrls);
        } else {
            assert mMoreInfoUrls instanceof List;
            //noinspection unchecked
            return (List<String>) mMoreInfoUrls;
        }
    }

    /**
     * Adds a more info URL string
     *
     * @param moreInfoUrl url string
     * @return this, for constructor chaining
     */
    @NonNull
    public Issue addMoreInfo(@NonNull String moreInfoUrl) {
        // Nearly all issues supply at most a single URL, so don't bother with
        // lists wrappers for most of these issues
        if (mMoreInfoUrls == null) {
            mMoreInfoUrls = moreInfoUrl;
        } else if (mMoreInfoUrls instanceof String) {
            String existing = (String) mMoreInfoUrls;
            List<String> list = new ArrayList<String>(2);
            list.add(existing);
            list.add(moreInfoUrl);
            mMoreInfoUrls = list;
        } else {
            assert mMoreInfoUrls instanceof List;
            //noinspection unchecked
            ((List<String>) mMoreInfoUrls).add(moreInfoUrl);
        }
        return this;
    }

    /**
     * Returns whether this issue should be enabled by default, unless the user
     * has explicitly disabled it.
     *
     * @return true if this issue should be enabled by default
     */
    public boolean isEnabledByDefault() {
        return mEnabledByDefault;
    }

    /**
     * Sorts the detectors alphabetically by id. This is intended to make it
     * convenient to store settings for detectors in a fixed order. It is not
     * intended as the order to be shown to the user; for that, a tool embedding
     * lint might consider the priorities, categories, severities etc of the
     * various detectors.
     *
     * @param other the {@link Issue} to compare this issue to
     */
    @Override
    public int compareTo(@NonNull Issue other) {
        return getId().compareTo(other.getId());
    }

    /**
     * Returns the implementation for the given issue
     *
     * @return the implementation for this issue
     */
    @NonNull
    public Implementation getImplementation() {
        return mImplementation;
    }

    /**
     * Sets the implementation for the given issue. This is typically done by
     * IDEs that can offer a replacement for a given issue which performs better
     * or in some other way works better within the IDE.
     *
     * @param implementation the new implementation to use
     */
    public void setImplementation(@NonNull Implementation implementation) {
        mImplementation = implementation;
    }

    /**
     * Sets whether this issue is enabled by default.
     *
     * @param enabledByDefault whether the issue should be enabled by default
     * @return this, for constructor chaining
     */
    @NonNull
    public Issue setEnabledByDefault(boolean enabledByDefault) {
        mEnabledByDefault = enabledByDefault;
        return this;
    }

    @Override
    public String toString() {
        return mId;
    }
}
