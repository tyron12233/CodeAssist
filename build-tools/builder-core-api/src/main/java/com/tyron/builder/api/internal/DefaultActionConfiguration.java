package com.tyron.builder.api.internal;

import com.google.common.collect.Lists;
import com.tyron.builder.api.ActionConfiguration;
import com.tyron.builder.api.NonExtensible;

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
