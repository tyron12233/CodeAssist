package com.tyron.psi.patterns;

import java.util.List;

/**
 * @author peter
 */
public abstract class InitialPatternConditionPlus<T> extends InitialPatternCondition<T> {
    protected InitialPatternConditionPlus(Class<T> aAcceptedClass) {
        super(aAcceptedClass);
    }

    public abstract List<ElementPattern<?>> getPatterns();
}