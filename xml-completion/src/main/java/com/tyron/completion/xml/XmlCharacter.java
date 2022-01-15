package com.tyron.completion.xml;

import java.util.HashSet;
import java.util.Set;

public class XmlCharacter {

    public static final Set<Character> sNonXmlCharacters = new HashSet<>();

    static {
        sNonXmlCharacters.add('\t');
        sNonXmlCharacters.add('\n');
        sNonXmlCharacters.add(' ');
    }

    public static boolean isNonXmlCharacterPart(char c) {
        return sNonXmlCharacters.contains(c);
    }
}
