package org.gradle.api.internal;

import com.google.common.collect.Lists;
import org.gradle.api.ActionConfiguration;
import org.gradle.api.NonExtensible;

import java.util.Collections;
import java.util.List;

@NonExtensible
public class DefaultActionConfiguration implements ActionConfiguration {
    private final List<Object> params = Lists.newArrayList();

    @Override
    public void params(Object... params) {
        Collections.addAll(this.params, params);
    }

    @Override
    public void setParams(Object... params) {
        this.params.clear();
        Collections.addAll(this.params, params);
    }

    @Override
    public Object[] getParams() {
        return this.params.toArray();
    }
}
