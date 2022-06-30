package com.tyron.builder.api.tasks.compile;


import com.tyron.builder.internal.reflect.JavaPropertyReflectionUtil;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Map;

/**
 * Base class for compilation-related options.
 */
public abstract class AbstractOptions implements Serializable {
    private static final long serialVersionUID = 0;

    public void define(@Nullable Map<String, Object> args) {
        if (args == null) {
            return;
        }
        for (Map.Entry<String, Object> arg: args.entrySet()) {
            setProperty(arg.getKey(), arg.getValue());
        }
    }

    private void setProperty(String property, Object value) {
        JavaPropertyReflectionUtil.writeableProperty(getClass(), property, value == null ? null : value.getClass()).setValue(this, value);
    }

}