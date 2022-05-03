package com.tyron.builder.model.internal.inspect;

import com.tyron.builder.internal.reflect.MethodDescription;
import com.tyron.builder.model.internal.type.ModelType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class FormattingValidationProblemCollector implements ValidationProblemCollector {
    private final String role;
    private final ModelType<?> source;
    private final List<String> problems = new ArrayList<String>();

    public FormattingValidationProblemCollector(String role, ModelType<?> source) {
        this.role = role;
        this.source = source;
    }

    @Override
    public boolean hasProblems() {
        return !problems.isEmpty();
    }

    @Override
    public void add(String problem) {
        problems.add(problem);
    }

    @Override
    public void add(Field field, String problem) {
        if (field.getDeclaringClass().equals(source.getConcreteClass())) {
            problems.add("Field " + field.getName() + " is not valid: " + problem);
        } else {
            problems.add("Field " + ModelType.of(field.getDeclaringClass()).getDisplayName() + '.' + field.getName() + " is not valid: " + problem);
        }
    }

    @Override
    public void add(Method method, String role, String problem) {
        MethodDescription description = MethodDescription.name(method.getName())
                .takes(method.getGenericParameterTypes());
        StringBuilder message = new StringBuilder("Method ");
        if (method.getDeclaringClass().equals(source.getConcreteClass())) {
            message.append(description);
        } else {
            message.append(ModelType.of(method.getDeclaringClass()).getDisplayName()).append('.').append(description);
        }
        message.append(" is not a valid");
        if (role != null) {
            message.append(' ').append(role);
        }
        message.append(" method: ");
        message.append(problem);
        problems.add(message.toString());
    }

    @Override
    public void add(Constructor<?> constructor, String problem) {
        String description = MethodDescription.name(ModelType.of(constructor.getDeclaringClass()).getDisplayName())
                .takes(constructor.getGenericParameterTypes())
                .toString();
        problems.add("Constructor " + description + " is not valid: " + problem);
    }

    public String format() {
        StringBuilder errorString = new StringBuilder(String.format("Type %s is not a valid %s:", source.getName(), role));
        if (problems.size() == 1 && errorString.length() + problems.get(0).length() < 80) {
            errorString.append(' ');
            errorString.append(problems.get(0));
        } else {
            for (String problem : problems) {
                errorString.append(String.format("\n- %s", problem));
            }
        }
        return errorString.toString();
    }
}
