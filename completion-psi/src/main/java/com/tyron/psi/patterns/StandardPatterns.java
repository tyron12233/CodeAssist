package com.tyron.psi.patterns;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.util.Function;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Factory for {@link String}, {@link Character} and {@link Object}-based patterns. Provides methods for composing patterns
 * with e.g. logical operations.
 * <p>
 * Please see the <a href="https://plugins.jetbrains.com/docs/intellij/element-patterns.html">IntelliJ Platform Docs</a>
 * for a high-level overview.
 */
@SuppressWarnings("unchecked")
public class StandardPatterns {

    private static final FalsePattern FALSE_PATTERN = new FalsePattern();

    @NotNull
    public static StringPattern string() {
        return StringPattern.STRING_PATTERN;
    }


    @NotNull
    public static CharPattern character() {
        return new CharPattern();
    }

    @NotNull
    public static <T> ObjectPattern.Capture<T> instanceOf(@NotNull Class<T> aClass) {
        return new ObjectPattern.Capture<>(aClass);
    }

    @NotNull
    @SafeVarargs
    public static <T> ElementPattern<T> instanceOf(Class<T>... classes) {
        ElementPattern[] patterns = ContainerUtil.map(classes, new Function<Class<T>, ElementPattern>() {
            @Override
            public ElementPattern fun(Class<T> tClass) {
                return instanceOf(tClass);
            }
        }).toArray(new ElementPattern[0]);
        return or(patterns);
    }

    @NotNull
    public static <T> CollectionPattern<T> collection(Class<T> aClass) {
        return new CollectionPattern<>();
    }

    public static @NotNull <T> CollectionPattern<T> collection() {
        return new CollectionPattern<>();
    }


    @NotNull
    public static <T> ElementPattern save(final Key<T> key) {
        return new ObjectPattern.Capture<>(new InitialPatternCondition(Object.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                context.put(key, (T)o);
                return true;
            }

            @Override
            public void append(@NotNull @NonNls final StringBuilder builder, final String indent) {
                builder.append("save(").append(key).append(")");
            }
        });
    }

    @NotNull
    public static ObjectPattern.Capture<Object> object() {
        return instanceOf(Object.class);
    }

    @NotNull
    public static <T> ObjectPattern.Capture<T> object(@NotNull T value) {
        return instanceOf((Class<T>)value.getClass()).equalTo(value);
    }

    @NotNull
    public static ElementPattern get(@NotNull @NonNls final String key) {
        return new ObjectPattern.Capture(new InitialPatternCondition(Object.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                return Comparing.equal(o, context.get(key));
            }

            @Override
            public void append(@NotNull @NonNls final StringBuilder builder, final String indent) {
                builder.append("get(").append(key).append(")");
            }
        });
    }


    @SafeVarargs
    public static @NotNull <E> ElementPattern<E> or(final ElementPattern<? extends E>  ... patterns) {
        return new ObjectPattern.Capture<>(new InitialPatternConditionPlus(Object.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                for (ElementPattern<?> pattern : patterns) {
                    if (pattern.accepts(o, context)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void append(@NotNull @NonNls final StringBuilder builder, final String indent) {
                boolean first = true;
                for (ElementPattern<?> pattern : patterns) {
                    if (!first) {
                        builder.append("\n").append(indent);
                    }
                    first = false;
                    pattern.getCondition().append(builder, indent + "  ");
                }
            }

            public List<ElementPattern<?>> getPatterns() {
                return Arrays.asList(patterns);
            }
        });
    }

    @NotNull
    @SafeVarargs
    public static <E> ElementPattern<E> and(final ElementPattern<? extends E>... patterns) {
        final List<InitialPatternCondition> initial = new SmartList<>();
        for (ElementPattern<?> pattern : patterns) {
            initial.add(pattern.getCondition().getInitialCondition());
        }
        ObjectPattern.Capture<E> result = composeInitialConditions(initial);
        for (ElementPattern pattern : patterns) {
            for (PatternCondition<?> condition : (List<PatternCondition<?>>)pattern.getCondition().getConditions()) {
                result = result.with((PatternCondition<? super E>)condition);
            }
        }
        return result;
    }

    @NotNull
    private static <E> ObjectPattern.Capture<E> composeInitialConditions(final List<? extends InitialPatternCondition> initial) {
        return new ObjectPattern.Capture<>(new InitialPatternCondition(Object.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                for (final InitialPatternCondition pattern : initial) {
                    if (!pattern.accepts(o, context)) return false;
                }
                return true;
            }

            @Override
            public void append(@NotNull @NonNls final StringBuilder builder, final String indent) {
                boolean first = true;
                for (final InitialPatternCondition pattern : initial) {
                    if (!first) {
                        builder.append("\n").append(indent);
                    }
                    first = false;
                    pattern.append(builder, indent + "  ");
                }
            }
        });
    }

    @NotNull
    public static <E> ObjectPattern.Capture<E> not(final ElementPattern<E> pattern) {
        return new ObjectPattern.Capture<>(new InitialPatternConditionPlus(Object.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                return !pattern.accepts(o, context);
            }

            @Override
            public void append(@NotNull @NonNls final StringBuilder builder, final String indent) {
                pattern.getCondition().append(builder.append("not("), indent + "  ");
                builder.append(")");
            }


            public List<ElementPattern<?>> getPatterns() {
                return Collections.singletonList(pattern);
            }
        });
    }

    @NotNull
    public static <T> ObjectPattern.Capture<T> optional(final ElementPattern<T> pattern) {
        return new ObjectPattern.Capture<>(new InitialPatternCondition(Object.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                pattern.accepts(o, context);
                return true;
            }
        });
    }


    @NotNull
    public static <E> ElementPattern<E> alwaysFalse() {
        return FALSE_PATTERN;
    }

    private static final class FalsePattern implements ElementPattern {
        @Override
        public boolean accepts(@Nullable Object o) {
            return false;
        }

        @Override
        public boolean accepts(@Nullable Object o, ProcessingContext context) {
            return false;
        }

        @Override
        public ElementPatternCondition getCondition() {
            return new ElementPatternCondition(new InitialPatternCondition(Object.class) {
                @Override
                public boolean accepts(@Nullable Object o, ProcessingContext context) {
                    return false;
                }
            });
        }
    }
}
