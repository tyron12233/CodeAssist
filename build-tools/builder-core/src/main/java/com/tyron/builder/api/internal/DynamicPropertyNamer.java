package com.tyron.builder.api.internal;

import groovy.lang.GroovyObject;
import groovy.lang.MissingPropertyException;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.Namer;
import com.tyron.builder.internal.metaobject.DynamicObjectUtil;

import java.util.Map;

public class DynamicPropertyNamer implements Namer<Object> {
    @Override
    public String determineName(Object thing) {
        Object name;
        try {
            if (thing instanceof Named) {
                name = ((Named) thing).getName();
            } else if (thing instanceof Map) {
                name = ((Map) thing).get("name");
            } else if (thing instanceof GroovyObject) {
                name = ((GroovyObject) thing).getProperty("name");
            } else {
                name = DynamicObjectUtil.asDynamicObject(thing).getProperty("name");
            }
        } catch (MissingPropertyException e) {
            throw new NoNamingPropertyException(thing);
        }

        if (name == null) {
            throw new NullNamingPropertyException(thing);
        }

        return name.toString();
    }
}
