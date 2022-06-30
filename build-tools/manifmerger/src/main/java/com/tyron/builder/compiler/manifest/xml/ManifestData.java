package com.tyron.builder.compiler.manifest.xml;


import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.compiler.manifest.resources.Keyboard;
import com.tyron.builder.compiler.manifest.resources.Navigation;
import com.tyron.builder.compiler.manifest.resources.TouchScreen;

import org.xml.sax.Attributes;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

/**
 * Class containing the manifest info obtained during the parsing.
 */
public final class ManifestData {

    /**
     * Value returned by {@link #getMinSdkVersion()} when the value of the minSdkVersion attribute
     * in the manifest is a codename and not an integer value.
     */
    public static final int MIN_SDK_CODENAME = 0;

    /**
     * Value returned by {@link #getGlEsVersion()} when there are no {@code <uses-feature>} node
     * with the attribute glEsVersion set.
     */
    public static final int GL_ES_VERSION_NOT_SET = -1;

    /** Application package */
    String mPackage = "";
    /** Application version code, null if the attribute is not present. */
    Integer mVersionCode = null;
    /** Application version name, null if the attribute is not present. */
    String mVersionName = null;
    /** Default Dex process */
    String mDefaultProcess;
    /** List of all activities */
    final ArrayList<Activity> mActivities = new ArrayList<Activity>();
    /** List of all activities, services, receivers and providers to keep for Proguard and Dex * */
    final ArrayList<KeepClass> mKeepClasses = new ArrayList<KeepClass>();
    /** Launcher activity */
    Activity mLauncherActivity = null;
    /** list of process names declared by the manifest */
    Set<String> mProcesses = null;
    /** debuggable attribute value. If null, the attribute is not present. */
    Boolean mDebuggable = null;
    /** API level requirement. if null the attribute was not present. */
    private String mMinSdkVersionString = null;
    /** API level requirement. Default is 1 even if missing. If value is a codename, then it'll be
     * 0 instead. */
    private int mMinSdkVersion = 1;
    private int mTargetSdkVersion = 0;
    /** List of all instrumentations declared by the manifest */
    final ArrayList<Instrumentation> mInstrumentations =
            new ArrayList<Instrumentation>();
    /** List of all libraries in use declared by the manifest */
    final ArrayList<UsesLibrary> mLibraries = new ArrayList<UsesLibrary>();
    /** List of all feature in use declared by the manifest */
    final ArrayList<UsesFeature> mFeatures = new ArrayList<UsesFeature>();
    /** List of all the custom permissions declared in the manifest */
    final ArrayList<String> mCustomPermissions = new ArrayList<>();

    SupportsScreens mSupportsScreensFromManifest;
    SupportsScreens mSupportsScreensValues;
    UsesConfiguration mUsesConfiguration;

    private String theme;

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getTheme() {
        return theme;
    }

    /**
     * Instrumentation info obtained from manifest
     */
    public static final class Instrumentation {
        private final String mName;
        private final String mTargetPackage;

        Instrumentation(String name, String targetPackage) {
            mName = name;
            mTargetPackage = targetPackage;
        }

        /**
         * Returns the fully qualified instrumentation class name
         */
        public String getName() {
            return mName;
        }

        /**
         * Returns the Android app package that is the target of this instrumentation
         */
        public String getTargetPackage() {
            return mTargetPackage;
        }
    }

    /**
     * Activity info obtained from the manifest.
     */
    public static final class Activity {
        private final String mName;
        private final boolean mIsExported;
        private boolean mHasAction = false;
        private boolean mHasMainAction = false;
        private boolean mHasLauncherCategory = false;
        private String theme;

        public Activity(String name, boolean exported) {
            mName = name;
            mIsExported = exported;
        }

        public String getName() {
            return mName;
        }

        public boolean isExported() {
            return mIsExported;
        }

        public boolean hasAction() {
            return mHasAction;
        }

        public boolean isHomeActivity() {
            return mHasMainAction && mHasLauncherCategory;
        }

        public String getTheme() {
            return theme;
        }

        void setHasAction(boolean hasAction) {
            mHasAction = hasAction;
        }

