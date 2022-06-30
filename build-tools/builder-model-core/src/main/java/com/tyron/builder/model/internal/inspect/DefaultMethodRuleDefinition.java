/*
 * Copyright 2014 the original author or authors.
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

package com.tyron.builder.model.internal.inspect;

import com.google.common.collect.ImmutableList;
import javax.annotation.concurrent.ThreadSafe;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.model.Path;
import com.tyron.builder.model.internal.core.ModelPath;
import com.tyron.builder.model.internal.core.ModelReference;
import com.tyron.builder.model.internal.core.rule.describe.MethodModelRuleDescriptor;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.method.WeaklyTypeReferencingMethod;
import com.tyron.builder.model.internal.type.ModelType;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.tyron.builder.util.internal.CollectionUtils.findFirst;

@ThreadSafe
public class DefaultMethodRuleDefinition<T, R, S> implements MethodRuleDefinition<R, S> {
    private static final String[] PARAMETER_DESC;

    static {
        PARAMETER_DESC = new String[255];
        for (int i = 0; i < PARAMETER_DESC.length; i++) {
            PARAMETER_DESC[i] = "parameter " + (i+1);
        }
    }

    private List<ModelReference<?>> references;
    private List<List<Annotation>> parameterAnnotations;
    private final WeaklyTypeReferencingMethod<T, R> method;

    private DefaultMethodRuleDefinition(Method method, ModelType<T> instanceType, ModelType<R> returnType) {
        this.method = WeaklyTypeReferencingMethod.of(instanceType, returnType, method);

        ImmutableList.Builder<ModelReference<?>> referencesBuilder = ImmutableList.builder();
        ImmutableList.Builder<List<Annotation>> parameterAnnotationsBuilder = ImmutableList.builder();
        for (int i = 0; i < method.getGenericParameterTypes().length; i++) {
            List<Annotation> paramAnnotations = Arrays.asList(method.getParameterAnnotations()[i]);
            parameterAnnotationsBuilder.add(paramAnnotations);
            referencesBuilder.add(reference(paramAnnotations, i));
        }
        this.references = referencesBuilder.build();
        this.parameterAnnotations = parameterAnnotationsBuilder.build();
    }

    public static <T> MethodRuleDefinition<?, ?> create(Class<T> source, Method method) {
        return innerCreate(source, method);
    }

    private static <T, R, S> MethodRuleDefinition<R, S> innerCreate(Class<T> source, Method method) {
        ModelType<R> returnType = ModelType.returnType(method);
        return new DefaultMethodRuleDefinition<T, R, S>(method, ModelType.of(source), returnType);
    }

    @Override
    public WeaklyTypeReferencingMethod<?, R> getMethod() {
        return method;
    }

    @Override
    public String getMethodName() {
        return method.getName();
    }

    @Override
    public ModelType<R> getReturnType() {
        return method.getReturnType();
    }

    @Nullable
    @Override
    public ModelReference<S> getSubjectReference() {
        return Cast.uncheckedCast(references.isEmpty() ? null : references.get(0));
    }

    @Override
    public List<ModelReference<?>> getTailReferences() {
        return references.size() > 1 ? references.subList(1, references.size()) : Collections.<ModelReference<?>>emptyList();
    }

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return getAnnotation(annotationType) != null;
    }

    @Override
    public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        return method.getMethod().getAnnotation(annotationType);
    }

    @Override
    public ModelRuleDescriptor getDescriptor() {
        return MethodModelRuleDescriptor.of(method);
    }

    @Override
    public List<ModelReference<?>> getReferences() {
        return references;
    }

    @Override
    public List<List<Annotation>> getParameterAnnotations() {
        return parameterAnnotations;
    }

    private ModelReference<?> reference(List<Annotation> annotations, int i) {
        Path pathAnnotation = (Path) findFirst(annotations, new Spec<Annotation>() {
            @Override
            public boolean isSatisfiedBy(Annotation element) {
                return element.annotationType().equals(Path.class);
            }
        });
        ModelPath path = pathAnnotation == null ? null : ModelPath.path(pathAnnotation.value());
        ModelType<?> cast = method.getGenericParameterTypes().get(i);
        return ModelReference.of(path, cast, PARAMETER_DESC[i]);
    }
}
