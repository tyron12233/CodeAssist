package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Streams;
import com.tyron.builder.compiler.manifest.SdkConstants;
import com.tyron.builder.compiler.manifest.resources.Density;
import com.tyron.builder.compiler.manifest.resources.ResourceFolderType;
import com.tyron.builder.compiler.manifest.resources.ScreenOrientation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Represents the configuration for Resource Folders. All the properties have a default value which
 * means that the property is not set.
 */
public final class FolderConfiguration implements Comparable<FolderConfiguration>, Serializable {
    @NotNull private static final ResourceQualifier[] DEFAULT_QUALIFIERS;

    /** Splitter which can be used to split qualifiers. */
    public static final Splitter QUALIFIER_SPLITTER = Splitter.on('-');

    private static final int INDEX_COUNTRY_CODE          = 0;
    private static final int INDEX_NETWORK_CODE          = 1;
    private static final int INDEX_LOCALE                = 2;
    private static final int INDEX_LAYOUT_DIR            = 3;
    private static final int INDEX_SMALLEST_SCREEN_WIDTH = 4;
    private static final int INDEX_SCREEN_WIDTH          = 5;
    private static final int INDEX_SCREEN_HEIGHT         = 6;
    private static final int INDEX_SCREEN_LAYOUT_SIZE    = 7;
    private static final int INDEX_SCREEN_RATIO          = 8;
    private static final int INDEX_SCREEN_ROUND          = 9;
    private static final int INDEX_WIDE_COLOR_GAMUT      = 10;
    private static final int INDEX_HIGH_DYNAMIC_RANGE    = 11;
    private static final int INDEX_SCREEN_ORIENTATION    = 12;
    private static final int INDEX_UI_MODE               = 13;
    private static final int INDEX_NIGHT_MODE            = 14;
    private static final int INDEX_PIXEL_DENSITY         = 15;
    private static final int INDEX_TOUCH_TYPE            = 16;
    private static final int INDEX_KEYBOARD_STATE        = 17;
    private static final int INDEX_TEXT_INPUT_METHOD     = 18;
    private static final int INDEX_NAVIGATION_STATE      = 19;
    private static final int INDEX_NAVIGATION_METHOD     = 20;
    private static final int INDEX_SCREEN_DIMENSION      = 21;
    private static final int INDEX_VERSION               = 22;
    private static final int INDEX_COUNT                 = 23;

    private static final ResourceQualifier[] NULL_QUALIFIERS = new ResourceQualifier[INDEX_COUNT];

    private final ResourceQualifier[] mQualifiers;
    @Nullable private String mQualifierString; // Evaluated lazily.

    static {
        // get the default qualifiers.
        FolderConfiguration defaultConfig = createDefault();
        DEFAULT_QUALIFIERS = defaultConfig.mQualifiers;
        for (int i = 0; i < DEFAULT_QUALIFIERS.length; i++) {
            NULL_QUALIFIERS[i] = DEFAULT_QUALIFIERS[i].getNullQualifier();
        }
    }

    public FolderConfiguration() {
        this(NULL_QUALIFIERS);
        mQualifierString = "";
    }

    private FolderConfiguration(@NotNull ResourceQualifier[] qualifiers) {
        mQualifiers = new ResourceQualifier[INDEX_COUNT];
        System.arraycopy(qualifiers, 0, mQualifiers, 0, INDEX_COUNT);
    }

    /**
     * Creates a {@link FolderConfiguration} matching the folder segments.
     *
     * @param folderSegments The segments of the folder name. The first segments should contain
     *     the name of the folder
     * @return a FolderConfiguration object, or null if the folder name isn't valid
     */
    @Nullable
    public static FolderConfiguration getConfig(@NotNull String[] folderSegments) {
        Iterator<String> iterator = Iterators.forArray(folderSegments);
        if (iterator.hasNext()) {
            // Skip the first segment: it should be just the base folder, such as "values" or
            // "layout"
            iterator.next();
        }

        return getConfigFromQualifiers(iterator);
    }

    /**
     * Creates a {@link FolderConfiguration} matching the folder segments.
     *
     * @param folderSegments The segments of the folder name. The first segments should contain
     *     the name of the folder
     * @return a FolderConfiguration object, or null if the folder name isn't valid
     * @see FolderConfiguration#getConfig(String[])
     */
    @Nullable
    public static FolderConfiguration getConfig(@NotNull Iterable<String> folderSegments) {
        Iterator<String> iterator = folderSegments.iterator();
        if (iterator.hasNext()) {
            // Skip the first segment: it should be just the base folder, such as "values" or
            // "layout"
            iterator.next();
        }

        return getConfigFromQualifiers(iterator);
    }

    /**
     * Creates a {@link FolderConfiguration} matching the qualifiers.
     *
     * @param qualifiers the qualifiers.
     * @return a FolderConfiguration object, or null if the folder name isn't valid
     */
    @Nullable
    public static FolderConfiguration getConfigFromQualifiers(
            @NotNull Iterable<String> qualifiers) {
        return getConfigFromQualifiers(qualifiers.iterator());
    }

