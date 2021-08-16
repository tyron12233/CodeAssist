/*
 * Copyright (c) 2008, 2009, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package com.sun.tools.javac.api;

import java.util.Locale;
import java.util.MissingResourceException;

/**
 * This interface defines the minimum requirements in order to provide support
 * for localized formatted strings.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 *
 * @author Maurizio Cimadamore
 */
public interface Messages {

    /**
     * Add a new resource bundle to the list that is searched for localized messages.
     * @param bundleName the name to identify the resource bundle of localized messages.
     * @throws MissingResourceException if the given resource is not found
     */
    void add(String bundleName) throws MissingResourceException;

    /**
     * Get a localized formatted string.
     * @param l locale in which the text is to be localized
     * @param key locale-independent message key
     * @param args misc message arguments
     * @return a localized formatted string
     */
    String getLocalizedString(Locale l, String key, Object... args);
}
