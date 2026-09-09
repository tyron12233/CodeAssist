package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.PrimaryActionButton
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.guide_intro
import dev.ide.ui.generated.resources.guide_limits_body
import dev.ide.ui.generated.resources.guide_limits_title
import dev.ide.ui.generated.resources.guide_own_body
import dev.ide.ui.generated.resources.guide_own_title
import dev.ide.ui.generated.resources.guide_step_live_body
import dev.ide.ui.generated.resources.guide_step_live_title
import dev.ide.ui.generated.resources.guide_step_notified_body
import dev.ide.ui.generated.resources.guide_step_notified_title
import dev.ide.ui.generated.resources.guide_step_package_body
import dev.ide.ui.generated.resources.guide_step_package_title
import dev.ide.ui.generated.resources.guide_step_review_body
import dev.ide.ui.generated.resources.guide_step_review_title
import dev.ide.ui.generated.resources.guide_title
import dev.ide.ui.generated.resources.submit_title
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.tonalPair
import org.jetbrains.compose.resources.stringResource

/**
 * What publishing actually does, before someone commits to it.
 *
 * This replaces a "How it works" link that opened the settings hub, which answered nothing. The content is
 * the honest version of the pipeline the store really runs: what leaves the device, what is excluded, that
 * a person reviews it, and the limits — written so someone can decide whether to publish rather than
 * discover the rules by hitting them.
 *
 * [onPublish] is null when this build cannot publish, in which case the guide is still worth reading and
 * simply ends without a call to action.
 */
@Composable
fun PublishingGuideScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPublish: (() -> Unit)? = null,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            DetailTopBar(title = stringResource(Res.string.guide_title), isSaved = false, onBack = onBack)
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 20.dp)) {
                item("intro") {
                    Text(
                        stringResource(Res.string.guide_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
                    )
                }
                itemsIndexedSteps()
                item("limits") {
                    Spacer(Modifier.height(10.dp))
                    FactCard(
                        glyph = CaSymbols.gavel,
                        title = stringResource(Res.string.guide_limits_title),
                        body = stringResource(Res.string.guide_limits_body),
                    )
                }
                item("own") {
                    Spacer(Modifier.height(12.dp))
                    FactCard(
                        glyph = CaSymbols.verified,
                        title = stringResource(Res.string.guide_own_title),
                        body = stringResource(Res.string.guide_own_body),
                    )
                }
                if (onPublish != null) {
                    item("cta") {
                        Spacer(Modifier.height(24.dp))
                        PrimaryActionButton(
                            label = stringResource(Res.string.submit_title),
                            glyph = CaSymbols.upload,
                            onClick = onPublish,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item("tail") { Spacer(Modifier.height(36.dp)) }
            }
        }
    }
}

/** The four steps, numbered, in the order they actually happen. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedSteps() {
    val steps = listOf(
        Triple(1, Res.string.guide_step_package_title, Res.string.guide_step_package_body),
        Triple(2, Res.string.guide_step_review_title, Res.string.guide_step_review_body),
        Triple(3, Res.string.guide_step_live_title, Res.string.guide_step_live_body),
        Triple(4, Res.string.guide_step_notified_title, Res.string.guide_step_notified_body),
    )
    steps.forEach { (number, title, body) ->
        item("step_$number") {
            StepRow(number, stringResource(title), stringResource(body))
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun StepRow(number: Int, title: String, body: String) {
    val pair = tonalPair(number - 1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = CircleShape, color = pair.container, contentColor = pair.onContainer, modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FactCard(glyph: Char, title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(glyph, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                Eyebrow(title)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