    /**
     * Creates a {@link FolderConfiguration} matching the qualifiers.
     *
     * @param qualifiers An iterator on the qualifiers.
     * @return a FolderConfiguration object, or null if the folder name isn't valid
     */
    @Nullable
    public static FolderConfiguration getConfigFromQualifiers(
            @NotNull Iterator<String> qualifiers) {
        FolderConfiguration config = new FolderConfiguration();

        // we are going to loop through the segments, and match them with the first
        // available qualifier. If the segment doesn't match we try with the next qualifier.
        // Because the order of the qualifier is fixed, we do not reset the first qualifier
        // after each successful segment.
        // If we run out of qualifier before processing all the segments, we fail.

        int qualifierIndex = 0;
        int qualifierCount = DEFAULT_QUALIFIERS.length;

        /*
            Process a series of qualifiers and parse them into a set of ResourceQualifiers
            in the new folder configuration.

            The basic loop is as follows:

                while (qualifiers.hasNext()) {
                    String seg = qualifiers.next();

                    while (qualifierIndex < qualifierCount &&
                            !DEFAULT_QUALIFIERS[qualifierIndex].checkAndSet(seg, config)) {
                        qualifierIndex++;
                    }

                    // if we reached the end of the qualifier we didn't find a matching qualifier.
                    if (qualifierIndex == qualifierCount) {
                        return null;
                    } else {
                        qualifierIndex++; // already processed this one
                    }
                }

             In other words, we process through the iterable, one segment at a time, and
             for that segment, we iterate up through the qualifiers and ask each one if
             they can handle it (via checkAndSet); if they can, we are done with that segment
             *and* that qualifier, so next time through the segment loop we won't keep retrying
             the same qualifiers. So, we are basically iterating through two lists (segments
             and qualifiers) at the same time).

             However, locales are a special exception to this: we want to combine *two* segments
             into a single qualifier when you specify both a language and a region.
             E.g. for "en-rUS-ldltr" we want a single LocaleQualifier holding both "en" and "rUS"
             and then a LayoutDirectionQualifier for ldltr.

             Therefore, we've unrolled the above loop: we process all identifiers up to
             the locale qualifier index.

             Then, at the locale qualifier index, IF we get a match, we don't increment
             the qualifierIndex: instead, we fetch the next segment, and if it matches
             as a region, we augment the locale qualifier and continue -- otherwise, we
             bail and process the next segment as usual.

             And then we finally iterate through the remaining qualifiers and segments; this
             is basically the first loop again, iterating from the post-locale qualifier
             up to the end.
         */

        if (!qualifiers.hasNext()) {
            return config;
        }

        while (qualifiers.hasNext()) {
            String seg = qualifiers.next();
            if (seg.isEmpty()) {
                return null; // Not a valid folder configuration
            }

            // TODO: Perform case normalization later (on a per qualifier basis)
            seg = seg.toLowerCase(Locale.US); // no-op if string is already in lower case

            while (qualifierIndex < INDEX_LOCALE &&
                    !DEFAULT_QUALIFIERS[qualifierIndex].checkAndSet(seg, config)) {
                qualifierIndex++;
            }

            // if we reached the end of the qualifier we didn't find a matching qualifier.
            if (qualifierIndex == INDEX_LOCALE) {
                // Ready for locale matching now; that requires some special
                // casing described below

                boolean handle = true;
                // Don't need to lowercase; qualifier will normalize case on its own
                if (DEFAULT_QUALIFIERS[qualifierIndex].checkAndSet(seg, config)) {
                    qualifierIndex++;
                    if (qualifiers.hasNext()) {
                        seg = qualifiers.next();
                        if (seg.isEmpty()) {
                            return null; // Not a valid folder configuration
                        }
                        // Is the next qualifier a region? If so, amend the existing
                        // LocaleQualifier
                        if (LocaleQualifier.isRegionSegment(seg)) {
                            LocaleQualifier localeQualifier = config.getLocaleQualifier();
                            assert localeQualifier != null; // because checkAndSet returned true above
                            localeQualifier.setRegionSegment(seg);
                            handle = false;
                        } else {
                            // No, not a region, so perform normal processing
                            seg = seg.toLowerCase(Locale.US); // no-op if string is already in lower case
                        }
                    } else {
                        return config;
                    }
                }

                if (handle) {
                    while (qualifierIndex < qualifierCount &&
                            !DEFAULT_QUALIFIERS[qualifierIndex].checkAndSet(seg, config)) {
                        qualifierIndex++;
                    }

                    // if we reached the end of the qualifier we didn't find a matching qualifier.
                    if (qualifierIndex == qualifierCount) {
                        // Ready for locale matching now; that requires some special
                        // casing described below
                        return null;
                    } else {
                        qualifierIndex++; // already processed this one
                    }
                }

                break;
            } else {
                qualifierIndex++; // already processed this one
            }
        }

        // Same loop as above, but we continue from after the locales
        while (qualifiers.hasNext()) {
            String seg = qualifiers.next();
            if (seg.isEmpty()) {
                return null; // Not a valid folder configuration
            }

            seg = seg.toLowerCase(Locale.US); // no-op if string is already in lower case

            while (qualifierIndex < qualifierCount &&
                    !DEFAULT_QUALIFIERS[qualifierIndex].checkAndSet(seg, config)) {
                qualifierIndex++;
            }

            // if we reached the end of the qualifier we didn't find a matching qualifier.
            if (qualifierIndex == qualifierCount) {
                return null;
            } else {
                qualifierIndex++; // already processed this one
            }
        }

        return config;
    }

