package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceEnum;
import com.tyron.builder.compiler.manifest.resources.ScreenSize;

/**
 * Resource Qualifier for Screen Size. Size can be "small", "normal", "large" and "x-large"
 */
public class ScreenSizeQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Screen Size";

    /**
     * The qualifier to be used for configurables when there is no qualifier present. This should
     * not be used for the reference configuration.
     */
    private static final ScreenSizeQualifier NULL_QUALIFIER = new ScreenSizeQualifier();

    private final ScreenSize mValue;

    public ScreenSizeQualifier() {
        this(null);
    }

    public ScreenSizeQualifier(ScreenSize value) {
        mValue = value;
    }

    public ScreenSize getValue() {
        return mValue;
    }

    @Override
    public ResourceEnum getEnumValue() {
        return mValue;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getShortName() {
        return "Size";
    }

    @Override
    public int since() {
        return 4;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        ScreenSize size = ScreenSize.getEnum(value);
        if (size != null) {
            ScreenSizeQualifier qualifier = new ScreenSizeQualifier(size);
            config.setScreenSizeQualifier(qualifier);
            return true;
        }

        return false;
    }

    @Override
    public ScreenSizeQualifier getNullQualifier() {
        return NULL_QUALIFIER;
    }

    @Override
    public boolean isMatchFor(ResourceQualifier qualifier) {
        // This is a match only if the screen size is smaller than the qualifier's screen size.
        if (qualifier instanceof ScreenSizeQualifier) {
            int qualifierIndex = ScreenSize.getIndex(((ScreenSizeQualifier) qualifier).mValue);
            int index = ScreenSize.getIndex(mValue);
            if (index <= qualifierIndex) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isValid() {
        return this != NULL_QUALIFIER;
    }

    @Override
    public boolean isBetterMatchThan(@Nullable ResourceQualifier compareTo,
                                     @NotNull ResourceQualifier reference) {
        if (compareTo == null) {
            return true;
        }
        ScreenSizeQualifier compareQ = (ScreenSizeQualifier) compareTo;
        // A little backwards compatibility here: undefined is
        // considered equivalent to normal.  But only if the
        // requested size is at least normal; otherwise, small
        // is better than the default.
        int mySL = ScreenSize.getIndex(mValue);
        int oSL = ScreenSize.getIndex(compareQ.mValue);
        int fixedMySL = mySL;
        int fixedOSL = oSL;
        int requestedSL = ScreenSize.getIndex(((ScreenSizeQualifier) reference).mValue);
        if (requestedSL >= ScreenSize.NORMAL.ordinal()) {
            if (fixedMySL == -1) fixedMySL = ScreenSize.NORMAL.ordinal();
            if (fixedOSL == -1) fixedOSL = ScreenSize.NORMAL.ordinal();
        }
        // For screen size, the best match is the one that is
        // closest to the requested screen size, but not over
        // (the not over part is dealt with in isMatchFor()).
        if (fixedMySL == fixedOSL) {
            // If the two are the same, but 'this' is actually
            // undefined, then the other is really a better match.
            return mySL != -1;
        }
        return fixedMySL > fixedOSL;
    }
}
