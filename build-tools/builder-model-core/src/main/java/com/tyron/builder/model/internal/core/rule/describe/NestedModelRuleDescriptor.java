package com.tyron.builder.model.internal.core.rule.describe;

import com.google.common.base.Objects;
import javax.annotation.concurrent.ThreadSafe;
import com.tyron.builder.api.UncheckedIOException;

import java.io.IOException;

@ThreadSafe
class NestedModelRuleDescriptor extends AbstractModelRuleDescriptor {

    private final ModelRuleDescriptor parent;
    private final ModelRuleDescriptor child;

    public NestedModelRuleDescriptor(ModelRuleDescriptor parent, ModelRuleDescriptor child) {
        this.parent = parent;
        this.child = child;
    }

    @Override
    public void describeTo(Appendable appendable) {
        parent.describeTo(appendable);
        try {
            appendable.append(" > ");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        child.describeTo(appendable);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NestedModelRuleDescriptor that = (NestedModelRuleDescriptor) o;
        return Objects.equal(parent, that.parent)
            && Objects.equal(child, that.child);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(parent, child);
    }
}
