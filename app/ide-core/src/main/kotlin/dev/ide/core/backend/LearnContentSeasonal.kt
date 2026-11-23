package dev.ide.core.backend

// ===========================================================================
// Seasonal tracks: shown only inside their [SeasonWindow] (LearnBackend filters them by the local date).
// ===========================================================================

internal val ACCENT_HOLIDAY = 0xFFB3243BL

/** Nov 28 through Jan 6, across the new year. */
internal val CHRISTMAS_SEASON = SeasonWindow(startMonth = 11, startDay = 28, endMonth = 1, endDay = 6)

/**
 * "Build a Christmas card in Compose": one cozy card, grown a concept at a time (composables and modifiers,
 * layouts, state, a plain-Kotlin exercise, Canvas, animation). The Compose steps follow the Compose tracks'
 * pattern: editable live previews plus quizzes. The one graded exercise is plain Kotlin, because a lesson with
 * an exercise moves into a console workspace where Compose cannot compile.
 */
internal fun christmasCard() = LearnTrackDef(
    id = "christmas-card",
    title = "Build a Christmas card in Compose",
    subtitle = "A cozy card with ornaments and falling snow, rendered live",
    iconId = "star",
    accentColor = ACCENT_HOLIDAY,
    language = "kotlin-compose",
    category = "Holiday",
    level = "Beginner",
    visibleIn = CHRISTMAS_SEASON,
    lessons = listOf(
        LearnLessonDef(
            id = "xmas-card", title = "A card is a composable",
            summary = "Put a cozy Card on screen with Text and Modifier.",
            iconId = "layers", estMinutes = 5,
            steps = listOf(
                LearnStepDef.Concept(
                    "xmas-card-c", "Your first card",
                    listOf(
                        text("Let's make a Christmas card for someone you love. In Compose, every piece of UI is a **composable**: a Kotlin function marked `@Composable`."),
                        text("A `Card` gives us a rounded surface. Inside it, `Text` draws the words, and a **Modifier** adds breathing room with `padding`. Here is the start of our card, rendered live:"),
                        composePreview(XMAS_CARD, caption = "An evergreen Card with two lines of Text"),
                        tip("`Color(0xFF1B4332)` is a color written in hex: `FF` for fully opaque, then red, green and blue. This one is a deep evergreen."),
                    ),
                ),
                LearnStepDef.Concept(
                    "xmas-card-play", "Make it yours",
                    listOf(
                        text("**Edit the code** and the card updates as you type. Change the message, try a bigger `fontSize`, or swap the evergreen for cranberry, `Color(0xFF9B1B30)`."),
                        composePreview(XMAS_CARD, interactive = true),
                    ),
                ),
                LearnStepDef.Quiz(
                    "xmas-card-q", "Quick check",
                    prompt = "What does `Modifier.padding(24.dp)` on the Column do?",
                    options = listOf(
                        "Makes the text bold",
                        "Adds space between the edge of the card and its words",
                        "Changes the color of the card",
                        "Makes the card clickable",
                    ),
                    correctIndex = 1,
                    explanation = "Padding adds space around the content, so the words don't touch the edges of the card.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "xmas-ornaments", title = "Ornaments in a row",
            summary = "Arrange things with Column, Row and Box.",
            iconId = "layers", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "xmas-ornaments-c", "Column, Row and Box",
                    listOf(
                        text("Layouts arrange what's inside them. A **Column** stacks its children from top to bottom, a **Row** puts them side by side, and a **Box** layers them on top of each other."),
                        text("An ornament is just a small `Box`: give it a `size`, `clip` it to a `CircleShape`, then paint a `background`. Put a few in a `Row` and you have a string of baubles:"),
                        composePreview(XMAS_ORNAMENTS, caption = "A Row of ornaments under the greeting"),
                        tip("Order matters: `clip` comes before `background`, so the color is painted inside the circle."),
                        note("`Arrangement.spacedBy(10.dp)` puts the same gap between every child in the Row."),
                    ),
                ),
                LearnStepDef.Concept(
                    "xmas-ornaments-play", "Hang more ornaments",
                    listOf(
                        text("**Your turn.** Add another `Ornament(...)` line inside the `Row`, try a new color, or make them bigger by changing `size(28.dp)`."),
                        composePreview(XMAS_ORNAMENTS, interactive = true),
                    ),
                ),
                LearnStepDef.Quiz(
                    "xmas-ornaments-q", "Quick check",
                    prompt = "Which layout puts the ornaments side by side?",
                    options = listOf("Column", "Row", "Box", "Card"),
                    correctIndex = 1,
                    explanation = "A Row lays its children out horizontally. A Column would stack them into a tower instead.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "xmas-state", title = "Make it personal",
            summary = "Use remember and mutableStateOf to change the card.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "xmas-state-c", "Cards that remember",
                    listOf(
                        text("A card that never changes is a little quiet. **State** is a value that can change, and when it does, Compose redraws the parts that read it."),
                        text("`remember { mutableStateOf(0) }` keeps a value safe while the card redraws, and `by` lets you use it like a normal variable. Tap **Next name** to change who the card is for:"),
                        composePreview(XMAS_NAMES, interactive = true, caption = "Tap Next name: the index is state"),
                        note("Without `remember`, the index would go back to `0` every time the card redraws, so the name would never change."),
                    ),
                ),
                LearnStepDef.Concept(
                    "xmas-state-flip", "Open the card",
                    listOf(
                        text("State can hold a `Boolean` too. Here `open` decides which side of the card you see, and even its color. Tap the button to open it, then write your own message inside."),
                        composePreview(XMAS_FLIP, interactive = true),
                    ),
                ),
                LearnStepDef.Quiz(
                    "xmas-state-q", "Quick check",
                    prompt = "Why does the card keep `open` in `remember { mutableStateOf(false) }`?",
                    options = listOf(
                        "So the value survives redraws, and changing it updates the card",
                        "So the card loads faster",
                        "Because a Boolean can't be a normal variable",
                        "So the text gets translated",
                    ),
                    correctIndex = 0,
                    explanation = "mutableStateOf tells Compose to redraw when the value changes, and remember keeps the value between redraws.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "xmas-greeting", title = "Words for anyone",
            summary = "Write the plain Kotlin function behind the card.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "xmas-greeting-c", "Logic lives in plain functions",
                    listOf(
                        text("Composables are ordinary Kotlin, so the words on a card can come from an ordinary function. Keeping that logic outside the composable makes it easy to test, and the card simply calls it:"),
                        code(
                            """
                            // Plain Kotlin: no @Composable, no UI, just words.
                            fun cardGreeting(name: String): String = "Merry Christmas, ${'$'}name!"

                            @Composable
                            fun CardHeading(name: String) {
                                Text(cardGreeting(name))
                            }
                            """
                        ),
                        tip("A function that only turns its input into a result, with no UI, is the easiest kind of code to check. That's exactly what you'll write next."),
                    ),
                ),
                LearnStepDef.Interactive(
                    "xmas-greeting-i", "Write cardGreeting",
                    listOf(
                        text("The program reads a name on one line and prints the card's greeting. Make `cardGreeting` return `Merry Christmas, Ana!` for the name `Ana`. Ignore spaces around the name, and when the name is blank return `Merry Christmas, friend!` instead."),
                        text("For the input `Ana` the output is:"),
                        code("Merry Christmas, Ana!", "plain"),
                    ),
                    starterCode = """
                        // The card calls this to fill in its heading.
                        fun cardGreeting(name: String): String {
                            // Return "Merry Christmas, <name>!"
                            // or "Merry Christmas, friend!" when the name is blank
                            return ""
                        }

                        fun main() {
                            val name = readln()
                            println(cardGreeting(name))
                        }
                    """,
                    language = "kotlin",
                    hints = listOf(
                        "Trim the name first: `val who = name.trim()`",
                        "Check for a blank name with `who.isEmpty()`",
                        "Build the text with a template: `\"Merry Christmas, \$who!\"`",
                    ),
                    solution = """
                        // The card calls this to fill in its heading.
                        fun cardGreeting(name: String): String {
                            val who = name.trim()
                            return if (who.isEmpty()) "Merry Christmas, friend!" else "Merry Christmas, ${'$'}who!"
                        }

                        fun main() {
                            val name = readln()
                            println(cardGreeting(name))
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("Ana\n", "Merry Christmas, Ana!"),
                            ExerciseCase("  Grandma  \n", "Merry Christmas, Grandma!"),
                            ExerciseCase("\n", "Merry Christmas, friend!"),
                        ),
                        // The greeting must come from the function, not from code inlined into main.
                        requireSource = listOf("fun cardGreeting(name: String)", "println(cardGreeting("),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "xmas-canvas", title = "Draw a snowy night",
            summary = "Paint shapes yourself with Canvas.",
            iconId = "layers", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "xmas-canvas-c", "Your own drawing",
                    listOf(
                        text("Sometimes you want to paint the pixels yourself. **Canvas** gives you a drawing area, and inside its block you call drawing functions like `drawRect`, `drawCircle` and `drawLine`."),
                        text("`size` is the width and height of that area and `center` is its middle, so a drawing fits whatever space it gets. Here a loop scatters snow and six turned lines make a snowflake:"),
                        composePreview(XMAS_CANVAS, caption = "A night sky drawn with Canvas"),
                        tip("`rotate(degrees = ..., pivot = center)` turns everything drawn inside it around the middle. Six lines, 60 degrees apart, make a snowflake."),
                    ),
                ),
                LearnStepDef.Concept(
                    "xmas-canvas-play", "Make it snow harder",
                    listOf(
                        text("**Change the numbers.** Try `0 until 60` for a heavier snowfall, a bigger `radius`, or eight arms with `0 until 8` and `arm * 45f`."),
                        composePreview(XMAS_CANVAS, interactive = true),
                    ),
                ),
                LearnStepDef.Quiz(
                    "xmas-canvas-q", "Quick check",
                    prompt = "Inside `Canvas { }`, what is `size`?",
                    options = listOf(
                        "How many shapes have been drawn",
                        "The width and height of the drawing area",
                        "The size of the font",
                        "The size of the screen in inches",
                    ),
                    correctIndex = 1,
                    explanation = "size describes the Canvas's own drawing area, so you can place shapes relative to it.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "xmas-snow", title = "Let it snow",
            summary = "Animate falling snow and finish your card.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "xmas-snow-c", "Animation in a few lines",
                    listOf(
                        text("To make the snow fall we need a number that keeps changing by itself. `rememberInfiniteTransition` runs forever, and `animateFloat` gives us a value that glides from `0f` to `1f` every six seconds."),
                        text("Each flake adds that value to its starting height, and `% 1f` wraps it back to the top when it reaches the bottom, so the snow never runs out. Here is your finished card:"),
                        composePreview(XMAS_SNOW, interactive = true, caption = "Your finished card, with snow falling"),
                        note("The `Canvas` uses `Modifier.matchParentSize()`, so the snow fills the whole `Box` behind the words without making the card any bigger."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "xmas-snow-q", "Quick check",
                    prompt = "What does `infiniteRepeatable(tween(durationMillis = 6000))` tell the animation?",
                    options = listOf(
                        "Run once, then stop",
                        "Take six seconds for each trip, and repeat forever",
                        "Wait six seconds before starting",
                        "Repeat exactly 6000 times",
                    ),
                    correctIndex = 1,
                    explanation = "tween sets how long one trip takes, and infiniteRepeatable starts it again every time it finishes.",
                ),
                LearnStepDef.Concept(
                    "xmas-snow-done", "Merry Christmas!",
                    listOf(
                        text("You built a whole card: composables and modifiers, layouts, state, a tested function, drawing and animation. That's a real slice of Compose. Send it to someone you love."),
                        tip("Want more? **Compose: Introduction** and **Compose: Advanced** go deeper into everything you used here."),
                    ),
                ),
            ),
        ),
    ),
)

// ---- the card's code, one stage per lesson ----

private const val XMAS_CARD = """
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChristmasCard() {
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B4332)),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                "Merry Christmas!",
                color = Color(0xFFFFF8E7),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Wishing you a warm and cozy season.",
                color = Color(0xFFE9C46A),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Preview
@Composable
fun ChristmasCardPreview() {
    ChristmasCard()
}
"""

private const val XMAS_ORNAMENTS = """
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Ornament(color: Color) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun ChristmasCard() {
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B4332)),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Merry Christmas!", color = Color(0xFFFFF8E7), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Wishing you a warm and cozy season.", color = Color(0xFFE9C46A))
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Ornament(Color(0xFFC1121F))
                Ornament(Color(0xFFE9C46A))
                Ornament(Color(0xFF9B1B30))
                Ornament(Color(0xFFF4F1DE))
            }
        }
    }
}

