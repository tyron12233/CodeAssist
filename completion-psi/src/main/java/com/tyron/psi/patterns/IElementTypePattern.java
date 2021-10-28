package com.tyron.psi.patterns;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.TokenSet;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author peter
 * @see PlatformPatterns#elementType()
 */
public class IElementTypePattern extends ObjectPattern<IElementType, IElementTypePattern> {
    protected IElementTypePattern() {
        super(IElementType.class);
    }

    public IElementTypePattern or(final IElementType... types) {
        return tokenSet(TokenSet.create(types));
    }

    public IElementTypePattern tokenSet(@NotNull final TokenSet tokenSet) {
        return with(new PatternCondition<IElementType>("tokenSet") {
            @Override
            public boolean accepts(@NotNull final IElementType type, final ProcessingContext context) {
                return tokenSet.contains(type);
            }
        });
    }
}
