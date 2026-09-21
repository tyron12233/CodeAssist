package dev.ide.ui.theme

import dev.ide.ui.theme.colors.ColorKeys
import dev.ide.ui.theme.colors.ResolvedColorScheme

/**
 * The active scheme projected onto the classic eighteen-field [SyntaxColors].
 *
 * Everything outside the editor canvas that colors code — the onboarding mocks, the code-sample cards, the
 * Code Style preview, the block editor's chips — reads `Ide.colors.syntax`, and they all follow the user's
 * scheme for free by this staying a projection of it rather than becoming a second source of truth.
 *
 * It is an extension here rather than a member of [ResolvedColorScheme] because the scheme model lives a
 * module below, in `ide-ui-api`, where the plugin contribution bridge can reach it; [SyntaxColors] is a
 * theme token and stays with the theme.
 */
fun ResolvedColorScheme.toSyntaxColors(): SyntaxColors = SyntaxColors(
    default = colorOf(ColorKeys.TEXT),
    keyword = colorOf(ColorKeys.KEYWORD),
    storage = colorOf(ColorKeys.KEYWORD_MODIFIER),
    string = colorOf(ColorKeys.STRING),
    number = colorOf(ColorKeys.NUMBER),
    func = colorOf(ColorKeys.FUNCTION),
    type = colorOf(ColorKeys.TYPE),
    comment = colorOf(ColorKeys.COMMENT),
    property = colorOf(ColorKeys.PROPERTY),
    variable = colorOf(ColorKeys.VARIABLE),
    punctuation = colorOf(ColorKeys.PUNCTUATION),
    constant = colorOf(ColorKeys.CONSTANT),
    annotation = colorOf(ColorKeys.ANNOTATION),
    label = colorOf(ColorKeys.LABEL),
    composable = colorOf(ColorKeys.KOTLIN_COMPOSABLE),
    extension = colorOf(ColorKeys.KOTLIN_EXTENSION),
    mutableVar = colorOf(ColorKeys.KOTLIN_MUTABLE),
    suspendFn = colorOf(ColorKeys.KOTLIN_SUSPEND),
)
