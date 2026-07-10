package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.theme.Ca

/** Multiply a colour toward black (gradient end for the project tile / colored shadows). */
fun Color.darken(factor: Float): Color = Color(red * factor, green * factor, blue * factor, alpha)

/**
 * The rounded gradient tile bearing a project's initial (base → darkened-base diagonal). [color] overrides
 * the base tint (null = the app accent) so the picker can give each project a distinct, stable color.
 */
@Composable
fun ProjectTile(
    initial: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    radius: Dp = Ca.radius.md,
    color: Color? = null,
) {
    val accent = color ?: Ca.colors.accent
    Box(
        modifier
            .size(size)
            .background(Brush.linearGradient(listOf(accent, accent.darken(0.55f))), RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial.take(1).uppercase(),
            color = Color.White,
            fontFamily = Ca.type.uiFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}