@Preview
@Composable
fun ChristmasCardPreview() {
    ChristmasCard()
}
"""

private const val XMAS_NAMES = """
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChristmasCard() {
    val names = listOf("Grandma", "Sam", "everyone")
    var index by remember { mutableStateOf(0) }
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B4332)),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Dear " + names[index] + ",", color = Color(0xFFE9C46A))
            Text("Merry Christmas!", color = Color(0xFFFFF8E7), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Button(
                onClick = { index = (index + 1) % names.size },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9B1B30)),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Next name", color = Color(0xFFFFF8E7))
            }
        }
    }
}

@Preview
@Composable
fun ChristmasCardPreview() {
    ChristmasCard()
}
"""

private const val XMAS_FLIP = """
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChristmasCard() {
    var open by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (open) Color(0xFFFFF8E7) else Color(0xFF9B1B30),
        ),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            if (open) {
                Text("Dear Sam,", color = Color(0xFF1B4332), fontWeight = FontWeight.Bold)
                Text("Thanks for a wonderful year. See you at the party!", color = Color(0xFF1B4332))
            } else {
                Text("Merry Christmas!", color = Color(0xFFFFF8E7), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { open = !open }) {
                Text(
                    if (open) "Close the card" else "Open the card",
                    color = if (open) Color(0xFF9B1B30) else Color(0xFFE9C46A),
                )
            }
        }
    }
}

