package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;

/**
 * A locale qualifier, which can be constructed from:
 * <ul>
 *     <li>A plain 2-letter language descriptor</li>
 *     <li>A 2-letter language descriptor followed by a -r 2 letter region qualifier</li>
 *     <li>A plain 3-letter language descriptor</li>
 *     <li>A 3-letter language descriptor followed by a -r 2 letter region qualifier</li>
 *     <li>A BCP 47 language tag. The BCP-47 tag uses + instead of - as separators,
 *         and has the prefix b+. Therefore, the BCP-47 tag "zh-Hans-CN" would be
 *         written as "b+zh+Hans+CN" instead.</li>
 * </ul>
 */
public final class LocaleQualifier extends ResourceQualifier {
    public static final String FAKE_VALUE = "__"; //$NON-NLS-1$
    public static final String NAME = "Locale";
    // TODO: Case insensitive check!
    public static final String BCP_47_PREFIX = "b+"; //$NON-NLS-1$

    /**
     * Old historical value for car dock mode that conflicts with the Carib language. The latter is
     * therefore, excluded from Android.
     */
    private static final String CAR_DOCK_MODE = "car";

    @NotNull private String mFull;
    @Nullable private String mLanguage;
    @Nullable private String mRegion;
    @Nullable private String mScript;

    public LocaleQualifier() {
        mFull = "";
    }

    public LocaleQualifier(@NotNull String language) {
        assert language.length() == 2 || language.length() == 3;
        mLanguage = language;
        mFull = language;
    }

    public LocaleQualifier(@Nullable String full, @NotNull String language,
                           @Nullable String region, @Nullable String script) {
        if (full == null) {
            if (region != null && region.length() == 3 || script != null) {
                StringBuilder sb = new StringBuilder(BCP_47_PREFIX);
                sb.append(language);
                if (region != null) {
                    sb.append('+');
                    sb.append(region);
                }
                if (script != null) {
                    sb.append('+');
                    sb.append(script);
                }
                full = sb.toString();
            } else if (region != null) {
                full = language + "-r" + region;
            } else {
                full = language;
            }
        }
        mFull = full;
        mLanguage = language;
        mRegion = region;
        mScript = script;
    }

    public static boolean isRegionSegment(@NotNull String segment) {
        return (segment.startsWith("r") || segment.startsWith("R")) && segment.length() == 3
                && Character.isLetter(segment.charAt(0)) && Character.isLetter(segment.charAt(1));
    }

    /**
     * Checks whether a string is a valid 2 letter language code in ISO 639-1 style.
     * @param str the string to check
     * @return is it valid?
     */
    private static boolean isValidAlpha2Code(@NotNull String str) {
        return str.length() == 2 && CharMatcher.javaLetter().matchesAllOf(str);
    }

    /**
     * Checks whether a string is a valid 3 letter language code in ISO 639-2 / ISO 639-5 style.
     * @param str the string to check
     * @return is it valid?
     */
    private static boolean isValidAlpha3Code(@NotNull String str) {
        return str.length() == 3 && CharMatcher.javaLetter().matchesAllOf(str);
    }

    /**
     * Checks whether a string is a valid 3 digit in the UN M.49 style.
     * @param str the string to check
     * @return is it valid?
     */
    private static boolean isValidM49Code(@NotNull String str) {
        return str.length() == 3 && Character.isDigit(str.charAt(0))
                && Character.isDigit(str.charAt(1))&& Character.isDigit(str.charAt(2));
    }

