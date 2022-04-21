package com.tyron.builder.problems.internal;


import java.util.Collection;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public abstract class RenderingUtils {
    public static String oxfordListOf(Collection<String> values, String conjunction) {
        return values.stream()
                .sorted()
                .map(s -> "'" + s + "'")
                .collect(oxfordJoin(conjunction));
    }

    public static Collector<? super String, ?, String> oxfordJoin(String conjunction) {
        return Collectors.collectingAndThen(Collectors.toList(), stringList -> {
            if (stringList.isEmpty()) {
                return "";
            }
            if (stringList.size() == 1) {
                return stringList.get(0);
            }
            int bound = stringList.size() - 1;
            return String.join(", ", stringList.subList(0, bound)) + " " + conjunction + " " + stringList.get(bound);
        });
    }
}
