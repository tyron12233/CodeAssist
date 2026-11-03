package dev.ide.core.backend

/**
 * Checkpoint and capstone lessons appended to the end of existing tracks. A checkpoint is a short quiz over the
 * whole track; a capstone is a small graded project that pulls the track's ideas together. Lesson ids are
 * `<short>-checkpoint` / `<short>-capstone`, step ids are prefixed `ms-<short>-`.
 *
 * Interactive exercises follow the [LearnContent] convention: complete files in the default package, Kotlin with
 * a top-level `fun main()`, Java with a `public class Main` (Java 8 only).
 */
internal fun trackMilestones(trackId: String): List<LearnLessonDef> = when (trackId) {
    "kotlin-next" -> kotlinNextMilestones()
    "kotlin-oo" -> kotlinOoMilestones()
    "kotlin-coroutines" -> kotlinCoroutinesMilestones()
    "compose-intro" -> composeIntroMilestones()
    "compose-advanced" -> composeAdvancedMilestones()
    "java-beyond" -> javaBeyondMilestones()
    "java-more" -> javaMoreMilestones()
    "android-basics" -> androidBasicsMilestones()
    "getting-started" -> gettingStartedMilestones()
    else -> emptyList()
}

private const val CHECKPOINT_SUMMARY = "Five questions across the whole track. Aim to get them right first time."

private fun msCheckpoint(short: String, title: String, quizzes: List<LearnStepDef.Quiz>) = LearnLessonDef(
    id = "$short-checkpoint", title = title, kind = "checkpoint",
    summary = CHECKPOINT_SUMMARY,
    iconId = "check", estMinutes = 4,
    steps = quizzes,
)

private fun msQuiz(short: String, n: Int, prompt: String, options: List<String>, correct: Int, explanation: String) =
    LearnStepDef.Quiz(
        "ms-$short-q$n", "Question $n",
        prompt = prompt,
        options = options,
        correctIndex = correct,
        explanation = explanation,
    )

private const val CASES_TIP =
    "This exercise is checked against several different inputs, so the program has to really work it out every time."

// ===========================================================================
// Kotlin: Next Steps
// ===========================================================================