@Preview
@Composable
fun ChristmasCardPreview() {
    ChristmasCard()
}
"""

private const val XMAS_CANVAS = """
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun SnowyNight() {
    Canvas(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .height(180.dp),
    ) {
        drawRect(Color(0xFF0B2545))
        for (i in 0 until 24) {
            val x = (i * 37 % 100) / 100f * size.width
            val y = (i * 61 % 100) / 100f * size.height
            drawCircle(Color.White, radius = 2f + i % 3 * 1.5f, center = Offset(x, y))
        }
        for (arm in 0 until 6) {
            rotate(degrees = arm * 60f, pivot = center) {
                drawLine(
                    color = Color.White,
                    start = center,
                    end = Offset(center.x, center.y - 50f),
                    strokeWidth = 5f,
                )
            }
        }
    }
}

@Preview
@Composable
fun SnowyNightPreview() {
    SnowyNight()
}
"""

private const val XMAS_SNOW = """
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Ornament(color: Color) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(color))
}

@Composable
fun ChristmasCard() {
    val snow = rememberInfiniteTransition(label = "snow")
    val fall by snow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 6000, easing = LinearEasing)),
        label = "fall",
    )
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B4332)),
    ) {
        Box {
            Canvas(modifier = Modifier.matchParentSize()) {
                for (i in 0 until 30) {
                    val x = (i * 37 % 100) / 100f * size.width
                    val y = ((i * 61 % 100) / 100f + fall) % 1f * size.height
                    drawCircle(Color.White.copy(alpha = 0.7f), radius = 2f + i % 3 * 1.5f, center = Offset(x, y))
                }
            }
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Merry Christmas!", color = Color(0xFFFFF8E7), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Wishing you a warm and cozy season.", color = Color(0xFFE9C46A))
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Ornament(Color(0xFFC1121F))
                    Ornament(Color(0xFFE9C46A))
                    Ornament(Color(0xFF9B1B30))
                }
            }
        }
    }
}

@Preview
@Composable
fun ChristmasCardPreview() {
    ChristmasCard()
}
"""
