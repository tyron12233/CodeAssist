package dev.ide.core.backend

// ===========================================================================
// Seasonal tracks: shown only inside their [SeasonWindow] (LearnBackend filters them by the local date).
// ===========================================================================

/** Aurora teal, from the winter season's "Aurora night" palette. */
internal val ACCENT_HOLIDAY = 0xFF6FE0C8L

/** The winter season: Nov 23 through Jan 6, across the new year (the UI's `Season.Winter`). */
internal val WINTER_SEASON = SeasonWindow(startMonth = 11, startDay = 23, endMonth = 1, endDay = 6)

/**
 * "Build a New Year card in Compose": one starry greeting card, grown a concept at a time (composables and
 * modifiers, layouts, state, a plain-Kotlin exercise, Canvas, animation). The Compose steps follow the Compose
 * tracks' pattern: editable live previews plus quizzes. The one graded exercise is plain Kotlin, because a
 * lesson with an exercise moves into a console workspace where Compose cannot compile.
 *
 * The samples use the season's "Aurora night" palette: night navy `0xFF0E1424`, aurora teal `0xFF6FE0C8`,
 * violet `0xFFB9A6FF` and star gold `0xFFF2CF7A`.
 */
internal fun newYearCard() = LearnTrackDef(
    id = "new-year-card",
    title = "Build a New Year card in Compose",
    subtitle = "A starry card with glowing lights and falling snow, rendered live",
    iconId = "star",
    accentColor = ACCENT_HOLIDAY,
    language = "kotlin-compose",
    category = "Holiday",
    level = "Beginner",
    visibleIn = WINTER_SEASON,
    lessons = listOf(
        LearnLessonDef(
            id = "ny-card", title = "A card is a composable",
            summary = "Put a cozy Card on screen with Text and Modifier.",
            iconId = "layers", estMinutes = 5,
            steps = listOf(
                LearnStepDef.Concept(
                    "ny-card-c", "Your first card",
                    listOf(
                        text("Let's make a New Year card for someone you love. In Compose, every piece of UI is a **composable**: a Kotlin function marked `@Composable`."),
                        text("A `Card` gives us a rounded surface. Inside it, `Text` draws the words, and a **Modifier** adds breathing room with `padding`. Here is the start of our card, rendered live:"),
                        composePreview(NY_CARD, caption = "A night-blue Card with two lines of Text"),
                        tip("`Color(0xFF0E1424)` is a color written in hex: `FF` for fully opaque, then red, green and blue. This one is a deep night navy."),
                    ),
                ),
                LearnStepDef.Concept(
                    "ny-card-play", "Make it yours",
                    listOf(
                        text("**Edit the code** and the card updates as you type. Change the message, try a bigger `fontSize`, or paint the second line aurora teal, `Color(0xFF6FE0C8)`."),
                        composePreview(NY_CARD, interactive = true),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ny-card-q", "Quick check",
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
            id = "ny-lights", title = "Lights in a row",
            summary = "Arrange things with Column, Row and Box.",
            iconId = "layers", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "ny-lights-c", "Column, Row and Box",
                    listOf(
                        text("Layouts arrange what's inside them. A **Column** stacks its children from top to bottom, a **Row** puts them side by side, and a **Box** layers them on top of each other."),
                        text("A glowing light is two circles layered in a `Box`: a faint halo, and a bright dot on top. Each circle is a `Box` of its own: give it a `size`, `clip` it to a `CircleShape`, then paint a `background`. Put a few lights in a `Row` and you have a string of them:"),
                        composePreview(NY_LIGHTS, caption = "A Row of glowing lights under the greeting"),
                        tip("Order matters: `clip` comes before `background`, so the color is painted inside the circle."),
                        note("`color.copy(alpha = 0.3f)` is the same color, but see-through. That is what makes the halo look like a glow."),
                    ),
                ),
                LearnStepDef.Concept(
                    "ny-lights-play", "Hang more lights",
                    listOf(
                        text("**Your turn.** Add another `Light(...)` line inside the `Row`, try a new color, or widen the glow by changing `size(28.dp)`."),
                        composePreview(NY_LIGHTS, interactive = true),
                        note("`Arrangement.spacedBy(10.dp)` puts the same gap between every child in the Row."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ny-lights-q", "Quick check",
                    prompt = "Which layout puts the lights side by side?",
                    options = listOf("Column", "Row", "Box", "Card"),
                    correctIndex = 1,
                    explanation = "A Row lays its children out horizontally. A Column would stack them into a tower instead.",
                ),
            ),
        ),
        LearnLessonDef(
            id = "ny-state", title = "Make it personal",
            summary = "Use remember and mutableStateOf to change the card.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "ny-state-c", "Cards that remember",
                    listOf(
                        text("A card that never changes is a little quiet. **State** is a value that can change, and when it does, Compose redraws the parts that read it."),
                        text("`remember { mutableStateOf(0) }` keeps a value safe while the card redraws, and `by` lets you use it like a normal variable. Tap **Next name** to change who the card is for:"),
                        composePreview(NY_NAMES, interactive = true, caption = "Tap Next name: the index is state"),
                        note("Without `remember`, the index would go back to `0` every time the card redraws, so the name would never change."),
                    ),
                ),
                LearnStepDef.Concept(
                    "ny-state-flip", "Open the card",
                    listOf(
                        text("State can hold a `Boolean` too. Here `open` decides which side of the card you see, and even its color. Tap the button to open it, then write your own message inside."),
                        composePreview(NY_FLIP, interactive = true),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ny-state-q", "Quick check",
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
            id = "ny-greeting", title = "Words for anyone",
            summary = "Write the plain Kotlin function behind the card.",
            iconId = "kotlin", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "ny-greeting-c", "Logic lives in plain functions",
                    listOf(
                        text("Composables are ordinary Kotlin, so the words on a card can come from an ordinary function. Keeping that logic outside the composable makes it easy to test, and the card simply calls it:"),
                        code(
                            """
                            // Plain Kotlin: no @Composable, no UI, just words.
                            fun cardGreeting(name: String): String = "Happy New Year, ${'$'}name!"

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
                    "ny-greeting-i", "Write cardGreeting",
                    listOf(
                        text("The program reads a name on one line and prints the card's greeting. Make `cardGreeting` return `Happy New Year, Ana!` for the name `Ana`. Ignore spaces around the name, and when the name is blank return `Happy New Year, friend!` instead."),
                        text("For the input `Ana` the output is:"),
                        code("Happy New Year, Ana!", "plain"),
                    ),
                    starterCode = """
                        // The card calls this to fill in its heading.
                        fun cardGreeting(name: String): String {
                            // Return "Happy New Year, <name>!"
                            // or "Happy New Year, friend!" when the name is blank
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
                        "Build the text with a template: `\"Happy New Year, \$who!\"`",
                    ),
                    solution = """
                        // The card calls this to fill in its heading.
                        fun cardGreeting(name: String): String {
                            val who = name.trim()
                            return if (who.isEmpty()) "Happy New Year, friend!" else "Happy New Year, ${'$'}who!"
                        }

                        fun main() {
                            val name = readln()
                            println(cardGreeting(name))
                        }
                    """,
                    check = ExerciseCheck(
                        cases = listOf(
                            ExerciseCase("Ana\n", "Happy New Year, Ana!"),
                            ExerciseCase("  Grandma  \n", "Happy New Year, Grandma!"),
                            ExerciseCase("\n", "Happy New Year, friend!"),
                        ),
                        // The greeting must come from the function, not from code inlined into main.
                        requireSource = listOf("fun cardGreeting(name: String)", "println(cardGreeting("),
                    ),
                ),
            ),
        ),
        LearnLessonDef(
            id = "ny-canvas", title = "Draw a starry night",
            summary = "Paint shapes yourself with Canvas.",
            iconId = "layers", estMinutes = 6,
            steps = listOf(
                LearnStepDef.Concept(
                    "ny-canvas-c", "Your own drawing",
                    listOf(
                        text("Sometimes you want to paint the pixels yourself. **Canvas** gives you a drawing area, and inside its block you call drawing functions like `drawRect`, `drawCircle` and `drawLine`."),
                        text("`size` is the width and height of that area and `center` is its middle, so a drawing fits whatever space it gets. Here a `drawRect` painted with a gradient `Brush` makes a soft aurora, a loop scatters the stars, and eight turned lines make one bright star in the middle:"),
                        composePreview(NY_CANVAS, caption = "A starry night sky drawn with Canvas"),
                        tip("`rotate(degrees = ..., pivot = center)` turns everything drawn inside it around the middle. Eight lines, 45 degrees apart, make a shining star."),
                    ),
                ),
                LearnStepDef.Concept(
                    "ny-canvas-play", "Fill the sky",
                    listOf(
                        text("**Change the numbers.** Try `0 until 60` for more stars, a bigger `radius`, or twelve rays with `0 until 12` and `ray * 30f`."),
                        composePreview(NY_CANVAS, interactive = true),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ny-canvas-q", "Quick check",
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
            id = "ny-snow", title = "Let it snow",
            summary = "Animate falling snow and finish your card.",
            iconId = "layers", estMinutes = 7,
            steps = listOf(
                LearnStepDef.Concept(
                    "ny-snow-c", "Animation in a few lines",
                    listOf(
                        text("To make the snow fall we need a number that keeps changing by itself. `rememberInfiniteTransition` runs forever, and `animateFloat` gives us a value that glides from `0f` to `1f` every six seconds."),
                        text("Each flake adds that value to its starting height, and `% 1f` wraps it back to the top when it reaches the bottom, so the snow never runs out. Here is your finished card:"),
                        composePreview(NY_SNOW, interactive = true, caption = "Your finished card, with snow falling"),
                        note("The `Canvas` uses `Modifier.matchParentSize()`, so the snow fills the whole `Box` behind the words without making the card any bigger."),
                    ),
                ),
                LearnStepDef.Quiz(
                    "ny-snow-q", "Quick check",
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
                    "ny-snow-done", "Happy New Year!",
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

private const val NY_CARD = """
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
fun NewYearCard() {
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1424)),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                "Happy New Year!",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Wishing you a bright year ahead.",
                color = Color(0xFFF2CF7A),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Preview
@Composable
fun NewYearCardPreview() {
    NewYearCard()
}
"""

private const val NY_LIGHTS = """
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Light(color: Color) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
fun NewYearCard() {
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1424)),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Happy New Year!", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Wishing you a bright year ahead.", color = Color(0xFFF2CF7A))
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Light(Color(0xFF6FE0C8))
                Light(Color(0xFFB9A6FF))
                Light(Color(0xFFF2CF7A))
                Light(Color(0xFF6FE0C8))
            }
        }
    }
}

@Preview
@Composable
fun NewYearCardPreview() {
    NewYearCard()
}
"""

private const val NY_NAMES = """
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
fun NewYearCard() {
    val names = listOf("Grandma", "Sam", "everyone")
    var index by remember { mutableStateOf(0) }
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1424)),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Dear " + names[index] + ",", color = Color(0xFFF2CF7A))
            Text("Happy New Year!", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Button(
                onClick = { index = (index + 1) % names.size },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB9A6FF)),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Next name", color = Color(0xFF0E1424))
            }
        }
    }
}

@Preview
@Composable
fun NewYearCardPreview() {
    NewYearCard()
}
"""

private const val NY_FLIP = """
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
fun NewYearCard() {
    var open by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (open) Color(0xFFB9A6FF) else Color(0xFF0E1424),
        ),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            if (open) {
                Text("Dear Sam,", color = Color(0xFF0E1424), fontWeight = FontWeight.Bold)
                Text("Thanks for a wonderful year. I can't wait for the next one!", color = Color(0xFF0E1424))
            } else {
                Text("Happy New Year!", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { open = !open }) {
                Text(
                    if (open) "Close the card" else "Open the card",
                    color = if (open) Color(0xFF0E1424) else Color(0xFF6FE0C8),
                )
            }
        }
    }
}

@Preview
@Composable
fun NewYearCardPreview() {
    NewYearCard()
}
"""

private const val NY_CANVAS = """
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun StarryNight() {
    Canvas(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .height(180.dp),
    ) {
        drawRect(Color(0xFF0E1424))
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color(0x666FE0C8), Color(0x44B9A6FF), Color.Transparent),
                startY = size.height * 0.1f,
                endY = size.height * 0.6f,
            ),
            topLeft = Offset(0f, size.height * 0.1f),
            size = Size(size.width, size.height * 0.5f),
        )
        for (i in 0 until 24) {
            val x = (i * 37 % 100) / 100f * size.width
            val y = (i * 61 % 100) / 100f * size.height
            drawCircle(Color.White, radius = 1.5f + i % 3, center = Offset(x, y))
        }
        for (ray in 0 until 8) {
            rotate(degrees = ray * 45f, pivot = center) {
                drawLine(
                    color = Color(0xFFF2CF7A),
                    start = center,
                    end = Offset(center.x, center.y - 40f),
                    strokeWidth = 4f,
                )
            }
        }
    }
}

@Preview
@Composable
fun StarryNightPreview() {
    StarryNight()
}
"""

private const val NY_SNOW = """
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Light(color: Color) {
    Box(
        modifier = Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun NewYearCard() {
    val snow = rememberInfiniteTransition(label = "snow")
    val fall by snow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 6000, easing = LinearEasing)),
        label = "fall",
    )
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1424)),
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
                Text("Happy New Year!", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Wishing you a bright year ahead.", color = Color(0xFFF2CF7A))
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Light(Color(0xFF6FE0C8))
                    Light(Color(0xFFB9A6FF))
                    Light(Color(0xFFF2CF7A))
                }
            }
        }
    }
}

@Preview
@Composable
fun NewYearCardPreview() {
    NewYearCard()
}
"""
