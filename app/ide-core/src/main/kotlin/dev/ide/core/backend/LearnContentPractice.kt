package dev.ide.core.backend

// ===========================================================================
// Practice problems: more Kotlin challenges for the "Kotlin: Practice" track, and
// the "Java: Practice" track. Every exercise reads stdin and is checked against
// several inputs, so a memorised answer cannot pass.
// ===========================================================================

/** Extra lessons appended to the "Kotlin: Practice" track (ids `pk-*`). */
internal fun kotlinPracticeExtra(): List<LearnLessonDef> = listOf(
    // ---- 1. Palindrome ----
    LearnLessonDef(
        id = "pk-palindrome", title = "Palindrome check", summary = "Does it read the same both ways?",
        iconId = "sparkle", estMinutes = 5,
        steps = listOf(
            LearnStepDef.Concept(
                "pk-palindrome-c", "Same forwards and backwards",
                listOf(
                    text("A **palindrome** reads the same from both ends, like `racecar` or `level`. The quickest test is to compare a word with its reverse:"),
                    code(
                        """
                        val word = "noon"
                        println(word == word.reversed()) // true
                        """
                    ),
                    tip("`Level` and `level` should count as the same word. Call `lowercase()` first so the capital letter does not get in the way."),
                ),
            ),
            LearnStepDef.Interactive(
                "pk-palindrome-i", "Is it a palindrome?",
                listOf(
                    text("The input is one word on a single line. Print `Palindrome` if it reads the same backwards (ignoring upper and lower case), otherwise print `Not a palindrome`."),
                    text("For the input `Level` the output is:"),
                    code("Palindrome", "plain"),
                ),
                starterCode = """
                    fun main() {
                        val word = readln().trim()
                        // Print Palindrome or Not a palindrome
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "Make the word lowercase first: word.lowercase()",
                    "Compare it with its reverse: lower == lower.reversed()",
                    "Use if / else to choose which message to print.",
                ),
                solution = """
                    fun main() {
                        val word = readln().trim().lowercase()
                        if (word == word.reversed()) {
                            println("Palindrome")
                        } else {
                            println("Not a palindrome")
                        }
                    }
                """,
                check = ExerciseCheck(
                    cases = listOf(
                        ExerciseCase("racecar\n", "Palindrome"),
                        ExerciseCase("Level\n", "Palindrome"),
                        ExerciseCase("kotlin\n", "Not a palindrome"),
                        ExerciseCase("a\n", "Palindrome"),
                    ),
                ),
            ),
        ),
    ),

    // ---- 2. Sum of digits ----
    LearnLessonDef(
        id = "pk-digit-sum", title = "Sum of digits", summary = "Add up every digit of a number.",
        iconId = "sparkle", estMinutes = 5,
        steps = listOf(
            LearnStepDef.Concept(
                "pk-digit-sum-c", "Digits as characters",
                listOf(
                    text("A number read with `readln()` arrives as text, and text is a list of characters. `digitToInt()` turns the character `'7'` into the number `7`, and `sumOf { }` adds up a value for each character:"),
                    code(
                        """
                        val digits = "305"
                        println(digits.sumOf { it.digitToInt() }) // 8
                        """
                    ),
                    tip("The maths way works too: `n % 10` is the last digit and `n / 10` drops it. Repeat until `n` is 0."),
                ),
            ),
            LearnStepDef.Interactive(
                "pk-digit-sum-i", "Add the digits",
                listOf(
                    text("The input is one whole number (0 or more) on a single line. Print the sum of its digits."),
                    text("For the input `1234` the output is `10`, because 1 + 2 + 3 + 4 = 10."),
                ),
                starterCode = """
                    fun main() {
                        val number = readln().trim()
                        // Print the sum of the digits in number
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "Each character of the string is one digit.",
                    "it.digitToInt() turns a digit character into an Int.",
                    "println(number.sumOf { it.digitToInt() })",
                ),
                solution = """
                    fun main() {
                        val number = readln().trim()
                        println(number.sumOf { it.digitToInt() })
                    }
                """,
                check = ExerciseCheck(
                    cases = listOf(
                        ExerciseCase("1234\n", "10"),
                        ExerciseCase("0\n", "0"),
                        ExerciseCase("9999\n", "36"),
                        ExerciseCase("507\n", "12"),
                    ),
                ),
            ),
        ),
    ),

    // ---- 3. Second largest ----
    LearnLessonDef(
        id = "pk-second-largest", title = "Second largest", summary = "Not the biggest, the one just below it.",
        iconId = "sparkle", estMinutes = 6,
        steps = listOf(
            LearnStepDef.Concept(
                "pk-second-largest-c", "Distinct and sorted",
                listOf(
                    text("To read a line of numbers, split it on spaces and convert each piece:"),
                    code(
                        """
                        val nums = "3 9 4".split(" ").filter { it.isNotEmpty() }.map { it.toInt() }
                        """
                    ),
                    text("`distinct()` drops repeats and `sortedDescending()` puts the biggest first. After both, the second element is the second largest value."),
                    tip("Watch out for repeats: in `9 9 7` the second largest value is 7, not 9."),
                ),
            ),
            LearnStepDef.Interactive(
                "pk-second-largest-i", "Find the runner-up",
                listOf(
                    text("The first line is how many numbers follow (at least 2). The second line holds the numbers, separated by spaces. Print the **second largest different** value. If every number is the same, print `None`."),
                    text("For this input:"),
                    code(
                        """
                        5
                        3 9 4 9 7
                        """,
                        "plain",
                    ),
                    text("the output is `7`: the largest is 9 (twice), and the next value below it is 7."),
                ),
                starterCode = """
                    fun main() {
                        val count = readln().trim().toInt()
                        val nums = readln().trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt() }.take(count)
                        // Print the second largest different value, or None
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "nums.distinct() removes the repeats.",
                    "Then .sortedDescending() puts the biggest value first.",
                    "If the result has fewer than 2 values print None, otherwise print the element at index 1.",
                ),
                solution = """
                    fun main() {
                        val count = readln().trim().toInt()
                        val nums = readln().trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt() }.take(count)
                        val ordered = nums.distinct().sortedDescending()
                        if (ordered.size < 2) {
                            println("None")
                        } else {
                            println(ordered[1])
                        }
                    }
                """,
                check = ExerciseCheck(
                    cases = listOf(
                        ExerciseCase("5\n3 9 4 9 7\n", "7"),
                        ExerciseCase("2\n5 5\n", "None"),
                        ExerciseCase("4\n-1 -5 -3 -2\n", "-2"),
                        ExerciseCase("3\n1 2 3\n", "2"),
                    ),
                ),
            ),
            LearnStepDef.Quiz(
                "pk-second-largest-q", "Quick check",
                prompt = "What is `listOf(4, 8, 8, 2).distinct().sortedDescending()`?",
                options = listOf("[8, 8, 4, 2]", "[8, 4, 2]", "[2, 4, 8]", "[4, 8, 2]"),
                correctIndex = 1,
                explanation = "`distinct()` keeps one 8, then `sortedDescending()` orders the rest from biggest to smallest.",
            ),
        ),
    ),

    // ---- 4. Word frequency ----
    LearnLessonDef(
        id = "pk-word-freq", title = "Word frequency", summary = "Count how often each word appears.",
        iconId = "sparkle", estMinutes = 7,
        steps = listOf(
            LearnStepDef.Concept(
                "pk-word-freq-c", "Counting with a map",
                listOf(
                    text("A `MutableMap<String, Int>` is a natural tally sheet: the word is the key and its count is the value."),
                    code(
                        """
                        val counts = mutableMapOf<String, Int>()
                        for (w in listOf("a", "b", "a")) {
                            counts[w] = (counts[w] ?: 0) + 1
                        }
                        println(counts) // {a=2, b=1}
                        """
                    ),
                    tip("`mutableMapOf()` remembers the order keys were first added, so looping over it lists words in the order they first appeared."),
                ),
            ),
            LearnStepDef.Interactive(
                "pk-word-freq-i", "Tally the words",
                listOf(
                    text("The input is one line of words separated by spaces (sometimes more than one space). Treat upper and lower case as the same word. Print each word in **lowercase** with its count, as `word: count`, one per line, in the order each word first appears."),
                    text("For the input `the cat and the hat` the output is:"),
                    code(
                        """
                        the: 2
                        cat: 1
                        and: 1
                        hat: 1
                        """,
                        "plain",
                    ),
                ),
                starterCode = """
                    fun main() {
                        val line = readln()
                        // Count each word (ignoring case) and print word: count lines
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "Split into words: line.lowercase().split(\" \").filter { it.isNotEmpty() }",
                    "Tally with counts[w] = (counts[w] ?: 0) + 1 inside a for loop.",
                    "Loop with for ((word, n) in counts) and print the word, a colon and a space, then n.",
                ),
                solution = """
                    fun main() {
                        val words = readln().lowercase().split(" ").filter { it.isNotEmpty() }
                        val counts = mutableMapOf<String, Int>()
                        for (w in words) {
                            counts[w] = (counts[w] ?: 0) + 1
                        }
                        for ((word, n) in counts) {
                            println("${'$'}word: ${'$'}n")
                        }
                    }
                """,
                check = ExerciseCheck(
                    cases = listOf(
                        ExerciseCase("the cat and the hat\n", "the: 2\ncat: 1\nand: 1\nhat: 1"),
                        ExerciseCase("Hello hello HELLO\n", "hello: 3"),
                        ExerciseCase("one\n", "one: 1"),
                        ExerciseCase("b  a b\n", "b: 2\na: 1"),
                    ),
                ),
            ),
        ),
    ),

    // ---- 5. Run-length encoding ----
    LearnLessonDef(
        id = "pk-rle", title = "Run-length encoding", summary = "Squeeze repeated letters into counts.",
        iconId = "sparkle", estMinutes = 8,
        steps = listOf(
            LearnStepDef.Concept(
                "pk-rle-c", "Runs of letters",
                listOf(
                    text("**Run-length encoding** replaces each run of the same letter with the letter and the length of the run. `aaabcc` has a run of three `a`, one `b` and two `c`, so it becomes `a3b1c2`."),
                    text("One way: walk the string with an index. At each new letter, count how many times it repeats while moving the index forward, then append the letter and the count to a `StringBuilder`."),
                    code(
                        """
                        val sb = StringBuilder()
                        sb.append('a').append(3)
                        println(sb) // a3
                        """
                    ),
                ),
            ),
            LearnStepDef.Interactive(
                "pk-rle-i", "Encode the runs",
                listOf(
                    text("The input is one line of lowercase letters (at least one). Print its run-length encoding: every run as the letter followed by how many times it repeats, all on one line."),
                    text("For the input `aaabcc` the output is `a3b1c2`."),
                ),
                starterCode = """
                    fun main() {
                        val s = readln().trim()
                        // Print the run-length encoding of s
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "Use var i = 0 and a while (i < s.length) loop.",
                    "Inside it, remember c = s[i], then count with an inner while (i < s.length && s[i] == c) { run++; i++ }.",
                    "After each run, sb.append(c).append(run). Print sb at the end.",
                ),
                solution = """
                    fun main() {
                        val s = readln().trim()
                        val sb = StringBuilder()
                        var i = 0
                        while (i < s.length) {
                            val c = s[i]
                            var run = 0
                            while (i < s.length && s[i] == c) {
                                run++
                                i++
                            }
                            sb.append(c).append(run)
                        }
                        println(sb)
                    }
                """,
                check = ExerciseCheck(
                    cases = listOf(
                        ExerciseCase("aaabcc\n", "a3b1c2"),
                        ExerciseCase("x\n", "x1"),
                        ExerciseCase("abab\n", "a1b1a1b1"),
                        ExerciseCase("zzzzzzzzzzzz\n", "z12"),
                    ),
                ),
            ),
            LearnStepDef.Quiz(
                "pk-rle-q", "Quick check",
                prompt = "What is the run-length encoding of `aabbba`?",
                options = listOf("a3b3", "a2b3a1", "a2b3", "ab3a"),
                correctIndex = 1,
                explanation = "The final `a` starts a new run, so it is encoded separately: `a2`, `b3`, `a1`.",
            ),
        ),
    ),

    // ---- 6. Anagrams ----
    LearnLessonDef(
        id = "pk-anagram", title = "Anagram check", summary = "Same letters, different order.",
        iconId = "sparkle", estMinutes = 6,
        steps = listOf(
            LearnStepDef.Concept(
                "pk-anagram-c", "Sort the letters",
                listOf(
                    text("Two words are **anagrams** when they use exactly the same letters the same number of times, like `listen` and `silent`."),
                    text("A neat trick: sort the letters of both words. Anagrams end up identical."),
                    code(
                        """
                        println("listen".toList().sorted()) // [e, i, l, n, s, t]
                        println("silent".toList().sorted()) // [e, i, l, n, s, t]
                        """
                    ),
                    tip("Comparing sets of letters is not enough: `aab` and `abb` have the same set but are not anagrams."),
                ),
            ),
            LearnStepDef.Interactive(
                "pk-anagram-i", "Anagrams or not?",
                listOf(
                    text("The input is two words, each on its own line. Ignoring upper and lower case, print `Anagrams` if they are anagrams of each other, otherwise `Not anagrams`."),
                    text("For the input `Dusty` then `study` the output is `Anagrams`."),
                ),
                starterCode = """
                    fun main() {
                        val first = readln().trim()
                        val second = readln().trim()
                        // Print Anagrams or Not anagrams
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "Lowercase both words first.",
                    "word.toList().sorted() gives the letters in order.",
                    "Compare the two sorted lists with ==.",
                ),
                solution = """
                    fun main() {
                        val first = readln().trim().lowercase()
                        val second = readln().trim().lowercase()
                        if (first.toList().sorted() == second.toList().sorted()) {
                            println("Anagrams")
                        } else {
                            println("Not anagrams")
                        }
                    }
                """,
                check = ExerciseCheck(
                    cases = listOf(
                        ExerciseCase("listen\nsilent\n", "Anagrams"),
                        ExerciseCase("Dusty\nstudy\n", "Anagrams"),
                        ExerciseCase("aab\nabb\n", "Not anagrams"),
                        ExerciseCase("abc\nabcd\n", "Not anagrams"),
                    ),
                ),
            ),
        ),
    ),

    // ---- 7. Primes up to N ----
    LearnLessonDef(
        id = "pk-primes", title = "Primes up to N", summary = "List every prime number in a range.",
        iconId = "sparkle", estMinutes = 7,
        steps = listOf(
            LearnStepDef.Concept(
                "pk-primes-c", "Testing for primes",
                listOf(
                    text("A **prime** is a whole number above 1 whose only divisors are 1 and itself: 2, 3, 5, 7, 11 and so on."),
                    text("To test `n`, try dividing it by `d = 2, 3, 4, ...`. If any `n % d == 0`, it is not prime. You only need to try while `d * d <= n`: if `n` had a bigger divisor, it would also have a smaller partner you already tried."),
                    code(
                        """
                        fun isPrime(n: Int): Boolean {
                            if (n < 2) return false
                            var d = 2
                            while (d * d <= n) {
                                if (n % d == 0) return false
                                d++
                            }
                            return true
                        }
                        """
                    ),
                    tip("`joinToString(\" \")` turns a list into one line with spaces between the items."),
                ),
            ),
            LearnStepDef.Interactive(
                "pk-primes-i", "List the primes",
                listOf(
                    text("The input is one whole number N. Print every prime from 2 up to and including N on one line, separated by single spaces. If there are none, print `None`."),
                    text("For the input `10` the output is `2 3 5 7`."),
                ),
                starterCode = """
                    fun isPrime(n: Int): Boolean {
                        // Return true when n is prime
                        return false
                    }

                    fun main() {
                        val n = readln().trim().toInt()
                        // Print the primes from 2 to n, or None
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "Finish isPrime first: numbers below 2 are not prime, then try divisors while d * d <= n.",
                    "(2..n).filter { isPrime(it) } collects the primes. When n is below 2 the range is simply empty.",
                    "If the list is empty print None, otherwise print primes.joinToString(\" \").",
                ),
                solution = """
                    fun isPrime(n: Int): Boolean {
                        if (n < 2) return false
                        var d = 2
                        while (d * d <= n) {
                            if (n % d == 0) return false
                            d++
                        }
                        return true
                    }

                    fun main() {
                        val n = readln().trim().toInt()
                        val primes = (2..n).filter { isPrime(it) }
                        if (primes.isEmpty()) {
                            println("None")
                        } else {
                            println(primes.joinToString(" "))
                        }
                    }
                """,
                check = ExerciseCheck(
                    cases = listOf(
                        ExerciseCase("10\n", "2 3 5 7"),
                        ExerciseCase("1\n", "None"),
                        ExerciseCase("2\n", "2"),
                        ExerciseCase("30\n", "2 3 5 7 11 13 17 19 23 29"),
                    ),
                ),
            ),
            LearnStepDef.Quiz(
                "pk-primes-q", "Quick check",
                prompt = "To test whether 49 is prime, what is the biggest divisor you need to try?",
                options = listOf("48", "24", "7", "2"),
                correctIndex = 2,
                explanation = "You stop once `d * d` passes 49, so 7 is the last one to try, and 49 % 7 == 0 shows 49 is not prime.",
            ),
        ),
    ),

    // ---- 8. Tiny calculator ----
    LearnLessonDef(
        id = "pk-calculator", title = "A tiny calculator", summary = "Parse \"3 + 4\" and work it out.",
        iconId = "sparkle", estMinutes = 7,
        steps = listOf(
            LearnStepDef.Concept(
                "pk-calculator-c", "Split, then decide",
                listOf(
                    text("An expression like `3 + 4` splits on spaces into three parts: a number, an operator and another number. A `when` on the operator picks the right sum:"),
                    code(
                        """
                        val parts = "3 + 4".split(" ")
                        val a = parts[0].toInt()
                        val b = parts[2].toInt()
                        when (parts[1]) {
                            "+" -> println(a + b)
                            "-" -> println(a - b)
                        }
                        """
                    ),
                    note("Dividing two `Int` values keeps only the whole part: `7 / 2` is `3`. Dividing by zero crashes the program, so check for it first."),
                ),
            ),
            LearnStepDef.Interactive(
                "pk-calculator-i", "Work it out",
                listOf(
                    text("The input is one line: a whole number, a space, one of `+`, `-`, `*` or `/`, a space, and another whole number. Numbers may be negative. Print the result. For `/` use whole-number division, and if the second number is 0 print `Cannot divide by zero`."),
                    text("For the input `3 + 4` the output is `7`."),
                ),
                starterCode = """
                    fun main() {
                        val parts = readln().trim().split(" ")
                        // parts[0] is the first number, parts[1] the operator, parts[2] the second number
                        // Print the result
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "Convert the numbers with parts[0].toInt() and parts[2].toInt(). \"-2\".toInt() is -2.",
                    "Use when (parts[1]) { \"+\" -> ...; \"-\" -> ...; \"*\" -> ...; \"/\" -> ... }",
                    "In the \"/\" branch, check if (b == 0) before dividing.",
                ),
                solution = """
                    fun main() {
                        val parts = readln().trim().split(" ").filter { it.isNotEmpty() }
                        val a = parts[0].toInt()
                        val op = parts[1]
                        val b = parts[2].toInt()
                        when (op) {
                            "+" -> println(a + b)
                            "-" -> println(a - b)
                            "*" -> println(a * b)
                            "/" -> if (b == 0) println("Cannot divide by zero") else println(a / b)
                            else -> println("Unknown operator")
                        }
                    }
                """,
                check = ExerciseCheck(
                    cases = listOf(
                        ExerciseCase("2 - 9\n", "-7"),
                        ExerciseCase("6 * -2\n", "-12"),
                        ExerciseCase("7 / 2\n", "3"),
                        ExerciseCase("5 / 0\n", "Cannot divide by zero"),
                    ),
                ),
            ),
            LearnStepDef.Quiz(
                "pk-calculator-q", "Quick check",
                prompt = "In Kotlin, what does `println(-7 / 2)` print?",
                options = listOf("-4", "-3", "-3.5", "3"),
                correctIndex = 1,
                explanation = "Integer division drops the fraction, rounding toward zero: -3.5 becomes -3.",
            ),
        ),
    ),
)

