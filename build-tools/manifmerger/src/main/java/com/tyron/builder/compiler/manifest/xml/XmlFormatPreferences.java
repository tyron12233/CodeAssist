package com.tyron.builder.compiler.manifest.xml;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.annotations.VisibleForTesting;
import java.util.Comparator;
import org.w3c.dom.Attr;

/**
 * Formatting preferences used by the Android XML formatter.
 */
public class XmlFormatPreferences {
    /** Use the Eclipse indent (tab/space, indent size) settings? */
    public boolean useEclipseIndent = false;

    /** Remove empty lines in all cases? */
    public boolean removeEmptyLines = false;

    /** Reformat the text and comment blocks? */
    public boolean reflowText = false;

    /** Join lines when reformatting text and comment blocks? */
    public boolean joinLines = false;

    /** Can attributes appear on the same line as the opening line if there is just one of them? */
    public boolean oneAttributeOnFirstLine = true;

    /** The sorting order to use when formatting */
    public XmlAttributeSortOrder sortAttributes = XmlAttributeSortOrder.LOGICAL;

    /** Returns the comparator to use when formatting, or null for no sorting */
    @Nullable
    public Comparator<Attr> getAttributeComparator() {
        return sortAttributes.getAttributeComparator();
    }

    /** Should there be a space before the closing {@code >}; or {@code >/;} ? */
    public boolean spaceBeforeClose = true;

    /** The string to insert for each indentation level */
    protected String mOneIndentUnit = "    "; //$NON-NLS-1$

    /** Tab width (number of spaces to display for a tab) */
    protected int mTabWidth = -1; // -1: uninitialized

    @VisibleForTesting
    protected XmlFormatPreferences() {
    }

    /**
     * Returns a new preferences object initialized with the defaults
     *
     * @return an {@link XmlFormatPreferences} object
     */
    @NotNull
    public static XmlFormatPreferences defaults() {
        return new XmlFormatPreferences();
    }

    public String getOneIndentUnit() {
        return mOneIndentUnit;
    }

    /**
     * Returns the number of spaces used to display a single tab character
     *
     * @return the number of spaces used to display a single tab character
     */
    @SuppressWarnings("restriction") // Editor settings
    public int getTabWidth() {
        if (mTabWidth == -1) {
            mTabWidth = 4;
        }

        return mTabWidth;
    }
}