        /** If the activity doesn't yet have a filter set for the launcher, this resets both
         * flags. This is to handle multiple intent-filters where one could have the valid
         * action, and another one of the valid category.
         */
        void resetIntentFilter() {
            if (isHomeActivity() == false) {
                mHasMainAction = mHasLauncherCategory = false;
            }
        }

        void setHasMainAction(boolean hasMainAction) {
            mHasMainAction = hasMainAction;
        }

        void setHasLauncherCategory(boolean hasLauncherCategory) {
            mHasLauncherCategory = hasLauncherCategory;
        }

        public void setTheme(String theme) {
            this.theme = theme;
        }
    }

    public static final class KeepClass {
        @NotNull
        private final String name;
        @Nullable
        private final String process;
        @NotNull private final String type;

        public KeepClass(@NotNull String name, @Nullable String process, @NotNull String type) {
            this.name = name;
            this.process = process;
            this.type = type;
        }

        @NotNull
        public String getName() {
            return name;
        }

        @Nullable
        public String getProcess() {
            return process;
        }

        @NotNull
        public String getType() {
            return type;
        }
    }

    /**
     * Class representing the <code>supports-screens</code> node in the manifest.
     * By default, all the getters will return null if there was no value defined in the manifest.
     *
     * To get an instance with all the actual values, use {@link #resolveSupportsScreensValues(int)}
     */
    public static final class SupportsScreens {
        private Boolean mResizeable;
        private Boolean mAnyDensity;
        private Boolean mSmallScreens;
        private Boolean mNormalScreens;
        private Boolean mLargeScreens;

        public SupportsScreens() {
        }

        /**
         * Instantiate an instance from a string. The string must have been created with
         * {@link #getEncodedValues()}.
         * @param value the string.
         */
        public SupportsScreens(String value) {
            String[] values = value.split("\\|");

            mAnyDensity = Boolean.valueOf(values[0]);
            mResizeable = Boolean.valueOf(values[1]);
            mSmallScreens = Boolean.valueOf(values[2]);
            mNormalScreens = Boolean.valueOf(values[3]);
            mLargeScreens = Boolean.valueOf(values[4]);
        }

        /**
         * Returns an instance of {@link SupportsScreens} initialized with the default values
         * based on the given targetSdkVersion.
         * @param targetSdkVersion
         */
        public static SupportsScreens getDefaultValues(int targetSdkVersion) {
            SupportsScreens result = new SupportsScreens();

            result.mNormalScreens = Boolean.TRUE;
            // Screen size and density became available in Android 1.5/API3, so before that
            // non normal screens were not supported by default. After they are considered
            // supported.
            result.mResizeable = result.mAnyDensity = result.mSmallScreens = result.mLargeScreens =
                    targetSdkVersion <= 3 ? Boolean.FALSE : Boolean.TRUE;

            return result;
        }

        /**
         * Returns a version of the receiver for which all values have been set, even if they
         * were not present in the manifest.
         * @param targetSdkVersion the target api level of the app, since this has an effect
         * on default values.
         */
        public SupportsScreens resolveSupportsScreensValues(int targetSdkVersion) {
            SupportsScreens result = getDefaultValues(targetSdkVersion);

            // Override the default with the existing values:
            if (mResizeable != null) result.mResizeable = mResizeable;
            if (mAnyDensity != null) result.mAnyDensity = mAnyDensity;
            if (mSmallScreens != null) result.mSmallScreens = mSmallScreens;
            if (mNormalScreens != null) result.mNormalScreens = mNormalScreens;
            if (mLargeScreens != null) result.mLargeScreens = mLargeScreens;

            return result;
        }

        /**
         * returns the value of the <code>resizeable</code> attribute or null if not present.
         */
        public Boolean getResizeable() {
            return mResizeable;
        }

        void setResizeable(Boolean resizeable) {
            mResizeable = getConstantBoolean(resizeable);
        }

        /**
         * returns the value of the <code>anyDensity</code> attribute or null if not present.
         */
        public Boolean getAnyDensity() {
            return mAnyDensity;
        }

        void setAnyDensity(Boolean anyDensity) {
            mAnyDensity = getConstantBoolean(anyDensity);
        }

