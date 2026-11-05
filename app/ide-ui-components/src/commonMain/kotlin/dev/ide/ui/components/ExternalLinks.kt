package dev.ide.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.link_copy
import dev.ide.ui.generated.resources.link_from_user
import dev.ide.ui.generated.resources.link_mail_from_user
import dev.ide.ui.generated.resources.link_mail_title
import dev.ide.ui.generated.resources.link_open
import dev.ide.ui.generated.resources.link_open_title
import dev.ide.ui.generated.resources.link_warn_http
import dev.ide.ui.generated.resources.link_warn_ip
import dev.ide.ui.generated.resources.link_warn_lookalike
import dev.ide.ui.generated.resources.link_warn_userinfo
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.markdown.isOpenableUrl
import dev.ide.ui.theme.Symbol
import org.jetbrains.compose.resources.stringResource

/** Something about a link a reader should know before following it. */
enum class LinkWarning {
    /** Plain `http://`: the page can be read or changed on the way. */
    NotEncrypted,

    /** `https://trusted.example@other.example`: what reads as the site is a user name; [LinkCheck.target] is the real host. */
    Disguised,

    /** A bare IPv4/IPv6 address instead of a name. */
    RawAddress,

    /** Non-ASCII or punycode (`xn--`) in the host, which can spell a well-known name with lookalike letters. */
    Lookalike,
}

/** Where a link really goes ([target]: the host, or the address of a `mailto:`) and what is odd about it. */
data class LinkCheck(val target: String, val isMail: Boolean, val warnings: List<LinkWarning>)

private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * Reads [url] the way a browser would to find the host it goes to. Only `http(s)` and `mailto:` reach
 * here ([isOpenableUrl] filters the rest before a link is made).
 */
fun checkLink(url: String): LinkCheck {
    val u = url.trim()
    if (u.startsWith("mailto:", ignoreCase = true)) {
        return LinkCheck(u.substring(7).substringBefore('?'), isMail = true, warnings = emptyList())
    }
    val authority = u.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
    val hostPort = authority.substringAfterLast('@')
    val host = (if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':'))
        .trimEnd('.')
        .lowercase()
    val warnings = buildList {
        if (u.startsWith("http://", ignoreCase = true)) add(LinkWarning.NotEncrypted)
        if ('@' in authority) add(LinkWarning.Disguised)
        if (host.startsWith("[") || IPV4.matches(host)) add(LinkWarning.RawAddress)
        if (host.any { it.code > 0x7F } || host.split('.').any { it.startsWith("xn--") }) add(LinkWarning.Lookalike)
    }
    return LinkCheck(host, isMail = false, warnings = warnings)
}

/**
 * Asks before leaving the app for a link someone else wrote (a store listing, a review, a reply).
 *
 * Every link in [content] opens through [LocalUriHandler] (a Markdown or [dev.ide.ui.markdown.linkify]
 * link carries a `LinkAnnotation.Url`, which `Text` opens that way), so providing a handler here catches
 * them all without each text knowing. The dialog names the real host, since a link's text can say one
 * site and point at another, and lists anything suspicious about the address.
 */
@Composable
fun ExternalLinkGuard(content: @Composable () -> Unit) {
    val platform = LocalUriHandler.current
    var pending by remember { mutableStateOf<String?>(null) }
    val guarded = remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (isOpenableUrl(uri)) pending = uri
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides guarded, content = content)
    pending?.let { url ->
        ExternalLinkDialog(
            url = url,
            onOpen = {
                pending = null
                runCatching { platform.openUri(url) }
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun ExternalLinkDialog(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val check = remember(url) { checkLink(url) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Symbol(
                if (check.warnings.isEmpty()) CaSymbols.link else CaSymbols.warning,
                contentDescription = null,
                size = 24.dp,
                tint = if (check.warnings.isEmpty()) c.secondary else c.error,
            )
        },
        title = { Text(stringResource(if (check.isMail) Res.string.link_mail_title else Res.string.link_open_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(if (check.isMail) Res.string.link_mail_from_user else Res.string.link_from_user),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            check.target,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = c.onSurface,
                        )
                        if (!check.isMail) {
                            Text(
                                url,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = dev.ide.ui.theme.Ca.type.codeFamily),
                                color = c.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                check.warnings.forEach { warning ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Symbol(CaSymbols.warning, contentDescription = null, size = 18.dp, tint = c.error)
                        Text(
                            when (warning) {
                                LinkWarning.NotEncrypted -> stringResource(Res.string.link_warn_http)
                                LinkWarning.Disguised -> stringResource(Res.string.link_warn_userinfo, check.target)
                                LinkWarning.RawAddress -> stringResource(Res.string.link_warn_ip)
                                LinkWarning.Lookalike -> stringResource(Res.string.link_warn_lookalike)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.error,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
        },
        // Stacked: three labels do not fit one row on a narrow phone in every language.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                FilledTonalButton(onClick = onOpen) { Text(stringResource(Res.string.link_open)) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(if (check.isMail) check.target else url)); onDismiss() }) {
                    Text(stringResource(Res.string.link_copy))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}