    /**
     * Creates and returns a qualifier from the given folder segment. If the segment is incorrect,
     * <code>null</code> is returned.
     * @param segment the folder segment from which to create a qualifier.
     * @return a new {@link LocaleQualifier} object or <code>null</code>
     */
    @Nullable
    public static LocaleQualifier getQualifier(@NotNull String segment) {
        /*
         * Special case: "car" is a valid 3 letter language code (Carib language), but
         * it conflicts with the (much older) UI mode constant for
         * car dock mode, so this specific language string should not be recognized
         * as a 3 letter language string; it should match car dock mode instead.
         */
        if (CAR_DOCK_MODE.equals(segment.toLowerCase(Locale.US))) {
            return null;
        }

        /*
         * If segment starts with "b+" then it is a BCP-47 locale.
         */
        if (segment.startsWith(BCP_47_PREFIX)) {
            return parseBcp47(segment);
        }

        String[] components = segment.split("-");
        if (components.length > 2) {
            /*
             * More than 2 components is not supported. If complex stuff is needed, BCP-47 should
             * be used instead.
             */
            return null;
        }

        /*
         * First component: language. Has to be lower case, either 2 character ISO 639-1
         * or three character ISO 639-2 / ISO 639-5, e.g., en, pt or eng, por...
         */
        String language = components[0].toLowerCase(Locale.US);
        if (!isValidAlpha2Code(language) && !isValidAlpha3Code(language)) {
            return null;
        }

        /*
         * Second component (optional): region. Can be any 2 letters from ISO 3166-1, or 3 digits
         * from UN M.49. E.g., UK, PT, or 013, 151. The second component must be prefixed with
         * either lowercase or uppercase "r", so the code would be en-rUK or pt-RPT.
         */
        String region = null;
        if (components.length > 1) {
            if (components[1].length() < 1
                    || Character.toLowerCase(components[1].charAt(0)) != 'r') {
                return null;
            }

            region = components[1].substring(1);
            if (!isValidAlpha2Code(region) && !isValidM49Code(region)) {
                return null;
            }
        }

        if (region == null) {
            return new LocaleQualifier(language, language, null, null);
        } else {
            return new LocaleQualifier(language + "-r" + region, language, region, null);
        }
    }

    /** Given a BCP-47 string, normalizes the case to the recommended casing */
    @NotNull
    public static String normalizeCase(@NotNull String segment) {
        /* According to the BCP-47 spec:
           o  [ISO639-1] recommends that language codes be written in lowercase
              ('mn' Mongolian).

           o  [ISO15924] recommends that script codes use lowercase with the
              initial letter capitalized ('Cyrl' Cyrillic).

           o  [ISO3166-1] recommends that country codes be capitalized ('MN'
              Mongolia).


           An implementation can reproduce this format without accessing the
           registry as follows.  All subtags, including extension and private
           use subtags, use lowercase letters with two exceptions: two-letter
           and four-letter subtags that neither appear at the start of the tag
           nor occur after singletons.  Such two-letter subtags are all
           uppercase (as in the tags "en-CA-x-ca" or "sgn-BE-FR") and four-
           letter subtags are titlecase (as in the tag "az-Latn-x-latn").
         */
        if (isNormalizedCase(segment)) {
            return segment;
        }

        StringBuilder sb = new StringBuilder(segment.length());
        if (segment.startsWith(BCP_47_PREFIX)) {
            sb.append(BCP_47_PREFIX);
            assert segment.startsWith(BCP_47_PREFIX);
            int segmentBegin = BCP_47_PREFIX.length();
            int segmentLength = segment.length();
            int start = segmentBegin;

            int lastLength = -1;
            while (start < segmentLength) {
                if (start != segmentBegin) {
                    sb.append('+');
                }
                int end = segment.indexOf('+', start);
                if (end == -1) {
                    end = segmentLength;
                }
                int length = end - start;
                if ((length != 2 && length != 4) || start == segmentBegin || lastLength == 1) {
                    for (int i = start; i < end; i++) {
                        sb.append(Character.toLowerCase(segment.charAt(i)));
                    }
                } else if (length == 2) {
                    for (int i = start; i < end; i++) {
                        sb.append(Character.toUpperCase(segment.charAt(i)));
                    }
                } else {
                    assert length == 4 : length;
                    sb.append(Character.toUpperCase(segment.charAt(start)));
                    for (int i = start + 1; i < end; i++) {
                        sb.append(Character.toLowerCase(segment.charAt(i)));
                    }
                }

                lastLength = length;
                start = end + 1;
            }
        } else if (segment.length() == 6) {
            // Language + region: ll-rRR
            sb.append(Character.toLowerCase(segment.charAt(0)));
            sb.append(Character.toLowerCase(segment.charAt(1)));
            sb.append(segment.charAt(2)); // -
            sb.append(Character.toLowerCase(segment.charAt(3))); // r
            sb.append(Character.toUpperCase(segment.charAt(4)));
            sb.append(Character.toUpperCase(segment.charAt(5)));
        } else if (segment.length() == 7) {
            // Language + region: lll-rRR
            sb.append(Character.toLowerCase(segment.charAt(0)));
            sb.append(Character.toLowerCase(segment.charAt(1)));
            sb.append(Character.toLowerCase(segment.charAt(2)));
            sb.append(segment.charAt(3)); // -
            sb.append(Character.toLowerCase(segment.charAt(4))); // r
            sb.append(Character.toUpperCase(segment.charAt(5)));
            sb.append(Character.toUpperCase(segment.charAt(6)));
        } else {
            sb.append(segment.toLowerCase(Locale.US));
        }

        return sb.toString();
    }

