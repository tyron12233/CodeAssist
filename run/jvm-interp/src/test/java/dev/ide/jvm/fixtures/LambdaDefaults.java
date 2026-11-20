package dev.ide.jvm.fixtures;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Default methods of a platform interface, called on a lambda. The lambda is the interface's one abstract
 * method; the defaults are the interface's own code and call back into it.
 */
public class LambdaDefaults {
    public static String onLambdas() {
        List<String> words = Arrays.asList("pear", "fig", "apple");
        // Guava and Dagger write an Iterable as a lambda and then stream it.
        Iterable<String> iterable = () -> words.iterator();
        String streamed = StreamSupport.stream(iterable.spliterator(), false).collect(Collectors.joining("+"));
        StringBuilder each = new StringBuilder();
        iterable.forEach(w -> each.append(w.charAt(0)));

        Comparator<String> byLength = (a, b) -> a.length() - b.length();
        String longest = words.stream().sorted(byLength.reversed()).findFirst().get();
        String tie = words.stream().sorted(byLength.thenComparing(Comparator.naturalOrder())).collect(Collectors.joining(","));

        Predicate<String> shortWord = w -> w.length() < 4;
        Function<String, Integer> length = String::length;
        return streamed + "|" + each + "|" + longest + "|" + tie + "|" + shortWord.negate().test("fig")
            + "|" + length.andThen(n -> n * 2).apply("four") + "|" + shortWord.and(w -> w.startsWith("f")).test("fig");
    }
}
