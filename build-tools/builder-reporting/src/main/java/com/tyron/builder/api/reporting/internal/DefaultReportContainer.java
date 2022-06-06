/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.api.reporting.internal;

import groovy.lang.Closure;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.NamedDomainObjectSet;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DefaultNamedDomainObjectSet;
import com.tyron.builder.api.reporting.Report;
import com.tyron.builder.api.reporting.ReportContainer;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.util.internal.ConfigureUtil;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.SortedMap;

public class DefaultReportContainer<T extends Report> extends DefaultNamedDomainObjectSet<T> implements ReportContainer<T> {
    private NamedDomainObjectSet<T> enabled;

    public DefaultReportContainer(Class<? extends T> type, Instantiator instantiator, CollectionCallbackActionDecorator callbackActionDecorator) {
        super(type, instantiator, Report.NAMER, callbackActionDecorator);

        enabled = matching(new Spec<T>() {
            @Override
            public boolean isSatisfiedBy(T element) {
                return element.getRequired().get();
            }
        });
    }

    @Override
    protected void assertMutableCollectionContents() {
        throw new ImmutableViolationException();
    }

    @Override
    public NamedDomainObjectSet<T> getEnabled() {
        return enabled;
    }

    @Override
    public ReportContainer<T> configure(Closure cl) {
        ConfigureUtil.configureSelf(cl, this);
        return this;
    }

    @Nullable
    @Internal
    public T getFirstEnabled() {
        SortedMap<String, T> map = enabled.getAsMap();
        if (map.isEmpty()) {
            return null;
        } else {
            return map.get(map.firstKey());
        }
    }

    protected <N extends T> N add(Class<N> clazz, Object... constructionArgs) {
        N report = getInstantiator().newInstance(clazz, constructionArgs);
        String name = report.getName();
        if (name.equals("enabled")) {
            throw new InvalidUserDataException("Reports that are part of a ReportContainer cannot be named 'enabled'");
        }
        getStore().add(report);
        index();
        return report;
    }

    @Override
    public Map<String, T> getEnabledReports() {
        return getEnabled().getAsMap();
    }
}
