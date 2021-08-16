/*
 * Copyright (c) 2008, Oracle and/or its affiliates. All rights reserved.
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

/**
 * This interface must be implemented by any javac class that has non-trivial
 * formatting needs (e.g. where toString() does not apply because of localization).
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 *
 * @author Maurizio Cimadamore
 */
public interface Formattable {

    /**
     * Used to obtain a localized String representing the object accordingly
     * to a given locale
     *
     * @param locale locale in which the object's representation is to be rendered
     * @param messages messages object used for localization
     * @return a locale-dependent string representing the object
     */
    public String toString(Locale locale, Messages messages);
    /**
     * Retrieve a pretty name of this object's kind
     * @return a string representing the object's kind
     */
    String getKind();

    static class LocalizedString implements Formattable {
        String key;

        public LocalizedString(String key) {
            this.key = key;
        }

        public String toString(Locale l, Messages messages) {
            return messages.getLocalizedString(l, key);
        }
        public String getKind() {
            return "LocalizedString";
        }

        public String toString() {
            return key;
        }
    }
}