// ===========================================================================
// Java: Practice (Java 8, stdin-driven classic problems)
// ===========================================================================

internal fun javaPractice() = LearnTrackDef(
    id = "java-practice",
    title = "Java: Practice",
    subtitle = "Classic problems, checked against real input",
    iconId = "java",
    accentColor = ACCENT_PRACTICE,
    language = "java",
    category = "Java",
    level = "Intermediate",
    prerequisites = listOf("java-basics"),
    lessons = listOf(
        // ---- 1. Reverse words ----
        LearnLessonDef(
            id = "pj-reverse-words", title = "Reverse the words", summary = "Flip the order of words in a sentence.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "pj-reverse-words-c", "Lines and words",
                    listOf(
                        text("`Scanner.nextLine()` reads a whole line. `split(\"\\\\s+\")` cuts it at every run of spaces, so double spaces do not create empty words. Call `trim()` first so leading spaces do not either:"),
                        code(
                            """
                            String line = "  I love   Java ";
                            String[] words = line.trim().split("\\s+");
                            // words is {"I", "love", "Java"}
                            """,
                            "java",
                        ),
                        tip("A `StringBuilder` is the efficient way to glue many pieces of text together in a loop."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "pj-reverse-words-i", "Words in reverse",
                    listOf(
                        text("The input is one line of words, which may be separated by more than one space. Print the words in reverse order, separated by single spaces."),
                        text("For the input `I love Java` the output is `Java love I`."),
                    ),
                    starterCode = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                String line = in.nextLine();
                                // Print the words of line in reverse order
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "String[] words = line.trim().split(\"\\\\s+\");",
                        "Loop backwards: for (int i = words.length - 1; i >= 0; i--)",
                        "Append each word to a StringBuilder, adding a space between words but not after the last one.",
                    ),
                    solution = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                String line = in.nextLine();
                                String[] words = line.trim().split("\\s+");
                                StringBuilder sb = new StringBuilder();
                                for (int i = words.length - 1; i >= 0; i--) {
                                    sb.append(words[i]);
                                    if (i > 0) sb.append(" ");
                                }
                                System.out.println(sb.toString());
                            }
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("I love Java\n", "Java love I"),
                            ExerciseCase("hello\n", "hello"),
                            ExerciseCase("one two three four\n", "four three two one"),
                            ExerciseCase("  spaced   out  \n", "out spaced"),
                        ),
                    ),
                ),
            ),
        ),

        // ---- 2. Vowels and consonants ----
        LearnLessonDef(
            id = "pj-vowels", title = "Vowels and consonants", summary = "Sort the letters of a line into two piles.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "pj-vowels-c", "Looking at each char",
                    listOf(
                        text("`line.charAt(i)` gives the `char` at position `i`. Characters can be compared like numbers, so `c >= 'a' && c <= 'z'` checks for a lowercase letter. `\"aeiou\".indexOf(c) >= 0` checks whether `c` is a vowel:"),
                        code(
                            """
                            String line = "Hi!".toLowerCase();
                            for (int i = 0; i < line.length(); i++) {
                                char c = line.charAt(i);
                                boolean letter = c >= 'a' && c <= 'z';
                            }
                            """,
                            "java",
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "pj-vowels-i", "Count both kinds",
                    listOf(
                        text("The input is one line of text. Count the letters only (ignore digits, spaces and punctuation), treating upper and lower case the same. `a`, `e`, `i`, `o`, `u` are vowels; every other letter, including `y`, is a consonant. Print:"),
                        code(
                            """
                            Vowels: 3
                            Consonants: 7
                            """,
                            "plain",
                        ),
                        text("That is the output for the input `Hello World`."),
                    ),
                    starterCode = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                String line = in.nextLine();
                                // Print Vowels: <count> and Consonants: <count>
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "Call line.toLowerCase() once, then loop over every char.",
                        "Skip anything that is not between 'a' and 'z'.",
                        "For a letter, \"aeiou\".indexOf(c) >= 0 means vowel, otherwise consonant.",
                    ),
                    solution = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                String line = in.nextLine().toLowerCase();
                                int vowels = 0;
                                int consonants = 0;
                                for (int i = 0; i < line.length(); i++) {
                                    char c = line.charAt(i);
                                    if (c >= 'a' && c <= 'z') {
                                        if ("aeiou".indexOf(c) >= 0) {
                                            vowels++;
                                        } else {
                                            consonants++;
                                        }
                                    }
                                }
                                System.out.println("Vowels: " + vowels);
                                System.out.println("Consonants: " + consonants);
                            }
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("Hello World\n", "Vowels: 3\nConsonants: 7"),
                            ExerciseCase("AEIOU\n", "Vowels: 5\nConsonants: 0"),
                            ExerciseCase("rhythm 123!\n", "Vowels: 0\nConsonants: 6"),
                            ExerciseCase("Java 8\n", "Vowels: 2\nConsonants: 2"),
                        ),
                    ),
                ),
            ),
        ),

        // ---- 3. Max of an array ----
        LearnLessonDef(
            id = "pj-max-array", title = "Biggest and where", summary = "Find the largest value and its position.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "pj-max-array-c", "Start from the first element",
                    listOf(
                        text("To find the largest value in an array, keep the best seen so far and compare each element to it. Start from the **first element**, not from 0, or an array of only negative numbers gives the wrong answer:"),
                        code(
                            """
                            int[] a = {-4, -1, -9};
                            int max = a[0];
                            for (int i = 1; i < a.length; i++) {
                                if (a[i] > max) max = a[i];
                            }
                            // max is -1
                            """,
                            "java",
                        ),
                        tip("Using `>` rather than `>=` keeps the first position when the largest value appears more than once."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "pj-max-array-i", "Find the maximum",
                    listOf(
                        text("The input is a count (at least 1), then that many whole numbers. Print `Max: <value> at index <i>`, where `i` is the **first** position of the largest value, counting from 0."),
                        text("For the input `5` followed by `4 9 2 9 1` the output is:"),
                        code("Max: 9 at index 1", "plain"),
                    ),
                    starterCode = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int count = in.nextInt();
                                int[] nums = new int[count];
                                for (int i = 0; i < count; i++) {
                                    nums[i] = in.nextInt();
                                }
                                // Print Max: <value> at index <i>
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "Start with int max = nums[0]; int index = 0;",
                        "Loop from i = 1 and update both when nums[i] > max.",
                        "System.out.println(\"Max: \" + max + \" at index \" + index);",
                    ),
                    solution = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int count = in.nextInt();
                                int[] nums = new int[count];
                                for (int i = 0; i < count; i++) {
                                    nums[i] = in.nextInt();
                                }
                                int max = nums[0];
                                int index = 0;
                                for (int i = 1; i < count; i++) {
                                    if (nums[i] > max) {
                                        max = nums[i];
                                        index = i;
                                    }
                                }
                                System.out.println("Max: " + max + " at index " + index);
                            }
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("5\n4 9 2 9 1\n", "Max: 9 at index 1"),
                            ExerciseCase("1\n-7\n", "Max: -7 at index 0"),
                            ExerciseCase("4\n-3 -1 -8 -2\n", "Max: -1 at index 1"),
                            ExerciseCase("3\n0 0 5\n", "Max: 5 at index 2"),
                        ),
                    ),
                ),
            ),
        ),

        // ---- 4. Factorial with long ----
        LearnLessonDef(
            id = "pj-factorial", title = "Big factorials", summary = "When int is too small, reach for long.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "pj-factorial-c", "int overflows",
                    listOf(
                        text("`n!` is `1 * 2 * ... * n`, and it grows fast. An `int` holds values up to about 2.1 billion, but `13!` is 6,227,020,800. Java does not warn you: the value silently **overflows** and wraps around to a wrong number."),
                        text("A `long` holds values up to about 9.2 quintillion, enough for everything up to `20!`:"),
                        code(
                            """
                            long result = 1;
                            for (int i = 1; i <= 5; i++) {
                                result *= i;
                            }
                            // result is 120
                            """,
                            "java",
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "pj-factorial-i", "Compute n!",
                    listOf(
                        text("The input is one whole number `n` from 0 to 20. Print `n!`. Remember that `0!` is 1."),
                        text("For the input `5` the output is `120`."),
                    ),
                    starterCode = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int n = in.nextInt();
                                // Print n! (it can be much bigger than an int can hold)
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "Use long result = 1; so big values fit.",
                        "Multiply in a loop: for (int i = 2; i <= n; i++) result *= i;",
                        "When n is 0 the loop never runs and result stays 1, which is right.",
                    ),
                    solution = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int n = in.nextInt();
                                long result = 1;
                                for (int i = 2; i <= n; i++) {
                                    result *= i;
                                }
                                System.out.println(result);
                            }
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("5\n", "120"),
                            ExerciseCase("0\n", "1"),
                            ExerciseCase("13\n", "6227020800"),
                            ExerciseCase("20\n", "2432902008176640000"),
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "pj-factorial-q", "Quick check",
                    prompt = "What happens when an `int` calculation goes past the largest `int` value?",
                    options = listOf(
                        "Java throws an exception",
                        "The value wraps around to a wrong number, with no error",
                        "Java switches to a long automatically",
                        "The program will not compile",
                    ),
                    correctIndex = 1,
                    explanation = "Integer overflow is silent in Java. Choose a type big enough (like `long`) when values can grow large.",
                ),
            ),
        ),

        // ---- 5. GCD ----
        LearnLessonDef(
            id = "pj-gcd", title = "Greatest common divisor", summary = "Euclid's algorithm, over 2000 years old.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "pj-gcd-c", "Euclid's trick",
                    listOf(
                        text("The **greatest common divisor** (GCD) of two numbers is the biggest number that divides both. The GCD of 12 and 18 is 6."),
                        text("Euclid noticed that `gcd(a, b)` equals `gcd(b, a % b)`. Keep replacing the pair until the second number is 0; the first one is then the answer:"),
                        code(
                            """
                            gcd(12, 18) -> gcd(18, 12) -> gcd(12, 6) -> gcd(6, 0) = 6
                            """,
                            "plain",
                        ),
                    ),
                ),
                LearnStepDef.Interactive(
                    "pj-gcd-i", "Find the GCD",
                    listOf(
                        text("The input is two whole numbers on one line, both 0 or more and not both 0. Print their greatest common divisor."),
                        text("For the input `12 18` the output is `6`."),
                    ),
                    starterCode = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int a = in.nextInt();
                                int b = in.nextInt();
                                // Print the greatest common divisor of a and b
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "Loop while (b != 0).",
                        "Inside: int t = a % b; a = b; b = t;",
                        "When the loop ends, a holds the GCD.",
                    ),
                    solution = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int a = in.nextInt();
                                int b = in.nextInt();
                                while (b != 0) {
                                    int t = a % b;
                                    a = b;
                                    b = t;
                                }
                                System.out.println(a);
                            }
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("12 18\n", "6"),
                            ExerciseCase("17 5\n", "1"),
                            ExerciseCase("0 9\n", "9"),
                            ExerciseCase("100 75\n", "25"),
                        ),
                    ),
                ),
            ),
        ),

        // ---- 6. Character frequency ----
        LearnLessonDef(
            id = "pj-char-freq", title = "Letter frequency", summary = "Count every letter with an array of 26.",
            iconId = "java", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "pj-char-freq-c", "An array as a tally",
                    listOf(
                        text("There are 26 letters, so an `int[26]` makes a perfect tally: slot 0 for `a`, slot 1 for `b`, and so on. Because a `char` is a number underneath, `c - 'a'` gives the slot for letter `c`, and `(char) ('a' + i)` turns a slot back into its letter:"),
                        code(
                            """
                            int[] counts = new int[26];
                            char c = 'c';
                            counts[c - 'a']++;          // counts[2] is now 1
                            char back = (char) ('a' + 2); // 'c'
                            """,
                            "java",
                        ),
                        tip("Walking the array from 0 to 25 prints the letters in alphabetical order for free."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "pj-char-freq-i", "Tally the letters",
                    listOf(
                        text("The input is one line of text. Count each letter, treating upper and lower case the same and ignoring anything that is not a letter. Print every letter that appears, in alphabetical order, as `letter: count`, one per line."),
                        text("For the input `banana` the output is:"),
                        code(
                            """
                            a: 3
                            b: 1
                            n: 2
                            """,
                            "plain",
                        ),
                    ),
                    starterCode = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                String line = in.nextLine();
                                int[] counts = new int[26];
                                // Count the letters, then print letter: count for each one that appears
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "Lowercase the line, then for each char c between 'a' and 'z' do counts[c - 'a']++.",
                        "Then loop i from 0 to 25 and skip slots that are 0.",
                        "System.out.println((char) ('a' + i) + \": \" + counts[i]);",
                    ),
                    solution = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                String line = in.nextLine().toLowerCase();
                                int[] counts = new int[26];
                                for (int i = 0; i < line.length(); i++) {
                                    char c = line.charAt(i);
                                    if (c >= 'a' && c <= 'z') {
                                        counts[c - 'a']++;
                                    }
                                }
                                for (int i = 0; i < 26; i++) {
                                    if (counts[i] > 0) {
                                        System.out.println((char) ('a' + i) + ": " + counts[i]);
                                    }
                                }
                            }
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("banana\n", "a: 3\nb: 1\nn: 2"),
                            ExerciseCase("hello world\n", "d: 1\ne: 1\nh: 1\nl: 3\no: 2\nr: 1\nw: 1"),
                            ExerciseCase("Mississippi\n", "i: 4\nm: 1\np: 2\ns: 4"),
                            ExerciseCase("zzz\n", "z: 3"),
                        ),
                    ),
                ),
                LearnStepDef.Quiz(
                    "pj-char-freq-q", "Quick check",
                    prompt = "What is the value of `'d' - 'a'` in Java?",
                    options = listOf("3", "4", "'c'", "It does not compile"),
                    correctIndex = 0,
                    explanation = "Characters are numbers underneath, and `d` is three letters after `a`, so the result is the int 3.",
                ),
            ),
        ),

        // ---- 7. Binary ----
        LearnLessonDef(
            id = "pj-binary", title = "To binary", summary = "Write a number in base 2.",
            iconId = "java", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "pj-binary-c", "Divide by two",
                    listOf(
                        text("To write a number in binary, keep dividing by 2. Each remainder (`n % 2`) is one binary digit, starting from the **rightmost**. For 6: remainder 0, then 1, then 1, so 6 is `110`."),
                        text("Since the digits come out backwards, append them to a `StringBuilder` and call `reverse()` at the end."),
                        tip("Java already has `Integer.toBinaryString(n)`, but writing the loop yourself is the point of this exercise."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "pj-binary-i", "Convert to binary",
                    listOf(
                        text("The input is one whole number, 0 or more. Print it in binary with no leading zeros. The number 0 is printed as `0`."),
                        text("For the input `10` the output is `1010`."),
                    ),
                    starterCode = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int n = in.nextInt();
                                // Print n in binary
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "Handle 0 first: print 0 and return.",
                        "while (n > 0) { sb.append(n % 2); n /= 2; }",
                        "Print sb.reverse().toString() at the end.",
                    ),
                    solution = """
                        import java.util.Scanner;

                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int n = in.nextInt();
                                if (n == 0) {
                                    System.out.println("0");
                                    return;
                                }
                                StringBuilder sb = new StringBuilder();
                                while (n > 0) {
                                    sb.append(n % 2);
                                    n /= 2;
                                }
                                System.out.println(sb.reverse().toString());
                            }
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("10\n", "1010"),
                            ExerciseCase("0\n", "0"),
                            ExerciseCase("255\n", "11111111"),
                            ExerciseCase("37\n", "100101"),
                        ),
                    ),
                ),
            ),
        ),

        // ---- 8. Capstone: balanced brackets ----
        LearnLessonDef(
            id = "pj-brackets", title = "Balanced brackets", kind = "capstone",
            summary = "Use a stack to check that every bracket is closed in order.",
            iconId = "java", estMinutes = 12,
            steps = listOf(
                LearnStepDef.Concept(
                    "pj-brackets-c", "A stack of open brackets",
                    listOf(
                        text("A string of brackets is **balanced** when every opening bracket is closed by the matching kind, in the right order: `([]{})` is balanced, `([)]` is not."),
                        text("The classic tool is a **stack**: last in, first out. Push every opening bracket. For a closing bracket, pop the most recent opener and check that it matches. At the end the stack must be empty."),
                        code(
                            """
                            import java.util.ArrayDeque;
                            import java.util.Deque;

                            Deque<Character> stack = new ArrayDeque<>();
                            stack.push('(');
                            char top = stack.pop(); // '('
                            boolean none = stack.isEmpty(); // true
                            """,
                            "java",
                        ),
                        tip("There are two ways to fail: a closing bracket arrives when the stack is empty, or the string ends with openers still on the stack."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "pj-brackets-i", "Check the brackets",
                    listOf(
                        text("The first line is how many strings follow. Each following line is one string made only of the characters `()[]{}`. For each string print `YES` if it is balanced, otherwise `NO`, one answer per line."),
                        text("For this input:"),
                        code(
                            """
                            3
                            ()[]{}
                            ([{}])
                            (]
                            """,
                            "plain",
                        ),
                        text("the output is:"),
                        code(
                            """
                            YES
                            YES
                            NO
                            """,
                            "plain",
                        ),
                    ),
                    starterCode = """
                        import java.util.ArrayDeque;
                        import java.util.Deque;
                        import java.util.Scanner;

                        public class Main {
                            static boolean balanced(String s) {
                                // Use a Deque<Character> as a stack
                                return false;
                            }

                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int count = in.nextInt();
                                // Read each string with in.next() and print YES or NO
                            }
                        }
                    """,
                    language = "java",
                    hints = listOf(
                        "In main: for (int i = 0; i < count; i++) { String s = in.next(); System.out.println(balanced(s) ? \"YES\" : \"NO\"); }",
                        "In balanced: push c when it is '(', '[' or '{'. Otherwise, if the stack is empty return false, else pop the opener.",
                        "A ')' must pop a '(', a ']' a '[', and a '}' a '{'. At the end, return stack.isEmpty().",
                    ),
                    solution = """
                        import java.util.ArrayDeque;
                        import java.util.Deque;
                        import java.util.Scanner;

                        public class Main {
                            static boolean balanced(String s) {
                                Deque<Character> stack = new ArrayDeque<>();
                                for (int i = 0; i < s.length(); i++) {
                                    char c = s.charAt(i);
                                    if (c == '(' || c == '[' || c == '{') {
                                        stack.push(c);
                                    } else {
                                        if (stack.isEmpty()) return false;
                                        char opener = stack.pop();
                                        if (c == ')' && opener != '(') return false;
                                        if (c == ']' && opener != '[') return false;
                                        if (c == '}' && opener != '{') return false;
                                    }
                                }
                                return stack.isEmpty();
                            }

                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                int count = in.nextInt();
                                for (int i = 0; i < count; i++) {
                                    String s = in.next();
                                    System.out.println(balanced(s) ? "YES" : "NO");
                                }
                            }
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("3\n()[]{}\n([{}])\n(]\n", "YES\nYES\nNO"),
                            ExerciseCase("2\n((\n())\n", "NO\nNO"),
                            ExerciseCase("4\n{[()()]}\n([)]\n)\n[]\n", "YES\nNO\nNO\nYES"),
                            ExerciseCase("1\n{{[[(())]]}}\n", "YES"),
                        ),
                    ),
                ),
            ),
        ),
    ),
)
