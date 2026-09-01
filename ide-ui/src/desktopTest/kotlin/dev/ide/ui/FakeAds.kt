package dev.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.ads.AdController
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend

/**
 * A stand-in ad network for snapshots.
 *
 * Ad slots render nothing without a host, so a snapshot taken without one cannot show whether an ad sits
 * in the list's gutter or bleeds past it — which is exactly the alignment these screens need checked.
 * This draws a labelled block of the real slot's width instead.
 */
class FakeAdHost : AdHost {
    override val available: Boolean = true

    @Composable
    override fun NativeAd(placement: AdPlacement, modifier: Modifier) {
        Box(
            modifier.fillMaxWidth().height(84.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "native ad · ${placement.name.lowercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun fakeAdController(backend: IdeBackend): AdController = AdController(backend, FakeAdHost())
