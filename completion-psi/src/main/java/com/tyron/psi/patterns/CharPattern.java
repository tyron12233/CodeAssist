package com.tyron.psi.patterns;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ObjectPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

public class CharPattern extends ObjectPattern<Character, CharPattern> {
    private static final CharPattern ourJavaIdentifierStartCharacter = StandardPatterns.character().javaIdentifierStart();
    private static final CharPattern ourJavaIdentifierPartCharacter = StandardPatterns.character().javaIdentifierPart();
    private static final CharPattern ourWhitespaceCharacter = StandardPatterns.character().whitespace();
    private static final CharPattern ourLetterOrDigitCharacter = StandardPatterns.character().letterOrDigit();

    protected CharPattern() {
        super(Character.class);
    }

    public CharPattern javaIdentifierPart() {
        return with(new PatternCondition<Character>("javaIdentifierPart") {
            @Override
            public boolean accepts(@NotNull final Character character, final ProcessingContext context) {
                return Character.isJavaIdentifierPart(character.charValue());
            }
        });
    }

    public CharPattern javaIdentifierStart() {
        return with(new PatternCondition<Character>("javaIdentifierStart") {
            @Override
            public boolean accepts(@NotNull final Character character, final ProcessingContext context) {
                return Character.isJavaIdentifierStart(character.charValue());
            }
        });
    }

    public CharPattern whitespace() {
        return with(new PatternCondition<Character>("whitespace") {
            @Override
            public boolean accepts(@NotNull final Character character, final ProcessingContext context) {
                return Character.isWhitespace(character.charValue());
            }
        });
    }

    public CharPattern letterOrDigit() {
        return with(new PatternCondition<Character>("letterOrDigit") {
            @Override
            public boolean accepts(@NotNull final Character character, final ProcessingContext context) {
                return Character.isLetterOrDigit(character.charValue());
            }
        });
    }

    public static CharPattern javaIdentifierStartCharacter() {
        return ourJavaIdentifierStartCharacter;
    }

    public static CharPattern javaIdentifierPartCharacter() {
        return ourJavaIdentifierPartCharacter;
    }

    public static CharPattern letterOrDigitCharacter() {
        return ourLetterOrDigitCharacter;
    }

    public static CharPattern whitespaceCharacter() {
        return ourWhitespaceCharacter;
    }
}