    /**
     * Given a BCP-47 string, determines whether the string is already
     * capitalized correctly (where "correct" means for readability; all strings
     * should be compared case insensitively)
     */
    @VisibleForTesting
    static boolean isNormalizedCase(@NotNull String segment) {
        if (segment.startsWith(BCP_47_PREFIX)) {
            assert segment.startsWith(BCP_47_PREFIX);
            int segmentBegin = BCP_47_PREFIX.length();
            int segmentLength = segment.length();
            int start = segmentBegin;

            int lastLength = -1;
            while (start < segmentLength) {
                int end = segment.indexOf('+', start);
                if (end == -1) {
                    end = segmentLength;
                }
                int length = end - start;
                if ((length != 2 && length != 4) || start == segmentBegin || lastLength == 1) {
                    if (isNotLowerCase(segment, start, end)) {
                        return false;
                    }
                } else if (length == 2) {
                    if (isNotUpperCase(segment, start, end)) {
                        return false;
                    }
                } else {
                    assert length == 4 : length;
                    if (isNotUpperCase(segment, start, start + 1)) {
                        return false;
                    }
                    if (isNotLowerCase(segment, start + 1, end)) {
                        return false;
                    }
                }

                lastLength = length;
                start = end + 1;
            }

            return true;
        } else if (segment.length() == 2) {
            // Just a language: ll
            return Character.isLowerCase(segment.charAt(0))
                    && Character.isLowerCase(segment.charAt(1));
        } else if (segment.length() == 3) {
            // Just a language: lll
            return Character.isLowerCase(segment.charAt(0))
                    && Character.isLowerCase(segment.charAt(1))
                    && Character.isLowerCase(segment.charAt(2));
        } else if (segment.length() == 6) {
            // Language + region: ll-rRR
            return Character.isLowerCase(segment.charAt(0))
                    && Character.isLowerCase(segment.charAt(1))
                    && Character.isLowerCase(segment.charAt(3))
                    && Character.isUpperCase(segment.charAt(4))
                    && Character.isUpperCase(segment.charAt(5));
        } else if (segment.length() == 7) {
            // Language + region: lll-rRR
            return Character.isLowerCase(segment.charAt(0))
                    && Character.isLowerCase(segment.charAt(1))
                    && Character.isLowerCase(segment.charAt(2))
                    && Character.isLowerCase(segment.charAt(4))
                    && Character.isUpperCase(segment.charAt(5))
                    && Character.isUpperCase(segment.charAt(6));
        }

        return true;
    }

    private static boolean isNotLowerCase(@NotNull String segment, int start, int end) {
        for (int i = start; i < end; i++) {
            if (Character.isUpperCase(segment.charAt(i))) {
                return true;
            }
        }

        return false;
    }

    private static boolean isNotUpperCase(@NotNull String segment, int start, int end) {
        for (int i = start; i < end; i++) {
            if (Character.isLowerCase(segment.charAt(i))) {
                return true;
            }
        }

        return false;
    }

