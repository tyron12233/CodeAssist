package com.tyron.builder.model.internal.core.rule.describe;

import javax.annotation.concurrent.ThreadSafe;
import com.tyron.builder.api.internal.cache.StringInterner;

@ThreadSafe
abstract class AbstractModelRuleDescriptor implements ModelRuleDescriptor {

    protected final static StringInterner STRING_INTERNER = new StringInterner();

    @Override
    public ModelRuleDescriptor append(ModelRuleDescriptor child) {
        return new NestedModelRuleDescriptor(this, child);
    }

    @Override
    public ModelRuleDescriptor append(String child) {
        return append(new SimpleModelRuleDescriptor(child));
    }

    @Override
    public ModelRuleDescriptor append(String child, Object... args) {
        return append(String.format(child, args));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        describeTo(sb);
        return sb.toString();
    }
}