private fun kotlinNextMilestones() = listOf(
    msCheckpoint(
        "kn", "Kotlin: Next Steps review",
        listOf(
            msQuiz(
                "kn", 1,
                prompt = "What does this print?\n\nval name: String? = null\nprintln(name?.length ?: 0)",
                options = listOf("null", "0", "It crashes with a NullPointerException", "It doesn't compile"),
                correct = 1,
                explanation = "`?.` gives null because name is null, so the Elvis operator `?:` supplies the fallback 0.",
            ),
            msQuiz(
                "kn", 2,
                prompt = "What does this print?\n\nval nums = listOf(1, 2, 3, 4)\nprintln(nums.map { it * 10 }.filter { it > 15 })",
                options = listOf("[2, 3, 4]", "90", "[10, 20, 30, 40]", "[20, 30, 40]"),
                correct = 3,
                explanation = "map first makes [10, 20, 30, 40], then filter keeps only the values above 15.",
            ),
            msQuiz(
                "kn", 3,
                prompt = "What does this print?\n\ndata class User(val name: String, val age: Int)\nprintln(User(\"Ana\", 30))",
                options = listOf("User(name=Ana, age=30)", "User@1b6d3586", "Ana 30", "{name: Ana, age: 30}"),
                correct = 0,
                explanation = "A data class generates a toString that lists each property by name.",
            ),
            msQuiz(
                "kn", 4,
                prompt = "What does this print?\n\nfun applyOp(x: Int, op: (Int) -> Int): Int = op(x)\nprintln(applyOp(3) { it + 4 })",
                options = listOf("3", "4", "7", "12"),
                correct = 2,
                explanation = "The trailing lambda is passed as op, and applyOp calls it with 3, giving 3 + 4.",
            ),
            msQuiz(
                "kn", 5,
                prompt = "What does this print?\n\nval x = 7\nval label = when (x) {\n    1 -> \"one\"\n    in 2..5 -> \"few\"\n    else -> \"many\"\n}\nprintln(label)",
                options = listOf("one", "many", "few", "7"),
                correct = 1,
                explanation = "7 is not 1 and not in 2..5, so the else branch supplies the value.",
            ),
        ),
    ),
    LearnLessonDef(
        id = "kn-capstone", title = "Grade book", kind = "capstone",
        summary = "Model students with a data class, then rank and grade them.",
        iconId = "kotlin", estMinutes = 15,
        steps = listOf(
            LearnStepDef.Concept(
                "ms-kn-cap-c", "Tools for the grade book",
                listOf(
                    text("This project uses everything from the track: a **data class**, a **nullable** property, list functions and a `when` expression. A few helpers make it easier:"),
                    code(
                        """
                        val parts = "Ana 92".split(" ")              // [Ana, 92]
                        val score: Int? = parts[1].toIntOrNull()   // 92, or null for a word like "absent"

                        val ranked = listOf(3, 9, 5).sortedByDescending { it }  // [9, 5, 3]
                        val names = listOf("Ben", "Kim").joinToString(", ")     // Ben, Kim
                        """
                    ),
                    text("`toIntOrNull()` is the null-safe cousin of `toInt()`: instead of crashing on text that isn't a number, it returns `null`. `sortedByDescending` keeps items with equal keys in their original order."),
                    tip(CASES_TIP),
                ),
            ),
            LearnStepDef.Interactive(
                "ms-kn-cap-i", "Grade book",
                listOf(
                    text("The first line of input is how many students follow. Each student line is a **name** and a **score**, separated by one space. A student who missed the test has a word instead of a number (for example `absent`)."),
                    text("Grades: **90 or more** is `A`, **75 to 89** is `B`, **50 to 74** is `C`, **below 50** is `F`."),
                    text("Print every student who has a score, **highest score first**, as `Name: score grade` (students with the same score keep their input order). Then print `Absent: ` followed by the absent names in input order, joined by `, `, or `none` when nobody was absent."),
                    text("For this input:"),
                    code(
                        """
                        4
                        Ana 92
                        Ben absent
                        Cleo 67
                        Dev 78
                        """,
                        "plain",
                    ),
                    text("the output is exactly:"),
                    code(
                        """
                        Ana: 92 A
                        Dev: 78 B
                        Cleo: 67 C
                        Absent: Ben
                        """,
                        "plain",
                    ),
                ),
                starterCode = """
                    data class Student(val name: String, val score: Int?)

                    fun main() {
                        val count = readln().trim().toInt()
                        val students = mutableListOf<Student>()
                        for (i in 1..count) {
                            val parts = readln().trim().split(" ")
                            // parts[0] is the name, parts[1] the score (or a word when absent)
                            // TODO: add a Student, using toIntOrNull() so a missing score becomes null
                        }
                        // TODO: print the students with a score, highest first, as "Name: score grade"
                        // TODO: print "Absent: " and the absent names joined by ", " (or "none")
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "Build each student with Student(parts[0], parts[1].toIntOrNull()), so a word like absent becomes null.",
                    "students.filter { it.score != null }.sortedByDescending { it.score ?: 0 } gives the ranked list.",
                    "Write fun grade(score: Int): String = when { score >= 90 -> \"A\" ... else -> \"F\" }, checking the biggest bound first.",
                ),
                solution = """
                    data class Student(val name: String, val score: Int?)

                    fun grade(score: Int): String = when {
                        score >= 90 -> "A"
                        score >= 75 -> "B"
                        score >= 50 -> "C"
                        else -> "F"
                    }

                    fun main() {
                        val count = readln().trim().toInt()
                        val students = mutableListOf<Student>()
                        for (i in 1..count) {
                            val parts = readln().trim().split(" ")
                            students.add(Student(parts[0], parts[1].toIntOrNull()))
                        }
                        val present = students.filter { it.score != null }.sortedByDescending { it.score ?: 0 }
                        for (s in present) {
                            val score = s.score ?: 0
                            println("${'$'}{s.name}: ${'$'}score ${'$'}{grade(score)}")
                        }
                        val absent = students.filter { it.score == null }.map { it.name }
                        val absentText = if (absent.isEmpty()) "none" else absent.joinToString(", ")
                        println("Absent: ${'$'}absentText")
                    }
                """,
                check = ExerciseCheck(
                    requireSource = listOf("when"),
                    cases = listOf(
                        ExerciseCase(
                            "4\nAna 92\nBen absent\nCleo 67\nDev 78\n",
                            "Ana: 92 A\nDev: 78 B\nCleo: 67 C\nAbsent: Ben",
                        ),
                        ExerciseCase(
                            "3\nZed 49\nAmy 50\nBo 90\n",
                            "Bo: 90 A\nAmy: 50 C\nZed: 49 F\nAbsent: none",
                        ),
                        ExerciseCase(
                            "2\nKim sick\nLee -\n",
                            "Absent: Kim, Lee",
                        ),
                        ExerciseCase(
                            "5\nMo 75\nIra 100\nJo absent\nPat 75\nSam 12\n",
                            "Ira: 100 A\nMo: 75 B\nPat: 75 B\nSam: 12 F\nAbsent: Jo",
                        ),
                    ),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Kotlin: Object-Oriented
// ===========================================================================

private fun kotlinOoMilestones() = listOf(
    msCheckpoint(
        "koo", "Kotlin: Object-Oriented review",
        listOf(
            msQuiz(
                "koo", 1,
                prompt = "open class Animal\nclass Cat : Animal()\n\nWhat happens if you remove `open` from Animal?",
                options = listOf(
                    "Nothing changes",
                    "Cat becomes abstract",
                    "It no longer compiles: Animal is final, so Cat can't extend it",
                    "Animal becomes an interface",
                ),
                correct = 2,
                explanation = "Kotlin classes are final by default, so a class must be open before another class can extend it.",
            ),
            msQuiz(
                "koo", 2,
                prompt = "What does this print?\n\nclass Point(val x: Int, val y: Int) {\n    fun sum(): Int = x + y\n}\nprintln(Point(4, 1).sum())",
                options = listOf("5", "41", "Point(4, 1)", "3"),
                correct = 0,
                explanation = "The primary constructor stores x = 4 and y = 1, and sum() adds them.",
            ),
            msQuiz(
                "koo", 3,
                prompt = "A class implements an interface's `fun greet(): String`. Which keyword must go in front of its own `fun greet()`?",
                options = listOf("open", "implements", "super", "override"),
                correct = 3,
                explanation = "Providing an interface member counts as overriding it, so Kotlin requires `override`.",
            ),
            msQuiz(
                "koo", 4,
                prompt = "What does this print?\n\nenum class Color { RED, GREEN, BLUE }\nprintln(Color.BLUE)",
                options = listOf("2", "BLUE", "Color(BLUE)", "blue"),
                correct = 1,
                explanation = "An enum constant prints as its name.",
            ),
            msQuiz(
                "koo", 5,
                prompt = "class Box {\n    companion object {\n        fun create(): String = \"box\"\n    }\n}\n\nHow do you call create()?",
                options = listOf("Box().create()", "companion.create()", "Box.create()", "create(Box)"),
                correct = 2,
                explanation = "Companion object members are called on the class itself, like a static method.",
            ),
        ),
    ),
    LearnLessonDef(
        id = "koo-capstone", title = "Shape calculator", kind = "capstone",
        summary = "Build shapes behind an interface and size them with an enum.",
        iconId = "kotlin", estMinutes = 15,
        steps = listOf(
            LearnStepDef.Concept(
                "ms-koo-cap-c", "One interface, many shapes",
                listOf(
                    text("An **interface** lets different classes be used the same way. Code that only knows it has a `Shape` can still ask for its area, and each class answers in its own way with `override`. An **enum** names a fixed set of results:"),
                    code(
                        """
                        interface Shape {
                            fun area(): Int
                        }

                        class Square(val side: Int) : Shape {
                            override fun area(): Int = side * side
                        }

                        enum class Size { SMALL, LARGE }

                        fun sizeOf(shape: Shape): Size = if (shape.area() < 10) Size.SMALL else Size.LARGE

                        println(sizeOf(Square(4)))  // LARGE
                        """
                    ),
                    text("A `when` on a string can pick which class to build, and its `else` branch can give `null` for input you don't recognise."),
                    tip(CASES_TIP),
                ),
            ),
            LearnStepDef.Interactive(
                "ms-koo-cap-i", "Shape calculator",
                listOf(
                    text("The first line of input is how many shapes follow. Each shape line is either `rect <width> <height>` or `square <side>`, using whole numbers."),
                    text("Implement `Rectangle` and `Square` from the `Shape` interface. For each shape print `<description>: area <a>, perimeter <p>, <SIZE>`, where the description is `Rectangle 3x4` or `Square 5`, and the size is `SMALL` when the area is below 10, `MEDIUM` when it is below 50, and `LARGE` otherwise."),
                    text("A line with any other shape name prints `Unknown shape: <name>` and is skipped. Finish with `Total area: <sum of the areas>`."),
                    text("For this input:"),
                    code(
                        """
                        3
                        rect 3 4
                        square 8
                        rect 1 2
                        """,
                        "plain",
                    ),
                    text("the output is exactly:"),
                    code(
                        """
                        Rectangle 3x4: area 12, perimeter 14, MEDIUM
                        Square 8: area 64, perimeter 32, LARGE
                        Rectangle 1x2: area 2, perimeter 6, SMALL
                        Total area: 78
                        """,
                        "plain",
                    ),
                ),
                starterCode = """
                    enum class Size { SMALL, MEDIUM, LARGE }

                    interface Shape {
                        fun describe(): String
                        fun area(): Int
                        fun perimeter(): Int
                    }

                    // TODO: class Rectangle(val width: Int, val height: Int) : Shape
                    // TODO: class Square(val side: Int) : Shape

                    fun main() {
                        val count = readln().trim().toInt()
                        for (i in 1..count) {
                            val parts = readln().trim().split(" ")
                            // TODO: build the shape named by parts[0] and print its line
                        }
                        // TODO: print the total area
                    }
                """,
                language = "kotlin",
                hints = listOf(
                    "Each class overrides all three members, for example override fun area(): Int = width * height.",
                    "val shape: Shape? = when (parts[0]) { \"rect\" -> Rectangle(...); \"square\" -> Square(...); else -> null }",
                    "Write fun sizeOf(shape: Shape): Size = when { shape.area() < 10 -> Size.SMALL ... else -> Size.LARGE }.",
                ),
                solution = """
                    enum class Size { SMALL, MEDIUM, LARGE }

                    interface Shape {
                        fun describe(): String
                        fun area(): Int
                        fun perimeter(): Int
                    }

                    class Rectangle(val width: Int, val height: Int) : Shape {
                        override fun describe(): String = "Rectangle ${'$'}{width}x${'$'}height"
                        override fun area(): Int = width * height
                        override fun perimeter(): Int = 2 * (width + height)
                    }

                    class Square(val side: Int) : Shape {
                        override fun describe(): String = "Square ${'$'}side"
                        override fun area(): Int = side * side
                        override fun perimeter(): Int = 4 * side
                    }

                    fun sizeOf(shape: Shape): Size = when {
                        shape.area() < 10 -> Size.SMALL
                        shape.area() < 50 -> Size.MEDIUM
                        else -> Size.LARGE
                    }

                    fun main() {
                        val count = readln().trim().toInt()
                        var total = 0
                        for (i in 1..count) {
                            val parts = readln().trim().split(" ")
                            val shape: Shape? = when (parts[0]) {
                                "rect" -> Rectangle(parts[1].toInt(), parts[2].toInt())
                                "square" -> Square(parts[1].toInt())
                                else -> null
                            }
                            if (shape == null) {
                                println("Unknown shape: ${'$'}{parts[0]}")
                            } else {
                                println("${'$'}{shape.describe()}: area ${'$'}{shape.area()}, perimeter ${'$'}{shape.perimeter()}, ${'$'}{sizeOf(shape)}")
                                total += shape.area()
                            }
                        }
                        println("Total area: ${'$'}total")
                    }
                """,
                check = ExerciseCheck(
                    requireSource = listOf("override", "Size."),
                    cases = listOf(
                        ExerciseCase(
                            "3\nrect 3 4\nsquare 8\nrect 1 2\n",
                            "Rectangle 3x4: area 12, perimeter 14, MEDIUM\nSquare 8: area 64, perimeter 32, LARGE\nRectangle 1x2: area 2, perimeter 6, SMALL\nTotal area: 78",
                        ),
                        ExerciseCase(
                            "2\nrect 2 5\nrect 5 10\n",
                            "Rectangle 2x5: area 10, perimeter 14, MEDIUM\nRectangle 5x10: area 50, perimeter 30, LARGE\nTotal area: 60",
                        ),
                        ExerciseCase(
                            "3\ncircle 4\nsquare 3\ntriangle 2 2\n",
                            "Unknown shape: circle\nSquare 3: area 9, perimeter 12, SMALL\nUnknown shape: triangle\nTotal area: 9",
                        ),
                        ExerciseCase(
                            "1\nsquare 1\n",
                            "Square 1: area 1, perimeter 4, SMALL\nTotal area: 1",
                        ),
                    ),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Kotlin: Coroutines
// ===========================================================================

private fun kotlinCoroutinesMilestones() = listOf(
    msCheckpoint(
        "kco", "Kotlin: Coroutines review",
        listOf(
            msQuiz(
                "kco", 1,
                prompt = "What does runBlocking { } do?",
                options = listOf(
                    "Starts a new thread for every line inside it",
                    "Prevents any coroutine from running",
                    "Runs coroutines inside it and blocks the current thread until they all finish",
                    "Makes delay() block the thread like Thread.sleep()",
                ),
                correct = 2,
                explanation = "runBlocking bridges normal code into coroutines and waits for its children before returning.",
            ),
            msQuiz(
                "kco", 2,
                prompt = "What does async { 2 + 3 } give back?",
                options = listOf(
                    "A Deferred; call await() on it to get 5",
                    "The Int 5, straight away",
                    "Unit",
                    "A Flow of 2 and 3",
                ),
                correct = 0,
                explanation = "async returns a Deferred, a promise of a result that await() suspends for.",
            ),
            msQuiz(
                "kco", 3,
                prompt = "What does this print?\n\nrunBlocking {\n    launch {\n        delay(50)\n        println(\"B\")\n    }\n    println(\"A\")\n}",
                options = listOf("B, then A", "Only A: launch never runs", "Only B", "A, then B"),
                correct = 3,
                explanation = "The launched coroutine is queued and then delays, so A prints first; runBlocking waits for B.",
            ),
            msQuiz(
                "kco", 4,
                prompt = "You need two values computed concurrently and then added together. Which do you use?",
                options = listOf(
                    "launch, because it returns the result",
                    "async for each, then await() both",
                    "Thread.sleep() between two calls",
                    "flowOf(), then collect",
                ),
                correct = 1,
                explanation = "async runs work concurrently and hands back a result; launch is fire-and-forget and returns no value.",
            ),
            msQuiz(
                "kco", 5,
                prompt = "What does this print?\n\nflowOf(1, 2, 3).collect { println(it * 2) }",
                options = listOf("6", "1, 2 and 3, one per line", "2, 4 and 6, one per line", "Nothing until you call await()"),
                correct = 2,
                explanation = "collect receives each emitted value in turn, and the block prints it doubled.",
            ),
        ),
    ),
    LearnLessonDef(
        id = "kco-capstone", title = "Stock and orders", kind = "capstone",
        summary = "Fetch values concurrently with async, then filter and map a Flow.",
        iconId = "kotlin", estMinutes = 12,
        steps = listOf(
            LearnStepDef.Concept(
                "ms-kco-cap-c", "Combining results",
                listOf(
                    text("A `suspend fun` is a function that can pause, for example with `delay`. You call it from a coroutine. Start two of them with `async` and they run at the same time; `await()` waits for each result."),
                    text("A Flow can be transformed before you collect it. `filter` and `map` work like their list versions, and `toList()` collects every value into a `List`:"),
                    code(
                        """
                        import kotlinx.coroutines.*
                        import kotlinx.coroutines.flow.*

                        suspend fun fetchNumber(): Int {
                            delay(100)
                            return 21
                        }

                        fun main() {
                            runBlocking {
                                val a = async { fetchNumber() }
                                val b = async { fetchNumber() }
                                println(a.await() + b.await())  // 42, once both are done

                                val big = flowOf(5, 12, 30)
                                    .filter { it > 10 }
                                    .map { it * 2 }
                                    .toList()
                                println(big)  // [24, 60]
                            }
                        }
                        """
                    ),
                    tip("Print only after you have awaited or collected. Then the output is the same on every run, however the coroutines are scheduled."),
                ),
            ),
            LearnStepDef.Interactive(
                "ms-kco-cap-i", "Stock and orders",
                listOf(
                    text("`fetchStock` is provided. Inside `runBlocking`:"),
                    text("1. Fetch the stock of `apples` and of `pears` **concurrently** with `async`, add them once both are awaited, and print `Total stock: <total>`.\n2. Take the orders flow `4, 0, 9, 25, 6`. Keep only orders from **1 up to the total stock**, double each one (every item costs 2), and collect them with `toList()`. Print `Order costs: <list>`.\n3. Print `Revenue: <sum of the costs>`."),
                    text("The output must be exactly:"),
                    code(
                        """
                        Total stock: 19
                        Order costs: [8, 18, 12]
                        Revenue: 38
                        """,
                        "plain",
                    ),
                ),
                starterCode = """
                    import kotlinx.coroutines.*
                    import kotlinx.coroutines.flow.*

                    suspend fun fetchStock(item: String): Int {
                        delay(100)
                        return when (item) {
                            "apples" -> 12
                            "pears" -> 7
                            else -> 0
                        }
                    }

                    fun main() {
                        runBlocking {
                            // 1. Fetch apples and pears concurrently with async, await both, print the total
                            val orders = flowOf(4, 0, 9, 25, 6)
                            // 2. Keep orders from 1 up to the total, double each, collect with toList(), print
                            // 3. Print the revenue
                        }
                    }
                """,
                language = "kotlin-coroutines",
                hints = listOf(
                    "Start both before awaiting either: val apples = async { fetchStock(\"apples\") }, and the same for pears.",
                    "orders.filter { it in 1..total }.map { it * 2 }.toList() gives the costs as a List.",
                    "A List prints like [8, 18, 12], and costs.sum() adds it up.",
                ),
                solution = """
                    import kotlinx.coroutines.*
                    import kotlinx.coroutines.flow.*

                    suspend fun fetchStock(item: String): Int {
                        delay(100)
                        return when (item) {
                            "apples" -> 12
                            "pears" -> 7
                            else -> 0
                        }
                    }

                    fun main() {
                        runBlocking {
                            val apples = async { fetchStock("apples") }
                            val pears = async { fetchStock("pears") }
                            val total = apples.await() + pears.await()
                            println("Total stock: ${'$'}total")
                            val orders = flowOf(4, 0, 9, 25, 6)
                            val costs = orders
                                .filter { it in 1..total }
                                .map { it * 2 }
                                .toList()
                            println("Order costs: ${'$'}costs")
                            println("Revenue: ${'$'}{costs.sum()}")
                        }
                    }
                """,
                check = ExerciseCheck(
                    expectedOutput = "Total stock: 19\nOrder costs: [8, 18, 12]\nRevenue: 38",
                    requireSource = listOf("async", "await()", ".filter", ".map", "toList()"),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Compose: Introduction
// ===========================================================================

private fun composeIntroMilestones() = listOf(
    msCheckpoint(
        "ci", "Compose: Introduction review",
        listOf(
            msQuiz(
                "ci", 1,
                prompt = "What does adding @Preview to a composable let you do?",
                options = listOf(
                    "Render it on its own, without launching the app",
                    "Run it on a background thread",
                    "Make it the app's first screen",
                    "Hide it in release builds",
                ),
                correct = 0,
                explanation = "A @Preview composable is rendered by itself, which is how the lesson previews appear.",
            ),
            msQuiz(
                "ci", 2,
                prompt = "You want a badge drawn on top of a picture, in the same spot. Which layout overlaps its children?",
                options = listOf("Column", "Row", "Box", "Text"),
                correct = 2,
                explanation = "Box stacks children on top of each other; Column and Row place them one after another.",
            ),
            msQuiz(
                "ci", 3,
                prompt = "Which chain paints the colour behind the padding too, so the text sits inside a larger coloured block?",
                options = listOf(
                    "Modifier.padding(16.dp).background(color)",
                    "Modifier.background(color).padding(16.dp)",
                    "Both give the same result",
                    "Neither: padding is always transparent",
                ),
                correct = 1,
                explanation = "Modifiers apply in order: the background covers the whole area first, then the padding pushes the content in.",
            ),
            msQuiz(
                "ci", 4,
                prompt = "A counter uses `var count by mutableStateOf(0)` without `remember`. What happens when it recomposes?",
                options = listOf(
                    "The count keeps its value",
                    "The app crashes",
                    "The count doubles",
                    "The count resets to 0, because the state is created again",
                ),
                correct = 3,
                explanation = "remember keeps the state across recompositions; without it a fresh state with the initial value is made each time.",
            ),
            msQuiz(
                "ci", 5,
                prompt = "An OutlinedTextField has `value = name` but its onValueChange is `{ }`, which never updates name. What does the user see when typing?",
                options = listOf(
                    "The field keeps showing name, so typing seems to do nothing",
                    "The typed text appears normally",
                    "The field clears itself",
                    "It doesn't compile",
                ),
                correct = 0,
                explanation = "The field shows the value you pass in; you have to update your state in onValueChange for the text to change.",
            ),
        ),
    ),
)

// ===========================================================================
// Compose: Advanced
// ===========================================================================

private fun composeAdvancedMilestones() = listOf(
    msCheckpoint(
        "cad", "Compose: Advanced review",
        listOf(
            msQuiz(
                "cad", 1,
                prompt = "Which counter composable has its state hoisted?",
                options = listOf(
                    "fun Counter() that holds remember { mutableStateOf(0) } inside",
                    "fun CounterDisplay(count: Int, onIncrement: () -> Unit)",
                    "fun Counter() that reads a global var",
                    "fun Counter(): Int that returns the count",
                ),
                correct = 1,
                explanation = "A hoisted composable takes its value as a parameter and reports changes through a callback.",
            ),
            msQuiz(
                "cad", 2,
                prompt = "How do you describe the rows of a LazyColumn?",
                options = listOf(
                    "Pass the list to its modifier",
                    "Call Text directly inside it in a for loop",
                    "Inside an items(rows) { row -> ... } block",
                    "LazyColumn builds rows from a String on its own",
                ),
                correct = 2,
                explanation = "LazyColumn's content is a list scope: you describe rows with items(...) (or item { }).",
            ),
            msQuiz(
                "cad", 3,
                prompt = "Why write `Text(\"Title\", style = MaterialTheme.typography.titleMedium)` instead of setting a font size by hand?",
                options = listOf(
                    "It is the only way to show text",
                    "The text style comes from the theme, so the UI stays consistent and changes with it",
                    "It makes the text load faster",
                    "Typography only works inside a Card",
                ),
                correct = 1,
                explanation = "MaterialTheme supplies colorScheme and typography, so every component reads the same styles.",
            ),
            msQuiz(
                "cad", 4,
                prompt = "When does the block of `LaunchedEffect(userId) { ... }` run again?",
                options = listOf(
                    "On every recomposition",
                    "Never, it only runs once for the app's lifetime",
                    "Whenever the user taps the screen",
                    "When userId changes",
                ),
                correct = 3,
                explanation = "A LaunchedEffect runs when it enters composition and restarts only when its key changes.",
            ),
            msQuiz(
                "cad", 5,
                prompt = "`val color by animateColorAsState(if (on) purple else grey)`. What happens when `on` becomes true?",
                options = listOf(
                    "The colour moves smoothly from grey to purple",
                    "The colour jumps to purple instantly",
                    "Nothing until you call start()",
                    "The composable is removed",
                ),
                correct = 0,
                explanation = "animate*AsState animates from the current value toward the new target whenever the target changes.",
            ),
        ),
    ),
)

// ===========================================================================
// Java: Beyond Basics
// ===========================================================================

private fun javaBeyondMilestones() = listOf(
    msCheckpoint(
        "jb", "Java: Beyond Basics review",
        listOf(
            msQuiz(
                "jb", 1,
                prompt = "What is the difference between a class and an object?",
                options = listOf(
                    "They are two names for the same thing",
                    "An object is a blueprint; a class is made from it with new",
                    "A class is a blueprint; an object is an instance of it made with new",
                    "A class can only hold methods, an object only data",
                ),
                correct = 2,
                explanation = "The class describes the type; each `new` creates an object of it.",
            ),
            msQuiz(
                "jb", 2,
                prompt = "Which line creates a Dog object?",
                options = listOf("Dog d = Dog();", "Dog d = new Dog();", "Dog d = Dog.create();", "new d = Dog();"),
                correct = 1,
                explanation = "Objects are created with `new` followed by a constructor call.",
            ),
            msQuiz(
                "jb", 3,
                prompt = "What does this print?\n\nArrayList<String> list = new ArrayList<>();\nlist.add(\"x\");\nlist.add(\"y\");\nlist.add(\"z\");\nSystem.out.println(list.size());",
                options = listOf("2", "z", "0", "3"),
                correct = 3,
                explanation = "An ArrayList grows with every add, and size() counts its elements.",
            ),
            msQuiz(
                "jb", 4,
                prompt = "What does this print?\n\ntry {\n    System.out.println(\"A\");\n    int zero = 0;\n    System.out.println(10 / zero);\n    System.out.println(\"B\");\n} catch (ArithmeticException e) {\n    System.out.println(\"C\");\n}",
                options = listOf("A, then C", "A, B, then C", "A, then B", "Only C"),
                correct = 0,
                explanation = "A prints, the division throws, the rest of the try is skipped, and the catch prints C.",
            ),
            msQuiz(
                "jb", 5,
                prompt = "What does the <String> in ArrayList<String> mean?",
                options = listOf(
                    "The list can only be printed as a String",
                    "The list holds Strings",
                    "The list has a String name",
                    "The list is sorted alphabetically",
                ),
                correct = 1,
                explanation = "The type in angle brackets says what kind of element the list holds.",
            ),
        ),
    ),
    LearnLessonDef(
        id = "jb-capstone", title = "Expense log", kind = "capstone",
        summary = "Parse expenses into objects, and report the lines that don't parse.",
        iconId = "java", estMinutes = 15,
        steps = listOf(
            LearnStepDef.Concept(
                "ms-jb-cap-c", "Parsing safely",
                listOf(
                    text("This project combines a class, an `ArrayList` and `try / catch`. Three more tools help. A **constructor** fills in a new object's fields; `Integer.parseInt` turns text into an `int` and throws a `NumberFormatException` when it can't; a **for-each** loop visits every element of a list:"),
                    code(
                        """
                        class Expense {
                            String item;
                            int amount;

                            Expense(String item, int amount) {  // the constructor
                                this.item = item;
                                this.amount = amount;
                            }
                        }

                        try {
                            int n = Integer.parseInt("12x");
                        } catch (NumberFormatException e) {
                            System.out.println("not a number");
                        }

                        for (Expense exp : expenses) {
                            total += exp.amount;
                        }
                        """,
                        "java",
                    ),
                    text("`Scanner.next()` reads the next word, skipping spaces and line breaks."),
                    tip(CASES_TIP),
                ),
            ),
            LearnStepDef.Interactive(
                "ms-jb-cap-i", "Expense log",
                listOf(
                    text("The input is a count, then that many expenses. Each expense is an **item** word and an **amount** word. The amount should be a whole number, but some aren't (`abc`, `2.50`)."),
                    text("Print three kinds of line, in this order:\n\n• `Total: <sum of the valid amounts>`\n• `Largest: <item> <amount>` for the biggest valid expense (the first one if several tie), or `Largest: none` when there are no valid expenses\n• `Invalid: <item> <amount text>` for every expense whose amount isn't a whole number, in input order"),
                    text("For this input:"),
                    code(
                        """
                        4
                        coffee 4
                        lunch 12
                        taxi abc
                        book 9
                        """,
                        "plain",
                    ),
                    text("the output is exactly:"),
                    code(
                        """
                        Total: 25
                        Largest: lunch 12
                        Invalid: taxi abc
                        """,
                        "plain",
                    ),
                ),
                starterCode = """
                    import java.util.ArrayList;
                    import java.util.Scanner;

                    class Expense {
                        String item;
                        int amount;

                        Expense(String item, int amount) {
                            this.item = item;
                            this.amount = amount;
                        }
                    }

                    public class Main {
                        public static void main(String[] args) {
                            Scanner in = new Scanner(System.in);
                            int count = in.nextInt();
                            ArrayList<Expense> expenses = new ArrayList<>();
                            ArrayList<String> invalid = new ArrayList<>();
                            for (int i = 0; i < count; i++) {
                                String item = in.next();
                                String text = in.next();
                                // TODO: parse text with Integer.parseInt inside a try.
                                // Valid: add a new Expense. Invalid: remember item + " " + text.
                            }
                            // TODO: print the Total, Largest and Invalid lines
                        }
                    }
                """,
                language = "java",
                hints = listOf(
                    "In the try: expenses.add(new Expense(item, Integer.parseInt(text))); in catch (NumberFormatException e): invalid.add(item + \" \" + text);",
                    "Keep an Expense largest = null and replace it when largest == null || exp.amount > largest.amount. Using > keeps the first of a tie.",
                    "Print Largest: none when largest is still null after the loop.",
                ),
                solution = """
                    import java.util.ArrayList;
                    import java.util.Scanner;

                    class Expense {
                        String item;
                        int amount;

                        Expense(String item, int amount) {
                            this.item = item;
                            this.amount = amount;
                        }
                    }

                    public class Main {
                        public static void main(String[] args) {
                            Scanner in = new Scanner(System.in);
                            int count = in.nextInt();
                            ArrayList<Expense> expenses = new ArrayList<>();
                            ArrayList<String> invalid = new ArrayList<>();
                            for (int i = 0; i < count; i++) {
                                String item = in.next();
                                String text = in.next();
                                try {
                                    int amount = Integer.parseInt(text);
                                    expenses.add(new Expense(item, amount));
                                } catch (NumberFormatException e) {
                                    invalid.add(item + " " + text);
                                }
                            }
                            int total = 0;
                            Expense largest = null;
                            for (Expense exp : expenses) {
                                total += exp.amount;
                                if (largest == null || exp.amount > largest.amount) {
                                    largest = exp;
                                }
                            }
                            System.out.println("Total: " + total);
                            if (largest == null) {
                                System.out.println("Largest: none");
                            } else {
                                System.out.println("Largest: " + largest.item + " " + largest.amount);
                            }
                            for (String bad : invalid) {
                                System.out.println("Invalid: " + bad);
                            }
                        }
                    }
                """,
                check = ExerciseCheck(
                    requireSource = listOf("catch (", "new Expense("),
                    cases = listOf(
                        ExerciseCase(
                            "4\ncoffee 4\nlunch 12\ntaxi abc\nbook 9\n",
                            "Total: 25\nLargest: lunch 12\nInvalid: taxi abc",
                        ),
                        ExerciseCase(
                            "3\nrent 500\ngym 500\ntea 2\n",
                            "Total: 1002\nLargest: rent 500",
                        ),
                        ExerciseCase(
                            "2\nbus 2.50\nsnack ten\n",
                            "Total: 0\nLargest: none\nInvalid: bus 2.50\nInvalid: snack ten",
                        ),
                        ExerciseCase(
                            "5\na 1\nb x1\nc 30\nd 7\ne 3o\n",
                            "Total: 38\nLargest: c 30\nInvalid: b x1\nInvalid: e 3o",
                        ),
                    ),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Java: More
// ===========================================================================

private fun javaMoreMilestones() = listOf(
    msCheckpoint(
        "jm", "Java: More review",
        listOf(
            msQuiz(
                "jm", 1,
                prompt = "Which keyword does a class use to promise it provides an interface's methods?",
                options = listOf("extends", "override", "implements", "interface"),
                correct = 2,
                explanation = "`class English implements Greeter` says English provides every method Greeter declares.",
            ),
            msQuiz(
                "jm", 2,
                prompt = "What does this print?\n\nHashMap<String, Integer> map = new HashMap<>();\nmap.put(\"a\", 1);\nmap.put(\"a\", 5);\nSystem.out.println(map.get(\"a\"));",
                options = listOf("1", "5", "6", "[1, 5]"),
                correct = 1,
                explanation = "A key holds one value, so the second put replaces the first.",
            ),
            msQuiz(
                "jm", 3,
                prompt = "In `static <T> T firstOf(T[] items)`, what does the `<T>` do?",
                options = listOf(
                    "Declares a type parameter, so the method works for any element type",
                    "Limits the method to Strings",
                    "Makes the method return a type name",
                    "Marks the method as private",
                ),
                correct = 0,
                explanation = "<T> introduces a type parameter that each call fills in, such as String or Integer.",
            ),
            msQuiz(
                "jm", 4,
                prompt = "With that generic firstOf, what does this print?\n\nInteger[] nums = {7, 8, 9};\nSystem.out.println(firstOf(nums));",
                options = listOf("8", "9", "It doesn't compile: firstOf only takes Strings", "7"),
                correct = 3,
                explanation = "T becomes Integer for this call, and firstOf returns the element at index 0.",
            ),
            msQuiz(
                "jm", 5,
                prompt = "English implements Greeter, and its greet() returns \"Hi\". What happens here?\n\nGreeter g = new English();\nSystem.out.println(g.greet());",
                options = listOf(
                    "It doesn't compile: g must be declared as English",
                    "It prints Hi",
                    "It prints Greeter",
                    "It throws an exception",
                ),
                correct = 1,
                explanation = "A variable of an interface type can hold any object that implements it, and calls run that object's method.",
            ),
        ),
    ),
    LearnLessonDef(
        id = "jm-capstone", title = "Word frequency", kind = "capstone",
        summary = "Count words with a generic HashMap counter, then sort the report.",
        iconId = "java", estMinutes = 15,
        steps = listOf(
            LearnStepDef.Concept(
                "ms-jm-cap-c", "Counting and sorting",
                listOf(
                    text("To count things, keep a `HashMap` from each thing to how many times you've seen it. `get` returns `null` for a key that isn't there yet:"),
                    code(
                        """
                        HashMap<String, Integer> counts = new HashMap<>();
                        Integer old = counts.get("cat");  // null the first time
                        if (old == null) {
                            counts.put("cat", 1);
                        } else {
                            counts.put("cat", old + 1);
                        }
                        """,
                        "java",
                    ),
                    text("To sort a list your own way, pass `Collections.sort` a `Comparator`. Its `compare(a, b)` returns a negative number when `a` should come first, a positive number when `b` should, and 0 when they are equal:"),
                    code(
                        """
                        ArrayList<String> names = new ArrayList<>(counts.keySet());
                        Collections.sort(names, new Comparator<String>() {
                            public int compare(String a, String b) {
                                return a.compareTo(b);  // alphabetical
                            }
                        });
                        """,
                        "java",
                    ),
                    text("`counts.keySet()` holds every key once, in no particular order, which is why you sort it. `while (in.hasNext())` reads words until the input ends."),
                    tip(CASES_TIP),
                ),
            ),
            LearnStepDef.Interactive(
                "ms-jm-cap-i", "Word frequency",
                listOf(
                    text("The input is any number of words (letters only), separated by spaces and line breaks, until the input ends. Count them **ignoring case**, so `The` and `the` are the same word."),
                    text("Finish `MapCounter`, a generic class that implements the `Counter<T>` interface with a `HashMap`. Then print `Words: <how many words>`, `Unique: <how many different words>`, and one `word: count` line per different word, in lower case, **most frequent first**, with ties in alphabetical order."),
                    text("For this input:"),
                    code(
                        """
                        the cat saw the dog
                        The dog ran
                        """,
                        "plain",
                    ),
                    text("the output is exactly:"),
                    code(
                        """
                        Words: 8
                        Unique: 5
                        the: 3
                        dog: 2
                        cat: 1
                        ran: 1
                        saw: 1
                        """,
                        "plain",
                    ),
                ),
                starterCode = """
                    import java.util.ArrayList;
                    import java.util.Collections;
                    import java.util.Comparator;
                    import java.util.HashMap;
                    import java.util.Scanner;

                    interface Counter<T> {
                        void add(T item);
                        int countOf(T item);
                        ArrayList<T> items();
                    }

                    class MapCounter<T> implements Counter<T> {
                        private HashMap<T, Integer> counts = new HashMap<>();

                        public void add(T item) {
                            // TODO: count one more of item
                        }

                        public int countOf(T item) {
                            return 0; // TODO: how many times item was added
                        }

                        public ArrayList<T> items() {
                            return new ArrayList<>(); // TODO: every different item, once
                        }
                    }

                    public class Main {
                        public static void main(String[] args) {
                            Scanner in = new Scanner(System.in);
                            final Counter<String> counter = new MapCounter<>();
                            int total = 0;
                            while (in.hasNext()) {
                                counter.add(in.next().toLowerCase());
                                total++;
                            }
                            // TODO: sort counter.items() and print the report
                        }
                    }
                """,
                language = "java",
                hints = listOf(
                    "add: read the old count with counts.get(item); put 1 when it is null, otherwise old + 1. items: return new ArrayList<>(counts.keySet()).",
                    "In compare(a, b), first compute counter.countOf(b) - counter.countOf(a) (bigger counts first). If that is 0, return a.compareTo(b).",
                    "Print Words and Unique before the word lines, even when there are no words at all.",
                ),
                solution = """
                    import java.util.ArrayList;
                    import java.util.Collections;
                    import java.util.Comparator;
                    import java.util.HashMap;
                    import java.util.Scanner;

                    interface Counter<T> {
                        void add(T item);
                        int countOf(T item);
                        ArrayList<T> items();
                    }

                    class MapCounter<T> implements Counter<T> {
                        private HashMap<T, Integer> counts = new HashMap<>();

                        public void add(T item) {
                            Integer old = counts.get(item);
                            if (old == null) {
                                counts.put(item, 1);
                            } else {
                                counts.put(item, old + 1);
                            }
                        }

                        public int countOf(T item) {
                            Integer c = counts.get(item);
                            if (c == null) {
                                return 0;
                            }
                            return c;
                        }

                        public ArrayList<T> items() {
                            return new ArrayList<T>(counts.keySet());
                        }
                    }

                    public class Main {
                        public static void main(String[] args) {
                            Scanner in = new Scanner(System.in);
                            final Counter<String> counter = new MapCounter<String>();
                            int total = 0;
                            while (in.hasNext()) {
                                counter.add(in.next().toLowerCase());
                                total++;
                            }
                            ArrayList<String> words = counter.items();
                            Collections.sort(words, new Comparator<String>() {
                                public int compare(String a, String b) {
                                    int byCount = counter.countOf(b) - counter.countOf(a);
                                    if (byCount != 0) {
                                        return byCount;
                                    }
                                    return a.compareTo(b);
                                }
                            });
                            System.out.println("Words: " + total);
                            System.out.println("Unique: " + words.size());
                            for (String w : words) {
                                System.out.println(w + ": " + counter.countOf(w));
                            }
                        }
                    }
                """,
                check = ExerciseCheck(
                    cases = listOf(
                        ExerciseCase(
                            "the cat saw the dog\nThe dog ran\n",
                            "Words: 8\nUnique: 5\nthe: 3\ndog: 2\ncat: 1\nran: 1\nsaw: 1",
                        ),
                        ExerciseCase(
                            "b a c b a b\n",
                            "Words: 6\nUnique: 3\nb: 3\na: 2\nc: 1",
                        ),
                        ExerciseCase(
                            "Apple banana APPLE\nBanana cherry\n",
                            "Words: 5\nUnique: 3\napple: 2\nbanana: 2\ncherry: 1",
                        ),
                        ExerciseCase(
                            "\n",
                            "Words: 0\nUnique: 0",
                        ),
                    ),
                ),
            ),
        ),
    ),
)

// ===========================================================================
// Android Basics
// ===========================================================================

private fun androidBasicsMilestones() = listOf(
    msCheckpoint(
        "ab", "Android Basics review",
        listOf(
            msQuiz(
                "ab", 1,
                prompt = "What is an Activity?",
                options = listOf(
                    "A background thread",
                    "A single screen of the app; it sets its layout in onCreate with setContentView",
                    "The file that lists the app's permissions",
                    "A widget that shows text",
                ),
                correct = 1,
                explanation = "Each Activity is one screen, and onCreate is where it calls setContentView.",
            ),
            msQuiz(
                "ab", 2,
                prompt = "In `setContentView(R.layout.activity_main)`, what is `R`?",
                options = listOf(
                    "A class you write by hand",
                    "The Activity's parent class",
                    "A class Android generates from your resources",
                    "A permission",
                ),
                correct = 2,
                explanation = "The build generates R from the res folder, so R.layout.activity_main points at res/layout/activity_main.xml.",
            ),
            msQuiz(
                "ab", 3,
                prompt = "What does android:layout_width=\"wrap_content\" do?",
                options = listOf(
                    "Makes the view just wide enough for its content",
                    "Makes the view fill its parent's width",
                    "Hides the view",
                    "Wraps the text onto several lines",
                ),
                correct = 0,
                explanation = "wrap_content sizes the view to its content; match_parent fills the parent.",
            ),
            msQuiz(
                "ab", 4,
                prompt = "The user switches to another app in the middle of your game. Which callback is the right place to pause it?",
                options = listOf("onCreate()", "onDestroy()", "onStart()", "onPause()"),
                correct = 3,
                explanation = "onPause runs when the screen loses focus; resume the game in its partner, onResume.",
            ),
            msQuiz(
                "ab", 5,
                prompt = "In AndroidManifest.xml, what marks the Activity that opens when the user taps the app icon?",
                options = listOf(
                    "Being listed first in the manifest",
                    "An <intent-filter> with the MAIN action and the LAUNCHER category",
                    "A uses-permission tag",
                    "The android:label attribute",
                ),
                correct = 1,
                explanation = "The MAIN + LAUNCHER intent-filter tells the system which Activity the launcher starts.",
            ),
        ),
    ),
)

// ===========================================================================
// Getting Started
// ===========================================================================

private fun gettingStartedMilestones() = listOf(
    msCheckpoint(
        "gs", "Getting Started review",
        listOf(
            msQuiz(
                "gs", 1,
                prompt = "What is a module?",
                options = listOf(
                    "A single source file",
                    "An independently buildable unit of code inside a project",
                    "Another name for the whole app",
                    "A downloaded library",
                ),
                correct = 1,
                explanation = "A project holds one or more modules, and each one builds on its own.",
            ),
            msQuiz(
                "gs", 2,
                prompt = "In a module's main source set, where does Kotlin code usually go?",
                options = listOf("src/main/res/", "src/main/java/", "build/", "src/main/kotlin/"),
                correct = 3,
                explanation = "Kotlin sources sit in src/main/kotlin/, Java in src/main/java/ and Android resources in src/main/res/.",
            ),
            msQuiz(
                "gs", 3,
                prompt = "Builds are incremental. What does that mean?",
                options = listOf(
                    "After the first build, only what you changed is recompiled",
                    "Every build starts again from nothing",
                    "The app's version number goes up on each build",
                    "Only one file can be built at a time",
                ),
                correct = 0,
                explanation = "Reusing the unchanged work is what makes later runs fast.",
            ),
            msQuiz(
                "gs", 4,
                prompt = "Which steps turn an Android project into an installable APK?",
                options = listOf(
                    "Download, unzip, install",
                    "Package, compile, sign, dex",
                    "Compile, dex, package, sign",
                    "Only compile",
                ),
                correct = 2,
                explanation = "Sources are compiled, converted to dex, packaged with resources, and the APK is signed.",
            ),
            msQuiz(
                "gs", 5,
                prompt = "A build fails. Where do you see each build step, its logs and the errors?",
                options = listOf("The build console", "The Store tab", "The file tree", "Nowhere, builds are silent"),
                correct = 0,
                explanation = "The build console streams every step and shows any errors as they happen.",
            ),
        ),
    ),
)
