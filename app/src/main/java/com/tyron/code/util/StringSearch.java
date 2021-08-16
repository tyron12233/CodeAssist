package com.tyron.code.util;

public class StringSearch {
    
    public static boolean matchesPartialName(CharSequence candidate, CharSequence partialName) {
        if (candidate.length() < partialName.length()) return false;
        for (int i = 0; i < partialName.length(); i++) {
            if (candidate.charAt(i) != partialName.charAt(i)) return false;
        }
        return true;
    }
}
