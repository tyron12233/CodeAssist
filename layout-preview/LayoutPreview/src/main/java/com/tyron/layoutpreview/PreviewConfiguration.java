package com.tyron.layoutpreview;

import java.util.Locale;

/**
 * Class used to define what will be displayed on the layout preview based on
 * the current configuration eg. Locale, Orientation, Screen size
 */
public class PreviewConfiguration  {

    public Locale locale;

    public PreviewConfiguration() {

    }

    /**
     * Sets the locale that will be used for preview
     */
    public void setLocale(Locale locale) {
        this.locale = locale;
    }
}