    /**
     * Parse a config line returned by 'am get-config' to extra the language configurations.
     *
     * <p>The line should be stripped of the 'config: ' prefix.
     *
     * @param qualifierString the list of dash-separated qualifier
     * @return a list of language-rRegion
     */
    @NotNull
    public static Set<String> getLanguageConfigFromQualifiers(@NotNull String qualifierString) {
        // because the format breaks the normal list of dash-separated qualifiers, we have to
        // process things differently.
        // the string will look like this:
        // [mcc-][mnc-]lang-rRegion[,lang-rRegion[...]][-other-qualifiers[-...]]
        //
        // So the language + region qualifiers are grouped and potentially repeated with comma
        // separation. To solve this we need to remove extraneous qualifiers before and after
        // and then split by comma.

        if (qualifierString.isEmpty()) {
            return ImmutableSet.of();
        }

        // create a folder config to handle the qualifiers.
        final FolderConfiguration config = new FolderConfiguration();

        // search for qualifiers manually
        int start = 0;
        int qualifierIndex = 0;
        boolean stop = false;

        while (qualifierIndex < INDEX_LOCALE && !stop) {
            int end = qualifierString.indexOf('-', start);

            String qualifier =
                    (end == -1)
                            ? qualifierString.substring(start)
                            : qualifierString.substring(start, end);

            // TODO: Perform case normalization later (on a per qualifier basis)
            qualifier = qualifier.toLowerCase(Locale.US);

            while (qualifierIndex < INDEX_LOCALE
                    && !DEFAULT_QUALIFIERS[qualifierIndex].checkAndSet(qualifier, config)) {
                qualifierIndex++;
            }

            if (end == -1) {
                stop = true;
            } else if (qualifierIndex != INDEX_LOCALE) {
                start = end + 1;
            }
        }

        if (stop) {
            // reach end of string before locale, return.
            return ImmutableSet.of();
        }

        // record start of languages.
        int languageStart = start;

        // now do the same backward.
        int end = qualifierString.length() - 1;
        qualifierIndex = INDEX_COUNT - 1;

        while (qualifierIndex > INDEX_LOCALE && !stop) {
            start = qualifierString.lastIndexOf('-', end);

            String qualifier =
                    (start == -1) ? qualifierString : qualifierString.substring(start + 1, end + 1);

            // TODO: Perform case normalization later (on a per qualifier basis)
            qualifier = qualifier.toLowerCase(Locale.US);

            while (qualifierIndex > INDEX_LOCALE
                    && !checkQualifier(config, qualifierIndex, qualifier)) {
                qualifierIndex--;
            }

            if (start == -1) {
                stop = true;
            } else if (qualifierIndex != INDEX_LOCALE) {
                end = start - 1;
            }
        }

        String languages = qualifierString.substring(languageStart, end + 1);

        return Streams.stream(Splitter.on(",").split(languages))
                .map(
                        locale -> {
                            DEFAULT_QUALIFIERS[INDEX_LOCALE].checkAndSet(locale, config);
                            if (config.getLocaleQualifier() != null) {
                                return config.getLocaleQualifier().getLanguage();
                            }
                            return null;
                        })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static boolean checkQualifier(
            @NotNull FolderConfiguration config,
            int qualifierIndex,
            @NotNull String qualifierValue) {
        if (DEFAULT_QUALIFIERS[qualifierIndex].checkAndSet(qualifierValue, config)) {
            return true;
        }

        // account for broken WideGamut/HDR order (b/78136980)

        if (qualifierIndex == INDEX_HIGH_DYNAMIC_RANGE) {
            return DEFAULT_QUALIFIERS[INDEX_WIDE_COLOR_GAMUT].checkAndSet(qualifierValue, config);
        } else if (qualifierIndex == INDEX_WIDE_COLOR_GAMUT) {
            return DEFAULT_QUALIFIERS[INDEX_HIGH_DYNAMIC_RANGE].checkAndSet(qualifierValue, config);
        }

        return false;
    }

    /**
     * Creates a {@link FolderConfiguration} matching the given folder name.
     *
     * @param folderName the folder name
     * @return a FolderConfiguration object, or null if the folder name isn't valid
     */
    @Nullable
    public static FolderConfiguration getConfigForFolder(@NotNull String folderName) {
        return getConfig(QUALIFIER_SPLITTER.split(folderName));
    }

    /**
     * Creates a copy of the given {@link FolderConfiguration}, that can be modified without
     * affecting the original.
     */
    @NotNull
    public static FolderConfiguration copyOf(@NotNull FolderConfiguration original) {
        return new FolderConfiguration(original.mQualifiers);
    }

    /**
     * Creates a {@link FolderConfiguration} matching the given qualifier string
     * (just the qualifiers; e.g. for a folder like "values-en-rUS" this would be "en-rUS").
     *
     * @param qualifierString the qualifier string
     * @return a FolderConfiguration object, or null if the qualifier string isn't valid
     */
    @Nullable
    public static FolderConfiguration getConfigForQualifierString(@NotNull String qualifierString) {
        if (qualifierString.isEmpty()) {
            return new FolderConfiguration();
        } else {
            return getConfigFromQualifiers(QUALIFIER_SPLITTER.split(qualifierString));
        }
    }

    /**
     * Returns the number of {@link ResourceQualifier} that make up a Folder configuration.
     */
    public static int getQualifierCount() {
        return INDEX_COUNT;
    }

    /**
     * Sets the config from the qualifiers of a given <var>config</var>.
     * <p>This is equivalent to <code>set(config, false)</code>
     * @param config the configuration to set
     *
     * @see #set(FolderConfiguration, boolean)
     */
    public void set(@Nullable FolderConfiguration config) {
        set(config, false /*nonFakeValuesOnly*/);
    }

    /**
     * Sets the config from the qualifiers of a given <var>config</var>.
     *
     * @param config the configuration to set
     * @param nonFakeValuesOnly if set to true this ignore qualifiers for which the
     * current value is a fake value.
     *
     * @see ResourceQualifier#hasFakeValue()
     */
    public void set(@Nullable FolderConfiguration config, boolean nonFakeValuesOnly) {
        if (config != null) {
            for (int i = 0; i < INDEX_COUNT; i++) {
                ResourceQualifier q = config.mQualifiers[i];
                if (!nonFakeValuesOnly || q == null || !q.hasFakeValue()) {
                    mQualifiers[i] = q;
                    mQualifierString = null;
                }
            }
        }
    }

    /**
     * Resets the config setting qualifiers at all indices to {@code null}.
     */
    public void reset() {
        System.arraycopy(NULL_QUALIFIERS, 0, mQualifiers, 0, INDEX_COUNT);
        mQualifierString = "";
    }

    /**
     * Removes the qualifiers from the receiver if they are present (and valid)
     * in the given configuration.
     */
    public void substract(@NotNull FolderConfiguration config) {
        for (int i = 0; i < INDEX_COUNT; i++) {
            if (ResourceQualifier.isValid(config.mQualifiers[i])) {
                mQualifiers[i] = NULL_QUALIFIERS[i];
                mQualifierString = null;
            }
        }
    }

    /**
     * Adds the non-qualifiers from the given config.
     * Qualifiers that are null in the given config do not change in the receiver.
     */
    public void add(@NotNull FolderConfiguration config) {
        for (int i = 0; i < INDEX_COUNT; i++) {
            if (config.mQualifiers[i] != NULL_QUALIFIERS[i]) {
                mQualifiers[i] = config.mQualifiers[i];
                mQualifierString = null;
            }
        }
    }

    /**
     * Returns the first invalid qualifier, or <code>null</code> if they are all valid (or if none
     * exists).
     */
    @Nullable
    public ResourceQualifier getInvalidQualifier() {
        for (int i = 0; i < INDEX_COUNT; i++) {
            if (mQualifiers[i] != null && !mQualifiers[i].isValid()) {
                return mQualifiers[i];
            }
        }

        // All allocated qualifiers are valid, we return null.
        return null;
    }

    /**
     * Adds a qualifier to the {@link FolderConfiguration}
     * @param qualifier the {@link ResourceQualifier} to add.
     */
    public void addQualifier(@Nullable ResourceQualifier qualifier) {
        if (qualifier instanceof CountryCodeQualifier) {
            mQualifiers[INDEX_COUNTRY_CODE] = qualifier;
        } else if (qualifier instanceof NetworkCodeQualifier) {
            mQualifiers[INDEX_NETWORK_CODE] = qualifier;
        } else if (qualifier instanceof LocaleQualifier) {
            mQualifiers[INDEX_LOCALE] = qualifier;
        } else if (qualifier instanceof LayoutDirectionQualifier) {
            mQualifiers[INDEX_LAYOUT_DIR] = qualifier;
        } else if (qualifier instanceof SmallestScreenWidthQualifier) {
            mQualifiers[INDEX_SMALLEST_SCREEN_WIDTH] = qualifier;
        } else if (qualifier instanceof ScreenWidthQualifier) {
            mQualifiers[INDEX_SCREEN_WIDTH] = qualifier;
        } else if (qualifier instanceof ScreenHeightQualifier) {
            mQualifiers[INDEX_SCREEN_HEIGHT] = qualifier;
        } else if (qualifier instanceof ScreenSizeQualifier) {
            mQualifiers[INDEX_SCREEN_LAYOUT_SIZE] = qualifier;
        } else if (qualifier instanceof ScreenRatioQualifier) {
            mQualifiers[INDEX_SCREEN_RATIO] = qualifier;
        } else if (qualifier instanceof ScreenRoundQualifier) {
            mQualifiers[INDEX_SCREEN_ROUND] = qualifier;
        } else if (qualifier instanceof WideGamutColorQualifier) {
            mQualifiers[INDEX_WIDE_COLOR_GAMUT] = qualifier;
        } else if (qualifier instanceof HighDynamicRangeQualifier) {
            mQualifiers[INDEX_HIGH_DYNAMIC_RANGE] = qualifier;
        } else if (qualifier instanceof ScreenOrientationQualifier) {
            mQualifiers[INDEX_SCREEN_ORIENTATION] = qualifier;
        } else if (qualifier instanceof UiModeQualifier) {
            mQualifiers[INDEX_UI_MODE] = qualifier;
        } else if (qualifier instanceof NightModeQualifier) {
            mQualifiers[INDEX_NIGHT_MODE] = qualifier;
        } else if (qualifier instanceof DensityQualifier) {
            mQualifiers[INDEX_PIXEL_DENSITY] = qualifier;
        } else if (qualifier instanceof TouchScreenQualifier) {
            mQualifiers[INDEX_TOUCH_TYPE] = qualifier;
        } else if (qualifier instanceof KeyboardStateQualifier) {
            mQualifiers[INDEX_KEYBOARD_STATE] = qualifier;
        } else if (qualifier instanceof TextInputMethodQualifier) {
            mQualifiers[INDEX_TEXT_INPUT_METHOD] = qualifier;
        } else if (qualifier instanceof NavigationStateQualifier) {
            mQualifiers[INDEX_NAVIGATION_STATE] = qualifier;
        } else if (qualifier instanceof NavigationMethodQualifier) {
            mQualifiers[INDEX_NAVIGATION_METHOD] = qualifier;
        } else if (qualifier instanceof ScreenDimensionQualifier) {
            mQualifiers[INDEX_SCREEN_DIMENSION] = qualifier;
        } else if (qualifier instanceof VersionQualifier) {
            mQualifiers[INDEX_VERSION] = qualifier;
        }
        mQualifierString = null;
    }

    /**
     * Removes a given qualifier from the {@link FolderConfiguration}.
     * @param qualifier the {@link ResourceQualifier} to remove.
     */
    public void removeQualifier(@NotNull ResourceQualifier qualifier) {
        for (int i = 0; i < INDEX_COUNT; i++) {
            if (mQualifiers[i] == qualifier) {
                mQualifiers[i] = NULL_QUALIFIERS[i];
                mQualifierString = null;
                return;
            }
        }
    }

    /**
     * Returns a qualifier by its index. The total number of qualifiers can be accessed by
     * {@link #getQualifierCount()}.
     * @param index the index of the qualifier to return.
     * @return the qualifier or null if there are none at the index.
     */
    @Nullable
    public ResourceQualifier getQualifier(int index) {
        return mQualifiers[index];
    }

    /** Performs the given action on each non-default qualifier */
    public void forEach(@NotNull Consumer<? super ResourceQualifier> action) {
        for (int i = 0; i < INDEX_COUNT; i++) {
            ResourceQualifier qualifier = mQualifiers[i];
            if (qualifier != null && qualifier != NULL_QUALIFIERS[i]) {
                action.accept(qualifier);
            }
        }
    }

    /** Returns true if the given predicate matches any non-default qualifier */
    public boolean any(Predicate<? super ResourceQualifier> predicate) {
        for (int i = 0; i < INDEX_COUNT; i++) {
            ResourceQualifier qualifier = mQualifiers[i];
            if (qualifier != null && qualifier != NULL_QUALIFIERS[i]) {
                if (predicate.test(qualifier)) {
                    return true;
                }
            }
        }

        return false;
    }

    public void setCountryCodeQualifier(CountryCodeQualifier qualifier) {
        mQualifiers[INDEX_COUNTRY_CODE] = qualifier == null ? NULL_QUALIFIERS[INDEX_COUNTRY_CODE]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public CountryCodeQualifier getCountryCodeQualifier() {
        return (CountryCodeQualifier)mQualifiers[INDEX_COUNTRY_CODE];
    }

    public void setNetworkCodeQualifier(NetworkCodeQualifier qualifier) {
        mQualifiers[INDEX_NETWORK_CODE] = qualifier == null ? NULL_QUALIFIERS[INDEX_NETWORK_CODE]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public NetworkCodeQualifier getNetworkCodeQualifier() {
        return (NetworkCodeQualifier)mQualifiers[INDEX_NETWORK_CODE];
    }

    public void setLocaleQualifier(LocaleQualifier qualifier) {
        mQualifiers[INDEX_LOCALE] = qualifier == null ? NULL_QUALIFIERS[INDEX_LOCALE]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public LocaleQualifier getLocaleQualifier() {
        return (LocaleQualifier)mQualifiers[INDEX_LOCALE];
    }

    public void setLayoutDirectionQualifier(LayoutDirectionQualifier qualifier) {
        mQualifiers[INDEX_LAYOUT_DIR] = qualifier == null ? NULL_QUALIFIERS[INDEX_LAYOUT_DIR]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public LayoutDirectionQualifier getLayoutDirectionQualifier() {
        return (LayoutDirectionQualifier)mQualifiers[INDEX_LAYOUT_DIR];
    }

    public void setSmallestScreenWidthQualifier(SmallestScreenWidthQualifier qualifier) {
        mQualifiers[INDEX_SMALLEST_SCREEN_WIDTH] = qualifier == null ? NULL_QUALIFIERS
                [INDEX_SMALLEST_SCREEN_WIDTH]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public SmallestScreenWidthQualifier getSmallestScreenWidthQualifier() {
        return (SmallestScreenWidthQualifier) mQualifiers[INDEX_SMALLEST_SCREEN_WIDTH];
    }

    public void setScreenWidthQualifier(ScreenWidthQualifier qualifier) {
        mQualifiers[INDEX_SCREEN_WIDTH] = qualifier == null ? NULL_QUALIFIERS[INDEX_SCREEN_WIDTH]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public ScreenWidthQualifier getScreenWidthQualifier() {
        return (ScreenWidthQualifier) mQualifiers[INDEX_SCREEN_WIDTH];
    }

    public void setScreenHeightQualifier(ScreenHeightQualifier qualifier) {
        mQualifiers[INDEX_SCREEN_HEIGHT] = qualifier == null ? NULL_QUALIFIERS[INDEX_SCREEN_HEIGHT]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public ScreenHeightQualifier getScreenHeightQualifier() {
        return (ScreenHeightQualifier) mQualifiers[INDEX_SCREEN_HEIGHT];
    }

    public void setScreenSizeQualifier(ScreenSizeQualifier qualifier) {
        mQualifiers[INDEX_SCREEN_LAYOUT_SIZE] = qualifier == null
                ? NULL_QUALIFIERS[INDEX_SCREEN_LAYOUT_SIZE] : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public ScreenSizeQualifier getScreenSizeQualifier() {
        return (ScreenSizeQualifier)mQualifiers[INDEX_SCREEN_LAYOUT_SIZE];
    }

    public void setScreenRatioQualifier(ScreenRatioQualifier qualifier) {
        mQualifiers[INDEX_SCREEN_RATIO] = qualifier == null ? NULL_QUALIFIERS[INDEX_SCREEN_RATIO]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public ScreenRatioQualifier getScreenRatioQualifier() {
        return (ScreenRatioQualifier)mQualifiers[INDEX_SCREEN_RATIO];
    }

    public void setScreenRoundQualifier(ScreenRoundQualifier qualifier) {
        mQualifiers[INDEX_SCREEN_ROUND] = qualifier == null ? NULL_QUALIFIERS[INDEX_SCREEN_ROUND]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public ScreenRoundQualifier getScreenRoundQualifier() {
        return (ScreenRoundQualifier)mQualifiers[INDEX_SCREEN_ROUND];
    }

    public void setWideColorGamutQualifier(WideGamutColorQualifier qualifier) {
        mQualifiers[INDEX_WIDE_COLOR_GAMUT] =
                qualifier == null ? NULL_QUALIFIERS[INDEX_WIDE_COLOR_GAMUT] : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public WideGamutColorQualifier getWideColorGamutQualifier() {
        return (WideGamutColorQualifier) mQualifiers[INDEX_WIDE_COLOR_GAMUT];
    }

    public void setHighDynamicRangeQualifier(HighDynamicRangeQualifier qualifier) {
        mQualifiers[INDEX_HIGH_DYNAMIC_RANGE] =
                qualifier == null ? NULL_QUALIFIERS[INDEX_HIGH_DYNAMIC_RANGE] : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public HighDynamicRangeQualifier getHighDynamicRangeQualifier() {
        return (HighDynamicRangeQualifier) mQualifiers[INDEX_HIGH_DYNAMIC_RANGE];
    }

    public void setScreenOrientationQualifier(ScreenOrientationQualifier qualifier) {
        mQualifiers[INDEX_SCREEN_ORIENTATION] = qualifier == null
                ? NULL_QUALIFIERS[INDEX_SCREEN_ORIENTATION] : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public ScreenOrientationQualifier getScreenOrientationQualifier() {
        return (ScreenOrientationQualifier)mQualifiers[INDEX_SCREEN_ORIENTATION];
    }

    public void setUiModeQualifier(UiModeQualifier qualifier) {
        mQualifiers[INDEX_UI_MODE] = qualifier == null ? NULL_QUALIFIERS[INDEX_UI_MODE]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public UiModeQualifier getUiModeQualifier() {
        return (UiModeQualifier)mQualifiers[INDEX_UI_MODE];
    }

    public void setNightModeQualifier(NightModeQualifier qualifier) {
        mQualifiers[INDEX_NIGHT_MODE] = qualifier == null ? NULL_QUALIFIERS[INDEX_NIGHT_MODE]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public NightModeQualifier getNightModeQualifier() {
        return (NightModeQualifier)mQualifiers[INDEX_NIGHT_MODE];
    }

    public void setDensityQualifier(DensityQualifier qualifier) {
        mQualifiers[INDEX_PIXEL_DENSITY] = qualifier == null ? NULL_QUALIFIERS[INDEX_PIXEL_DENSITY]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public DensityQualifier getDensityQualifier() {
        return (DensityQualifier)mQualifiers[INDEX_PIXEL_DENSITY];
    }

    public void setTouchTypeQualifier(TouchScreenQualifier qualifier) {
        mQualifiers[INDEX_TOUCH_TYPE] = qualifier == null ? NULL_QUALIFIERS[INDEX_TOUCH_TYPE]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public TouchScreenQualifier getTouchTypeQualifier() {
        return (TouchScreenQualifier)mQualifiers[INDEX_TOUCH_TYPE];
    }

    public void setKeyboardStateQualifier(KeyboardStateQualifier qualifier) {
        mQualifiers[INDEX_KEYBOARD_STATE] = qualifier == null ? NULL_QUALIFIERS[INDEX_KEYBOARD_STATE]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public KeyboardStateQualifier getKeyboardStateQualifier() {
        return (KeyboardStateQualifier)mQualifiers[INDEX_KEYBOARD_STATE];
    }

    public void setTextInputMethodQualifier(TextInputMethodQualifier qualifier) {
        mQualifiers[INDEX_TEXT_INPUT_METHOD] = qualifier == null ? NULL_QUALIFIERS[INDEX_TEXT_INPUT_METHOD]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public TextInputMethodQualifier getTextInputMethodQualifier() {
        return (TextInputMethodQualifier)mQualifiers[INDEX_TEXT_INPUT_METHOD];
    }

    public void setNavigationStateQualifier(NavigationStateQualifier qualifier) {
        mQualifiers[INDEX_NAVIGATION_STATE] = qualifier == null
                ? NULL_QUALIFIERS[INDEX_NAVIGATION_STATE] : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public NavigationStateQualifier getNavigationStateQualifier() {
        return (NavigationStateQualifier)mQualifiers[INDEX_NAVIGATION_STATE];
    }

    public void setNavigationMethodQualifier(NavigationMethodQualifier qualifier) {
        mQualifiers[INDEX_NAVIGATION_METHOD] = qualifier == null
                ? NULL_QUALIFIERS[INDEX_NAVIGATION_METHOD] : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public NavigationMethodQualifier getNavigationMethodQualifier() {
        return (NavigationMethodQualifier)mQualifiers[INDEX_NAVIGATION_METHOD];
    }

    public void setScreenDimensionQualifier(ScreenDimensionQualifier qualifier) {
        mQualifiers[INDEX_SCREEN_DIMENSION] = qualifier == null
                ? NULL_QUALIFIERS[INDEX_SCREEN_DIMENSION] : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public ScreenDimensionQualifier getScreenDimensionQualifier() {
        return (ScreenDimensionQualifier)mQualifiers[INDEX_SCREEN_DIMENSION];
    }

    public void setVersionQualifier(@Nullable VersionQualifier qualifier) {
        mQualifiers[INDEX_VERSION] = qualifier == null ? NULL_QUALIFIERS[INDEX_VERSION]
                : qualifier;
        mQualifierString = null;
    }

    @Nullable
    public VersionQualifier getVersionQualifier() {
        return (VersionQualifier) mQualifiers[INDEX_VERSION];
    }

    /**
     * Normalizes this folder configuration by adding a version qualifier corresponding to the API
     * level implied by other qualifiers. See also
     * {@link #normalizeByRemovingRedundantVersionQualifier()}.
     */
    public void normalizeByAddingImpliedVersionQualifier() {
        int minSdk = 1;
        for (int i = 0; i < mQualifiers.length; i++) {
            ResourceQualifier qualifier = mQualifiers[i];
            if (qualifier != NULL_QUALIFIERS[i]) {
                int min = qualifier.since();
                if (min > minSdk) {
                    minSdk = min;
                }
            }
        }

        if (minSdk == 1) {
            return;
        }

        if (mQualifiers[INDEX_VERSION] == NULL_QUALIFIERS[INDEX_VERSION]
                || ((VersionQualifier) mQualifiers[INDEX_VERSION]).getVersion() < minSdk) {
            setVersionQualifier(new VersionQualifier(minSdk));
        }
    }

    /**
     * Normalizes this folder configuration by removing a redundant version qualifier. A version
     * qualifier is redundant if it is implied by other qualifiers. See also
     * {@link #normalizeByAddingImpliedVersionQualifier()}.
     */
    public void normalizeByRemovingRedundantVersionQualifier() {
        VersionQualifier versionQualifier = getVersionQualifier();
        if (versionQualifier == NULL_QUALIFIERS[INDEX_VERSION]) {
            return;
        }

        int version = versionQualifier.getVersion();
        if (version == 1) {
            setVersionQualifier(null);
            return;
        }

        for (int i = 0; i < mQualifiers.length; i++) {
            if (i != INDEX_VERSION) {
                ResourceQualifier qualifier = mQualifiers[i];
                if (qualifier != NULL_QUALIFIERS[i] && qualifier.since() >= version) {
                    setVersionQualifier(null);
                    break;
                }
            }
        }
    }

    /**
     * Updates the {@link SmallestScreenWidthQualifier}, {@link ScreenWidthQualifier}, and
     * {@link ScreenHeightQualifier} based on the (required) values of
     * {@link ScreenDimensionQualifier} {@link DensityQualifier}, and
     * {@link ScreenOrientationQualifier}.
     *
     * Also the density cannot be {@link Density#NODPI} as it's not valid on a device.
     */
    public void updateScreenWidthAndHeight() {
        ResourceQualifier sizeQ = mQualifiers[INDEX_SCREEN_DIMENSION];
        ResourceQualifier densityQ = mQualifiers[INDEX_PIXEL_DENSITY];
        ResourceQualifier orientQ = mQualifiers[INDEX_SCREEN_ORIENTATION];

        if (sizeQ != NULL_QUALIFIERS[INDEX_SCREEN_DIMENSION]
                && densityQ != NULL_QUALIFIERS[INDEX_PIXEL_DENSITY]
                && orientQ != NULL_QUALIFIERS[INDEX_SCREEN_ORIENTATION]) {
            Density density = ((DensityQualifier) densityQ).getValue();
            if (density == Density.NODPI || density == Density.ANYDPI) {
                return;
            }

            ScreenOrientation orientation = ((ScreenOrientationQualifier) orientQ).getValue();

            int size1 = ((ScreenDimensionQualifier) sizeQ).getValue1();
            int size2 = ((ScreenDimensionQualifier) sizeQ).getValue2();

            // Make sure size1 is the biggest (should be the case, but make sure).
            if (size1 < size2) {
                int a = size1;
                size1 = size2;
                size2 = a;
            }

            // Compute the dp. round them up since we want -w480dp to match a 480.5dp screen.
            assert density != null;
            int dp1 = divideWithRoundingUp(size1 * Density.DEFAULT_DENSITY, density.getDpiValue());
            int dp2 = divideWithRoundingUp(size2 * Density.DEFAULT_DENSITY, density.getDpiValue());

            setSmallestScreenWidthQualifier(new SmallestScreenWidthQualifier(dp2));

            switch (orientation) {
                case PORTRAIT:
                    setScreenWidthQualifier(new ScreenWidthQualifier(dp2));
                    setScreenHeightQualifier(new ScreenHeightQualifier(dp1));
                    break;
                case LANDSCAPE:
                    setScreenWidthQualifier(new ScreenWidthQualifier(dp1));
                    setScreenHeightQualifier(new ScreenHeightQualifier(dp2));
                    break;
                case SQUARE:
                    setScreenWidthQualifier(new ScreenWidthQualifier(dp2));
                    setScreenHeightQualifier(new ScreenHeightQualifier(dp2));
                    break;
            }
        }
    }

    private static int divideWithRoundingUp(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (!(obj instanceof FolderConfiguration)) {
            return false;
        }

        FolderConfiguration fc = (FolderConfiguration)obj;
        for (int i = 0; i < INDEX_COUNT; i++) {
            ResourceQualifier qualifier = mQualifiers[i];
            ResourceQualifier fcQualifier = fc.mQualifiers[i];
            if (!Objects.equals(qualifier, fcQualifier)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        return getQualifierString().hashCode();
    }

    /**
     * Returns whether the configuration has only default values.
     */
    public boolean isDefault() {
        return getQualifierString().isEmpty();
    }

    /**
     * Returns the name of a folder with the configuration.
     */
    @NotNull
    public String getFolderName(@NotNull ResourceFolderType folder) {
        StringBuilder result = new StringBuilder(folder.getName());

        for (int i = 0; i < mQualifiers.length; i++) {
            ResourceQualifier qualifier = mQualifiers[i];
            if (qualifier != NULL_QUALIFIERS[i]) {
                String segment = qualifier.getFolderSegment();
                if (segment != null && !segment.isEmpty()) {
                    result.append(SdkConstants.RES_QUALIFIER_SEP);
                    result.append(segment);
                }
            }
        }

        return result.toString();
    }

    /**
     * Returns the folder configuration as a qualifier string, e.g.
     * for the folder values-en-rUS this returns "en-rUS". For the
     * default configuration it returns "".
     */
    @NotNull
    public String getQualifierString() {
        if (mQualifierString == null) {
            StringBuilder result = null;
            for (int i = 0; i < mQualifiers.length; i++) {
                ResourceQualifier qualifier = mQualifiers[i];
                if (qualifier != NULL_QUALIFIERS[i]) {
                    String segment = qualifier.getFolderSegment();
                    if (segment != null && !segment.isEmpty()) {
                        if (result == null) {
                            result = new StringBuilder(40);
                        } else {
                            result.append(SdkConstants.RES_QUALIFIER_SEP);
                        }
                        result.append(segment);
                    }
                }
            }

            mQualifierString = result == null ? "" : result.toString();
        }
        return mQualifierString;
    }

    /**
     * Returns {@link #toDisplayString()}.
     */
    @Override
    @NotNull
    public String toString() {
        return toDisplayString();
    }

    /**
     * Returns a string valid for display purpose.
     */
    @NotNull
    public String toDisplayString() {
        if (isDefault()) {
            return "default";
        }

        StringBuilder result = null;
        int index = 0;

        while (index < INDEX_COUNT) {
            ResourceQualifier qualifier = mQualifiers[index];
            if (qualifier != NULL_QUALIFIERS[index++]) {
                if (result == null) {
                    result = new StringBuilder();
                } else {
                    result.append(", "); //$NON-NLS-1$
                }
                result.append(qualifier.getLongDisplayValue());

            }
        }

        return result == null ? "" : result.toString();
    }

    /**
     * Returns a string for display purposes which uses only the short names of the qualifiers
     */
    @NotNull
    public String toShortDisplayString() {
        if (isDefault()) {
            return "default";
        }

        StringBuilder result = new StringBuilder(100);
        int index = 0;

        while (index < INDEX_COUNT) {
            ResourceQualifier qualifier = mQualifiers[index];
            if (qualifier != NULL_QUALIFIERS[index++]) {
                if (result.length() > 0) {
                    result.append(',');
                }
                result.append(qualifier.getShortDisplayValue());
            }
        }

        return result.toString();
    }

    @Override
    public int compareTo(@NotNull FolderConfiguration folderConfig) {
        // Default are always at the top.
        if (isDefault()) {
            if (folderConfig.isDefault()) {
                return 0;
            }
            return -1;
        }

        // Now we compare the qualifiers.
        for (int i = 0; i < INDEX_COUNT; i++) {
            ResourceQualifier qualifier1 = mQualifiers[i];
            ResourceQualifier qualifier2 = folderConfig.mQualifiers[i];
            if (Objects.equals(qualifier1, qualifier2)) {
                continue;
            }
            if (qualifier1 != null && qualifier2 != null) {
                return qualifier1.compareTo(qualifier2);
            }
            return qualifier1 == NULL_QUALIFIERS[i] ? -1 : 1;
        }

        // If we arrive here, all the qualifiers match.
        return 0;
    }

    /**
     * Returns the best matching {@link Configurable} for this configuration.
     *
     * @param configurables the list of {@link Configurable} to choose from
     * @return an item from the given list of {@link Configurable} or null
     * @see "http://d.android.com/guide/topics/resources/resources-i18n.html#best-match"
     */
    @Nullable
    public <T extends Configurable> T findMatchingConfigurable(
            @Nullable Collection<T> configurables) {
        // Because we skip qualifiers where reference configuration doesn't have a valid qualifier,
        // we can end up with more than one match. In this case, we just take the first one.
        List<T> matches = findMatchingConfigurables(configurables);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Tries to eliminate as many {@link Configurable}s as possible. It skips the
     * {@link ResourceQualifier} if it's not valid and assumes that all resources match it.
     *
     * @param configurables the list of {@code Configurable} to choose from.
     * @return a list of items from the above list. This may be empty.
     */
    @NotNull
    public <T extends Configurable> List<T> findMatchingConfigurables(
            @Nullable Collection<T> configurables) {
        if (configurables == null) {
            return Collections.emptyList();
        }

        //
        // 1: Eliminate resources that contradict the reference configuration.
        // 2: Pick next qualifier type.
        // 3: Check if any resources use this qualifier, if no, back to 2, else move on to 4.
        // 4: Eliminate resources that don't use this qualifier.
        // 5: If more than one resource left, go back to 2.
        //
        // The precedence of the qualifiers is more important than the number of qualifiers that
        // exactly match the device.

        // 1: Eliminate resources that contradict.
        ArrayList<T> matchingConfigurables = new ArrayList<>();
        for (T res : configurables) {
            FolderConfiguration configuration = res.getConfiguration();
            if (configuration.isMatchFor(this)) {
                matchingConfigurables.add(res);
            }
        }

        // If there is at most one match, just take it.
        if (matchingConfigurables.size() < 2) {
            return matchingConfigurables;
        }

        // 2. Loop on the qualifiers, and eliminate matches.
        int count = getQualifierCount();
        for (int q = 0; q < count; q++) {
            // Look to see if one configurable has this qualifier.
            // At the same time also record the best match value for the qualifier (if applicable).

            // The reference value, to find the best match.
            // Note that this qualifier could be null. In which case any qualifier found in the
            // possible match, will all be considered best match.
            ResourceQualifier referenceQualifier = getQualifier(q);

            // If referenceQualifier is null, we don't eliminate resources based on it.
            if (referenceQualifier == NULL_QUALIFIERS[q] || referenceQualifier == null) {
                continue;
            }

            boolean found = false;
            ResourceQualifier bestMatch = null; // this is to store the best match.
            for (T configurable : matchingConfigurables) {
                ResourceQualifier qualifier = configurable.getConfiguration().getQualifier(q);
                if (qualifier != null) {
                    // Set the flag.
                    found = true;

                    // Now check for a best match. If the reference qualifier is null ,
                    // any qualifier is a "best" match (we don't need to record all of them.
                    // Instead the non compatible ones are removed below)
                    if (qualifier.isBetterMatchThan(bestMatch, referenceQualifier)) {
                        bestMatch = qualifier;
                    }
                }
            }

            // 4. If a configurable has a qualifier at the current index, remove all the ones that
            // do not have one, or whose qualifier value does not equal the best match found above
            // unless there's no reference qualifier, in which case they are all considered
            // "best" match.
            if (found) {
                for (int i = 0; i < matchingConfigurables.size(); ) {
                    T configurable = matchingConfigurables.get(i);
                    FolderConfiguration configuration = configurable.getConfiguration();
                    ResourceQualifier qualifier = configuration.getQualifier(q);

                    if (qualifier == null) {
                        // This resource has no qualifier of this type: rejected.
                        matchingConfigurables.remove(configurable);
                    } else if (bestMatch != null && !bestMatch.equals(qualifier)) {
                        // There's a reference qualifier and there is a better match for it than
                        // this resource, so we reject it.
                        matchingConfigurables.remove(configurable);
                    } else {
                        // Looks like we keep this resource, move on to the next one.
                        //noinspection AssignmentToForLoopParameter
                        i++;
                    }
                }

                // At this point we may have run out of matching resources before going
                // through all the qualifiers.
                if (matchingConfigurables.size() < 2) {
                    break;
                }
            }
        }

        // We've exhausted all the qualifiers. If we still have matching ones left, return all.
        return matchingConfigurables;
    }

    /**
     * Returns whether the configuration is a match for the given reference config.
     *
     * <p>A match means that, for each qualifier of this config
     * <ul>
     *   <li>The reference config has no value set
     *   <li>or, the qualifier of the reference config is a match. Depending on the qualifier type
     *       this does not mean the same exact value.
     * </ul>
     * @param referenceConfig The reference configuration to test against.
     * @return true if the configuration matches.
     */
    public boolean isMatchFor(@Nullable FolderConfiguration referenceConfig) {
        if (referenceConfig == null) {
            return false;
        }

        for (int i = 0; i < INDEX_COUNT; i++) {
            ResourceQualifier testQualifier = mQualifiers[i];
            ResourceQualifier referenceQualifier = referenceConfig.mQualifiers[i];

            // it's only a non match if both qualifiers are non-null, and they don't match.
            if (testQualifier != null
                    && !testQualifier.equals(testQualifier.getNullQualifier())
                    && referenceQualifier != null
                    && !referenceQualifier.equals(referenceQualifier.getNullQualifier())
                    && !testQualifier.isMatchFor(referenceQualifier)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns the index of the first non null {@link ResourceQualifier} starting at index
     * <var>startIndex</var>
     * @return -1 if no qualifier was found.
     */
    public int getHighestPriorityQualifier(int startIndex) {
        for (int i = startIndex; i < INDEX_COUNT; i++) {
            if (mQualifiers[i] != NULL_QUALIFIERS[i]) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Creates a FolderConfiguration with default qualifiers with no values for all indices.
     */
    public static FolderConfiguration createDefault() {
        FolderConfiguration config = new FolderConfiguration();
        config.mQualifiers[INDEX_COUNTRY_CODE] = new CountryCodeQualifier();
        config.mQualifiers[INDEX_NETWORK_CODE] = new NetworkCodeQualifier();
        config.mQualifiers[INDEX_LOCALE] = new LocaleQualifier();
        config.mQualifiers[INDEX_LAYOUT_DIR] = new LayoutDirectionQualifier();
        config.mQualifiers[INDEX_SMALLEST_SCREEN_WIDTH] = new SmallestScreenWidthQualifier();
        config.mQualifiers[INDEX_SCREEN_WIDTH] = new ScreenWidthQualifier();
        config.mQualifiers[INDEX_SCREEN_HEIGHT] = new ScreenHeightQualifier();
        config.mQualifiers[INDEX_SCREEN_LAYOUT_SIZE] = new ScreenSizeQualifier();
        config.mQualifiers[INDEX_SCREEN_RATIO] = new ScreenRatioQualifier();
        config.mQualifiers[INDEX_SCREEN_ROUND] = new ScreenRoundQualifier();
        config.mQualifiers[INDEX_WIDE_COLOR_GAMUT] = new WideGamutColorQualifier();
        config.mQualifiers[INDEX_HIGH_DYNAMIC_RANGE] = new HighDynamicRangeQualifier();
        config.mQualifiers[INDEX_SCREEN_ORIENTATION] = new ScreenOrientationQualifier();
        config.mQualifiers[INDEX_UI_MODE] = new UiModeQualifier();
        config.mQualifiers[INDEX_NIGHT_MODE] = new NightModeQualifier();
        config.mQualifiers[INDEX_PIXEL_DENSITY] = new DensityQualifier();
        config.mQualifiers[INDEX_TOUCH_TYPE] = new TouchScreenQualifier();
        config.mQualifiers[INDEX_KEYBOARD_STATE] = new KeyboardStateQualifier();
        config.mQualifiers[INDEX_TEXT_INPUT_METHOD] = new TextInputMethodQualifier();
        config.mQualifiers[INDEX_NAVIGATION_STATE] = new NavigationStateQualifier();
        config.mQualifiers[INDEX_NAVIGATION_METHOD] = new NavigationMethodQualifier();
        config.mQualifiers[INDEX_SCREEN_DIMENSION] = new ScreenDimensionQualifier();
        config.mQualifiers[INDEX_VERSION] = new VersionQualifier();
        return config;
    }

    /**
     * Returns an array of all the non null qualifiers.
     */
    @NotNull
    public ResourceQualifier[] getQualifiers() {
        int count = 0;
        for (int i = 0; i < INDEX_COUNT; i++) {
            if (mQualifiers[i] != null && mQualifiers[i] != NULL_QUALIFIERS[i]) {
                count++;
            }
        }

        ResourceQualifier[] array = new ResourceQualifier[count];
        int index = 0;
        for (int i = 0; i < INDEX_COUNT; i++) {
            if (mQualifiers[i] != null && mQualifiers[i] != NULL_QUALIFIERS[i]) {
                array[index++] = mQualifiers[i];
            }
        }

        return array;
    }

    /**
     * Returns qualifier of a folder name, e.g. "zh-rHK-watch" for "values-zh-rHK-watch" or an empty
     * string for "drawable".
     */
    @NotNull
    public static String getQualifier(@NotNull String folderName) {
        int dashPos = folderName.indexOf('-');
        return dashPos < 0 ? "" : folderName.substring(dashPos + 1);
    }
}