    @NotNull
    public String getValue() {
        return mFull;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getShortName() {
        return NAME;
    }

    @Override
    public int since() {
        // This was added in Lollipop, but you can for example write b+en+US and aapt handles it
        // compatibly so we don't want to normalize this in normalize() to append -v21 etc
        return 1;
    }

    @Override
    public boolean isValid() {
        //noinspection StringEquality
        return mFull != FAKE_VALUE;
    }

    @Override
    public boolean hasFakeValue() {
        //noinspection StringEquality
        return mFull == FAKE_VALUE;
    }

    public boolean hasLanguage() {
        return mLanguage != null && !FAKE_VALUE.equals(mLanguage);
    }

    public boolean hasRegion() {
        return mRegion != null && !FAKE_VALUE.equals(mRegion);
    }

    @Override
    public boolean checkAndSet(@NotNull String value, @NotNull FolderConfiguration config) {
        LocaleQualifier qualifier = getQualifier(value);
        if (qualifier != null) {
            config.setLocaleQualifier(qualifier);
            return true;
        }

        return false;
    }

    /**
     * Used only when constructing the qualifier, don't use after it's been assigned to a
     * {@link FolderConfiguration}.
     */
    void setRegionSegment(@NotNull String segment) {
        assert segment.length() == 3 : segment;
        mRegion = new String(new char[] {
                Character.toUpperCase(segment.charAt(1)),
                Character.toUpperCase(segment.charAt(2))
        });
        mFull = mLanguage + "-r" + mRegion;
    }

    @SuppressWarnings("RedundantIfStatement")
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        LocaleQualifier qualifier = (LocaleQualifier) o;

        if (!mFull.equals(qualifier.mFull)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return mFull.hashCode();
    }

    /**
     * Returns the string used to represent this qualifier in the folder name.
     */
    @Override
    public String getFolderSegment() {
        return mFull;
    }

    /** BCP 47 tag or "language,region", or language */
    @Override
    public String getShortDisplayValue() {
        if (mFull.startsWith(BCP_47_PREFIX)) {
            return mFull;
        } else if (mRegion != null) {
            return mLanguage + ',' + mRegion;
        } else {
            return mLanguage;
        }
    }

    /** Tag: language, or language-region, or BCP-47 tag */
    public String getTag() {
        if (mFull.startsWith(BCP_47_PREFIX)) {
            return mFull.substring(BCP_47_PREFIX.length()).replace('+','-');
        } else if (mRegion != null) {
            return mLanguage + '-' + mRegion;
        } else {
            return mLanguage;
        }
    }

    @Override
    public String getLongDisplayValue() {
        if (mFull.startsWith(BCP_47_PREFIX)) {
            return String.format("Locale %1$s", mFull);
        } else if (mRegion != null) {
            return String.format("Locale %1$s_%2$s", mLanguage, mRegion);
        } else //noinspection StringEquality
            if (mFull != FAKE_VALUE) {
                return String.format("Locale %1$s", mLanguage);
            }

        return ""; //$NON-NLS-1$
    }

    /**
     * Parse an Android BCP-47 string (which differs from BCP-47 in that
     * it has the prefix "b+" and the separator character has been changed from
     * - to +.
     *
     * @param qualifier the folder name to parse
     * @return a {@linkplain LocaleQualifier} holding the language, region and script
     *     or null if not a valid Android BCP 47 tag
     */
    @Nullable
    public static LocaleQualifier parseBcp47(@NotNull String qualifier) {
        if (qualifier.startsWith(BCP_47_PREFIX)) {
            qualifier = normalizeCase(qualifier);
            Iterator<String> iterator = Splitter.on('+').split(qualifier).iterator();
            // Skip b+ prefix, already checked above
            iterator.next();

            if (iterator.hasNext()) {
                String language = iterator.next();
                String region = null;
                String script = null;
                if (language.length() >= 2 && language.length() <= 3) {
                    if (iterator.hasNext()) {
                        String next = iterator.next();
                        if (next.length() == 4) {
                            // Script specified; look for next
                            script = next;
                            if (iterator.hasNext()) {
                                next = iterator.next();
                            }
                        } else if (next.length() >= 5) {
                            // Past region: specifying a variant
                            return new LocaleQualifier(qualifier, language, null, null);
                        }
                        if (next.length() >= 2 && next.length() <= 3) {
                            region = next;
                        }
                    }
                    if (script == null && (region == null || region.length() == 2)
                            && !iterator.hasNext()) {
                        // Switch from BCP 47 syntax to plain
                        qualifier = language.toLowerCase(Locale.US);
                        if (region != null) {
                            qualifier = qualifier + "-r" + region.toUpperCase(Locale.US);
                        }
                    }
                    return new LocaleQualifier(qualifier, language, region, script);
                }
            }
        }

        return null;
    }

    @Nullable
    public String getLanguage() {
        return mLanguage;
    }

    @Nullable
    public String getRegion() {
        return mRegion;
    }

    @Nullable
    public String getScript() {
        return mScript;
    }

    @NotNull
    public String getFull() {
        return mFull;
    }

    @Override
    public boolean isMatchFor(ResourceQualifier qualifier) {
        if (qualifier instanceof LocaleQualifier) {
            LocaleQualifier other = (LocaleQualifier)qualifier;

            if (!Objects.equals(mLanguage, other.mLanguage)) {
                return false;
            }

            if (mRegion != null && other.mRegion != null && !mRegion.equals(other.mRegion)) {
                return false;
            }

            if (mScript != null && other.mScript != null && !mScript.equals(other.mScript)) {
                return false;
            }

            return true;
        }
        return false;
    }
}