        /**
         * returns the value of the <code>smallScreens</code> attribute or null if not present.
         */
        public Boolean getSmallScreens() {
            return mSmallScreens;
        }

        void setSmallScreens(Boolean smallScreens) {
            mSmallScreens = getConstantBoolean(smallScreens);
        }

        /**
         * returns the value of the <code>normalScreens</code> attribute or null if not present.
         */
        public Boolean getNormalScreens() {
            return mNormalScreens;
        }

        void setNormalScreens(Boolean normalScreens) {
            mNormalScreens = getConstantBoolean(normalScreens);
        }

        /**
         * returns the value of the <code>largeScreens</code> attribute or null if not present.
         */
        public Boolean getLargeScreens() {
            return mLargeScreens;
        }

        void setLargeScreens(Boolean largeScreens) {
            mLargeScreens = getConstantBoolean(largeScreens);
        }

        /**
         * Returns either {@link Boolean#TRUE} or {@link Boolean#FALSE} based on the value of
         * the given Boolean object.
         */
        private Boolean getConstantBoolean(Boolean v) {
            if (v != null) {
                if (v.equals(Boolean.TRUE)) {
                    return Boolean.TRUE;
                } else {
                    return Boolean.FALSE;
                }
            }

            return null;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SupportsScreens) {
                SupportsScreens support = (SupportsScreens) obj;
                // since all the fields are guaranteed to be either Boolean.TRUE or Boolean.FALSE
                // (or null), we can simply check they are identical and not bother with
                // calling equals (which would require to check != null.
                // see #getConstanntBoolean(Boolean)
                return mResizeable    == support.mResizeable &&
                        mAnyDensity    == support.mAnyDensity &&
                        mSmallScreens  == support.mSmallScreens &&
                        mNormalScreens == support.mNormalScreens &&
                        mLargeScreens  == support.mLargeScreens;
            }

            return false;
        }

