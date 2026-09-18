package dev.ide.model.impl.jdk

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Generates a minimal, self-consistent set of `java.lang` source stubs — a fallback platform when no
 * real JDK is present, so basic name/member resolution and completion of core types still work. The
 * stubs reference only each other and primitives, so a source-based type solver resolves them
 * standalone. This is intentionally tiny; a real deployment ships an `android.jar` or a JDK image.
 */
object SyntheticJdk {

    fun writeInto(dir: Path): Path {
        for ((relPath, content) in STUBS) {
            val file = dir.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return dir
    }

    private val STUBS: Map<String, String> = linkedMapOf(
        "java/lang/Object.java" to """
            package java.lang;
            public class Object {
                public String toString() { return null; }
                public boolean equals(Object obj) { return false; }
                public int hashCode() { return 0; }
                public final Class<?> getClass() { return null; }
            }
        """.trimIndent(),

        "java/lang/Class.java" to """
            package java.lang;
            public final class Class<T> {
                public String getName() { return null; }
                public String getSimpleName() { return null; }
            }
        """.trimIndent(),

        "java/lang/CharSequence.java" to """
            package java.lang;
            public interface CharSequence {
                int length();
                char charAt(int index);
                CharSequence subSequence(int start, int end);
                String toString();
            }
        """.trimIndent(),

        "java/lang/Comparable.java" to """
            package java.lang;
            public interface Comparable<T> {
                int compareTo(T o);
            }
        """.trimIndent(),

        "java/lang/Number.java" to """
            package java.lang;
            public abstract class Number {
                public abstract int intValue();
                public abstract long longValue();
                public abstract double doubleValue();
            }
        """.trimIndent(),

        "java/lang/String.java" to """
            package java.lang;
            public final class String implements CharSequence, Comparable<String> {
                public int length() { return 0; }
                public boolean isEmpty() { return false; }
                public char charAt(int index) { return ' '; }
                public CharSequence subSequence(int start, int end) { return null; }
                public String substring(int beginIndex) { return null; }
                public String substring(int beginIndex, int endIndex) { return null; }
                public String toUpperCase() { return null; }
                public String toLowerCase() { return null; }
                public String trim() { return null; }
                public boolean startsWith(String prefix) { return false; }
                public boolean contains(CharSequence s) { return false; }
                public int compareTo(String o) { return 0; }
            }
        """.trimIndent(),

        "java/lang/Integer.java" to """
            package java.lang;
            public final class Integer extends Number implements Comparable<Integer> {
                public int intValue() { return 0; }
                public long longValue() { return 0L; }
                public double doubleValue() { return 0.0; }
                public int compareTo(Integer o) { return 0; }
                public static int parseInt(String s) { return 0; }
                public static Integer valueOf(int i) { return null; }
            }
        """.trimIndent(),
    )
}