        /* Override hashCode, mostly to make Eclipse happy and not warn about it.
         * And if you ever put this in a Map or Set, it will avoid surprises. */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((mAnyDensity    == null) ? 0 : mAnyDensity.hashCode());
            result = prime * result + ((mLargeScreens  == null) ? 0 : mLargeScreens.hashCode());
            result = prime * result + ((mNormalScreens == null) ? 0 : mNormalScreens.hashCode());
            result = prime * result + ((mResizeable    == null) ? 0 : mResizeable.hashCode());
            result = prime * result + ((mSmallScreens  == null) ? 0 : mSmallScreens.hashCode());
            return result;
        }

        /**
         * Returns true if the two instances support the same screen sizes.
         * This is similar to {@link #equals(Object)} except that it ignores the values of
         * {@link #getAnyDensity()} and {@link #getResizeable()}.
         * @param support the other instance to compare to.
         * @return true if the two instances support the same screen sizes.
         */
        public boolean hasSameScreenSupportAs(SupportsScreens support) {
            // since all the fields are guaranteed to be either Boolean.TRUE or Boolean.FALSE
            // (or null), we can simply check they are identical and not bother with
            // calling equals (which would require to check != null.
            // see #getConstanntBoolean(Boolean)

            // This only checks that matter here are the screen sizes. resizeable and anyDensity
            // are not checked.
            return  mSmallScreens == support.mSmallScreens &&
                    mNormalScreens == support.mNormalScreens &&
                    mLargeScreens == support.mLargeScreens;
        }

        /**
         * Returns true if the two instances have strictly different screen size support.
         * This means that there is no screen size that they both support.
         * @param support the other instance to compare to.
         * @return true if they are strictly different.
         */
        public boolean hasStrictlyDifferentScreenSupportAs(SupportsScreens support) {
            // since all the fields are guaranteed to be either Boolean.TRUE or Boolean.FALSE
            // (or null), we can simply check they are identical and not bother with
            // calling equals (which would require to check != null.
            // see #getConstanntBoolean(Boolean)

            // This only checks that matter here are the screen sizes. resizeable and anyDensity
            // are not checked.
            return (mSmallScreens != Boolean.TRUE || support.mSmallScreens != Boolean.TRUE) &&
                    (mNormalScreens != Boolean.TRUE || support.mNormalScreens != Boolean.TRUE) &&
                    (mLargeScreens != Boolean.TRUE || support.mLargeScreens != Boolean.TRUE);
        }

        /**
         * Comparison of 2 Supports-screens. This only uses screen sizes (ignores resizeable and
         * anyDensity), and considers that
         * {@link #hasStrictlyDifferentScreenSupportAs(SupportsScreens)} returns true and
         * {@link #overlapWith(SupportsScreens)} returns false.
         * @throws IllegalArgumentException if the two instanced are not strictly different or
         * overlap each other
         * @see #hasStrictlyDifferentScreenSupportAs(SupportsScreens)
         * @see #overlapWith(SupportsScreens)
         */
        public int compareScreenSizesWith(SupportsScreens o) {
            if (hasStrictlyDifferentScreenSupportAs(o) == false) {
                throw new IllegalArgumentException("The two instances are not strictly different.");
            }
            if (overlapWith(o)) {
                throw new IllegalArgumentException("The two instances overlap each other.");
            }

            int comp = mLargeScreens.compareTo(o.mLargeScreens);
            if (comp != 0) return comp;

            comp = mNormalScreens.compareTo(o.mNormalScreens);
            if (comp != 0) return comp;

            comp = mSmallScreens.compareTo(o.mSmallScreens);
            if (comp != 0) return comp;

            return 0;
        }

        /**
         * Returns a string encoding of the content of the instance. This string can be used to
         * instantiate a {@link SupportsScreens} object through
         * {@link #SupportsScreens(String)}.
         */
        public String getEncodedValues() {
            return String.format("%1$s|%2$s|%3$s|%4$s|%5$s",
                    mAnyDensity, mResizeable, mSmallScreens, mNormalScreens, mLargeScreens);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            boolean alreadyOutputSomething = false;

            if (Boolean.TRUE.equals(mSmallScreens)) {
                alreadyOutputSomething = true;
                sb.append("small");
            }

            if (Boolean.TRUE.equals(mNormalScreens)) {
                if (alreadyOutputSomething) {
                    sb.append(", ");
                }
                alreadyOutputSomething = true;
                sb.append("normal");
            }

            if (Boolean.TRUE.equals(mLargeScreens)) {
                if (alreadyOutputSomething) {
                    sb.append(", ");
                }
                alreadyOutputSomething = true;
                sb.append("large");
            }

            if (alreadyOutputSomething == false) {
                sb.append("<none>");
            }

            return sb.toString();
        }

        /**
         * Returns true if the two instance overlap with each other.
         * This can happen if one instances supports a size, when the other instance doesn't while
         * supporting a size above and a size below.
         * @param otherSS the other supports-screens to compare to.
         */
        public boolean overlapWith(SupportsScreens otherSS) {
            if (mSmallScreens == null || mNormalScreens == null || mLargeScreens == null ||
                    otherSS.mSmallScreens == null || otherSS.mNormalScreens == null ||
                    otherSS.mLargeScreens == null) {
                throw new IllegalArgumentException("Some screen sizes Boolean are not initialized");
            }

            if (mSmallScreens == Boolean.TRUE && mNormalScreens == Boolean.FALSE &&
                    mLargeScreens == Boolean.TRUE) {
                return otherSS.mNormalScreens == Boolean.TRUE;
            }

            if (otherSS.mSmallScreens == Boolean.TRUE && otherSS.mNormalScreens == Boolean.FALSE &&
                    otherSS.mLargeScreens == Boolean.TRUE) {
                return mNormalScreens == Boolean.TRUE;
            }

            return false;
        }
    }

    /**
     * Class representing a <code>uses-library</code> node in the manifest.
     */
    public static final class UsesLibrary {
        String mName;
        Boolean mRequired = Boolean.TRUE; // default is true even if missing

        public String getName() {
            return mName;
        }

        public Boolean getRequired() {
            return mRequired;
        }
    }

    /**
     * Class representing a <code>uses-feature</code> node in the manifest.
     */
    public static final class UsesFeature {
        String mName;
        int mGlEsVersion = 0;
        Boolean mRequired = Boolean.TRUE;  // default is true even if missing

        public String getName() {
            return mName;
        }

        /**
         * Returns the value of the glEsVersion attribute, or 0 if the attribute was not present.
         */
        public int getGlEsVersion() {
            return mGlEsVersion;
        }

        public Boolean getRequired() {
            return mRequired;
        }
    }

    /**
     * Class representing the <code>uses-configuration</code> node in the manifest.
     */
    public static final class UsesConfiguration {
        Boolean mReqFiveWayNav;
        Boolean mReqHardKeyboard;
        Keyboard mReqKeyboardType;
        TouchScreen mReqTouchScreen;
        Navigation mReqNavigation;

        /**
         * returns the value of the <code>reqFiveWayNav</code> attribute or null if not present.
         */
        public Boolean getReqFiveWayNav() {
            return mReqFiveWayNav;
        }

        /**
         * returns the value of the <code>reqNavigation</code> attribute or null if not present.
         */
        public Navigation getReqNavigation() {
            return mReqNavigation;
        }

        /**
         * returns the value of the <code>reqHardKeyboard</code> attribute or null if not present.
         */
        public Boolean getReqHardKeyboard() {
            return mReqHardKeyboard;
        }

        /**
         * returns the value of the <code>reqKeyboardType</code> attribute or null if not present.
         */
        public Keyboard getReqKeyboardType() {
            return mReqKeyboardType;
        }

        /**
         * returns the value of the <code>reqTouchScreen</code> attribute or null if not present.
         */
        public TouchScreen getReqTouchScreen() {
            return mReqTouchScreen;
        }
    }

    /**
     * Returns the package defined in the manifest, if found.
     * @return The package name or null if not found.
     */
    public String getPackage() {
        return mPackage;
    }

    /**
     * Returns the versionCode value defined in the manifest, if found, null otherwise.
     * @return the versionCode or null if not found.
     */
    public Integer getVersionCode() {
        return mVersionCode;
    }

    /**
     * Returns the versionName value defined in the manifest, if found, null otherwise.
     *
     * @return the versionName or null if not found.
     */
    public String getVersionName() {
        return mVersionName;
    }

    /**
     * Returns the list of activities found in the manifest.
     * @return An array of fully qualified class names, or empty if no activity were found.
     */
    public Activity[] getActivities() {
        return mActivities.toArray(new Activity[0]);
    }

    /**
     * Returns the list of activities, services, receivers and providers found in the manifest.
     *
     * @return An array of fully qualified class names, or empty if no classes to keep were found.
     */
    public KeepClass[] getKeepClasses() {
        return mKeepClasses.toArray(new KeepClass[0]);
    }

    /**
     * Returns the name of one activity found in the manifest, that is configured to show up in the
     * HOME screen.
     *
     * @return the fully qualified name of a HOME activity or null if none were found.
     */
    public Activity getLauncherActivity() {
        return mLauncherActivity;
    }

    /**
     * Returns the list of process names declared by the manifest.
     */
    public String[] getProcesses() {
        if (mProcesses != null) {
            return mProcesses.toArray(new String[0]);
        }

        return new String[0];
    }

    @Nullable
    public String getDefaultProcess() {
        return mDefaultProcess;
    }

    /**
     * Returns the <code>debuggable</code> attribute value or null if it is not set.
     */
    public Boolean getDebuggable() {
        return mDebuggable;
    }

    /**
     * Returns the <code>minSdkVersion</code> attribute, or null if it's not set.
     */
    public String getMinSdkVersionString() {
        return mMinSdkVersionString;
    }

    /**
     * Sets the value of the <code>minSdkVersion</code> attribute.
     * @param minSdkVersion the string value of the attribute in the manifest.
     */
    public void setMinSdkVersionString(String minSdkVersion) {
        mMinSdkVersionString = minSdkVersion;
        if (mMinSdkVersionString != null) {
            try {
                mMinSdkVersion = Integer.parseInt(mMinSdkVersionString);
            } catch (NumberFormatException e) {
                mMinSdkVersion = MIN_SDK_CODENAME;
            }
        }
    }

    /**
     * Returns the <code>minSdkVersion</code> attribute, or 0 if it's not set or is a codename.
     * @see #getMinSdkVersionString()
     */
    public int getMinSdkVersion() {
        return mMinSdkVersion;
    }


    /**
     * Sets the value of the <code>minSdkVersion</code> attribute.
     * @param targetSdkVersion the string value of the attribute in the manifest.
     */
    public void setTargetSdkVersionString(String targetSdkVersion) {
        if (targetSdkVersion != null) {
            try {
                mTargetSdkVersion = Integer.parseInt(targetSdkVersion);
            } catch (NumberFormatException e) {
                // keep the value at 0.
            }
        }
    }

    /**
     * Returns the <code>targetSdkVersion</code> attribute, or the same value as
     * {@link #getMinSdkVersion()} if it was not set in the manifest.
     */
    public int getTargetSdkVersion() {
        if (mTargetSdkVersion == 0) {
            return getMinSdkVersion();
        }

        return mTargetSdkVersion;
    }

    /**
     * Returns the list of instrumentations found in the manifest.
     * @return An array of {@link Instrumentation}, or empty if no instrumentations were
     * found.
     */
    public Instrumentation[] getInstrumentations() {
        return mInstrumentations.toArray(new Instrumentation[0]);
    }

    /**
     * Returns the list of libraries in use found in the manifest.
     * @return An array of {@link UsesLibrary} objects, or empty if no libraries were found.
     */
    public UsesLibrary[] getUsesLibraries() {
        return mLibraries.toArray(new UsesLibrary[0]);
    }

    /**
     * Returns the list of features in use found in the manifest.
     * @return An array of {@link UsesFeature} objects, or empty if no libraries were found.
     */
    public UsesFeature[] getUsesFeatures() {
        return mFeatures.toArray(new UsesFeature[0]);
    }

    /** Returns the set of custom permissions declared in the manifest. */
    public ImmutableList<String> getCustomPermissions() {
        return ImmutableList.copyOf(mCustomPermissions);
    }

    /**
     * Returns the glEsVersion from a {@code <uses-feature>} or {@link #GL_ES_VERSION_NOT_SET}
     * if not set.
     */
    public int getGlEsVersion() {
        for (UsesFeature feature : mFeatures) {
            if (feature.mGlEsVersion > 0) {
                return feature.mGlEsVersion;
            }
        }
        return GL_ES_VERSION_NOT_SET;
    }

    /**
     * Returns the {@link SupportsScreens} object representing the <code>supports-screens</code>
     * node, or null if the node doesn't exist at all.
     * Some values in the {@link SupportsScreens} instance maybe null, indicating that they
     * were not present in the manifest. To get an instance that contains the values, as seen
     * by the Android platform when the app is running, use {@link #getSupportsScreensValues()}.
     */
    public SupportsScreens getSupportsScreensFromManifest() {
        return mSupportsScreensFromManifest;
    }

    /**
     * Returns an always non-null instance of {@link SupportsScreens} that's been initialized with
     * the default values, and the values from the manifest.
     * The default values depends on the manifest values for minSdkVersion and targetSdkVersion.
     */
    public synchronized SupportsScreens getSupportsScreensValues() {
        if (mSupportsScreensValues == null) {
            if (mSupportsScreensFromManifest == null) {
                mSupportsScreensValues = SupportsScreens.getDefaultValues(getTargetSdkVersion());
            } else {
                // get a SupportsScreen that replace the missing values with default values.
                mSupportsScreensValues = mSupportsScreensFromManifest.resolveSupportsScreensValues(
                        getTargetSdkVersion());
            }
        }

        return mSupportsScreensValues;
    }

    /**
     * Returns the {@link UsesConfiguration} object representing the <code>uses-configuration</code>
     * node, or null if the node doesn't exist at all.
     */
    public UsesConfiguration getUsesConfiguration() {
        return mUsesConfiguration;
    }

    void addProcessName(String processName) {
        if (mProcesses == null) {
            mProcesses = new TreeSet<String>();
        }

        if (processName.startsWith(":")) {
            mProcesses.add(mPackage + processName);
        } else {
            mProcesses.add(processName);
        }
    }

}
