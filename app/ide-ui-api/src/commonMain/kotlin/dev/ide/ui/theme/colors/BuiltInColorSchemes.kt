package dev.ide.ui.theme.colors

import androidx.compose.ui.graphics.Color
import dev.ide.ui.theme.colors.ColorKeys as K

/**
 * The shipped, read-only schemes.
 *
 * [DEFAULT] is deliberately EMPTY on both sides: the attribute defaults in [BuiltInColorAttributes] ARE the
 * CodeAssist palette, and its chrome is left to the live Material theme, so the default scheme is the
 * absence of a scheme and a fresh install renders exactly as the build before schemes existed.
 *
 * The rest are ports of palettes people already know. Each pins its own editor background, selection and
 * gutter, because half of what makes Solarized recognisably Solarized is the paper it is printed on.
 */
object BuiltInColorSchemes {

    const val DEFAULT_ID = "codeassist"

    val DEFAULT = EditorColorScheme(
        id = DEFAULT_ID,
        name = "CodeAssist",
        builtIn = true,
    )

    val DARCULA = EditorColorScheme(
        id = "darcula", name = "Darcula", builtIn = true,
        dark = variant(
            background = 0xFF2B2B2B, caret = 0xFFBBBBBB, selection = 0xFF214283, currentLine = 0xFF323232,
            gutter = 0xFF606366, gutterCurrent = 0xFFA4A3A3, gutterBorder = 0xFF3C3F41,
            text = 0xFFA9B7C6, keyword = 0xFFCC7832, keywordBold = true, string = 0xFF6A8759,
            number = 0xFF6897BB, comment = 0xFF808080, annotation = 0xFFBBB529, function = 0xFFFFC66D,
            type = 0xFFA9B7C6, property = 0xFF9876AA, variable = 0xFFA9B7C6, constant = 0xFF9876AA,
            punctuation = 0xFFA9B7C6, label = 0xFFBBB529,
            composable = 0xFF6A8759, extension = 0xFFFFC66D, suspendFn = 0xFF9876AA, mutableVar = 0xFFA9B7C6,
            docComment = 0xFF629755,
        ),
        light = variant(
            background = 0xFFFFFFFF, caret = 0xFF000000, selection = 0xFFA6D2FF, currentLine = 0xFFFCFAED,
            gutter = 0xFF999999, gutterCurrent = 0xFF3C3C3C, gutterBorder = 0xFFE8E8E8,
            text = 0xFF080808, keyword = 0xFF0033B3, keywordBold = true, string = 0xFF067D17,
            number = 0xFF1750EB, comment = 0xFF8C8C8C, annotation = 0xFF9E880D, function = 0xFF00627A,
            type = 0xFF080808, property = 0xFF871094, variable = 0xFF080808, constant = 0xFF871094,
            punctuation = 0xFF080808, label = 0xFF9E880D,
            composable = 0xFF067D17, extension = 0xFF00627A, suspendFn = 0xFF871094, mutableVar = 0xFF080808,
            docComment = 0xFF3D7A3D,
        ),
    )

    val ONE = EditorColorScheme(
        id = "one", name = "One", builtIn = true,
        dark = variant(
            background = 0xFF282C34, caret = 0xFF528BFF, selection = 0xFF3E4451, currentLine = 0xFF2C313C,
            gutter = 0xFF4B5263, gutterCurrent = 0xFF9DA5B4, gutterBorder = 0xFF31363F,
            text = 0xFFABB2BF, keyword = 0xFFC678DD, string = 0xFF98C379, number = 0xFFD19A66,
            comment = 0xFF5C6370, annotation = 0xFFE5C07B, function = 0xFF61AFEF, type = 0xFFE5C07B,
            property = 0xFF56B6C2, variable = 0xFFE06C75, constant = 0xFFD19A66, punctuation = 0xFFABB2BF,
            label = 0xFFE5C07B,
            composable = 0xFF56B6C2, extension = 0xFF61AFEF, suspendFn = 0xFFC678DD, mutableVar = 0xFFE06C75,
            operator = 0xFF56B6C2, docComment = 0xFF7F848E,
        ),
        light = variant(
            background = 0xFFFAFAFA, caret = 0xFF526FFF, selection = 0xFFD7DAE0, currentLine = 0xFFF0F0F0,
            gutter = 0xFF9D9D9F, gutterCurrent = 0xFF383A42, gutterBorder = 0xFFE5E5E6,
            text = 0xFF383A42, keyword = 0xFFA626A4, string = 0xFF50A14F, number = 0xFF986801,
            comment = 0xFFA0A1A7, annotation = 0xFFC18401, function = 0xFF4078F2, type = 0xFFC18401,
            property = 0xFF0184BC, variable = 0xFFE45649, constant = 0xFF986801, punctuation = 0xFF383A42,
            label = 0xFFC18401,
            composable = 0xFF0184BC, extension = 0xFF4078F2, suspendFn = 0xFFA626A4, mutableVar = 0xFFE45649,
            operator = 0xFF0184BC, docComment = 0xFF9CA0A4,
        ),
    )

    val SOLARIZED = EditorColorScheme(
        id = "solarized", name = "Solarized", builtIn = true,
        dark = variant(
            background = 0xFF002B36, caret = 0xFF93A1A1, selection = 0xFF073642, currentLine = 0xFF073642,
            gutter = 0xFF586E75, gutterCurrent = 0xFF93A1A1, gutterBorder = 0xFF073642,
            text = 0xFF839496, keyword = 0xFF859900, string = 0xFF2AA198, number = 0xFFD33682,
            comment = 0xFF586E75, annotation = 0xFFB58900, function = 0xFF268BD2, type = 0xFFB58900,
            property = 0xFF2AA198, variable = 0xFFCB4B16, constant = 0xFFD33682, punctuation = 0xFF657B83,
            label = 0xFF6C71C4,
            composable = 0xFF859900, extension = 0xFF268BD2, suspendFn = 0xFF6C71C4, mutableVar = 0xFFCB4B16,
            control = 0xFFCB4B16, operator = 0xFF2AA198, docComment = 0xFF657B83,
        ),
        light = variant(
            background = 0xFFFDF6E3, caret = 0xFF586E75, selection = 0xFFEEE8D5, currentLine = 0xFFEEE8D5,
            gutter = 0xFF93A1A1, gutterCurrent = 0xFF586E75, gutterBorder = 0xFFEEE8D5,
            text = 0xFF657B83, keyword = 0xFF859900, string = 0xFF2AA198, number = 0xFFD33682,
            comment = 0xFF93A1A1, annotation = 0xFFB58900, function = 0xFF268BD2, type = 0xFFB58900,
            property = 0xFF2AA198, variable = 0xFFCB4B16, constant = 0xFFD33682, punctuation = 0xFF93A1A1,
            label = 0xFF6C71C4,
            composable = 0xFF859900, extension = 0xFF268BD2, suspendFn = 0xFF6C71C4, mutableVar = 0xFFCB4B16,
            control = 0xFFCB4B16, operator = 0xFF2AA198, docComment = 0xFF839496,
        ),
    )

    val GITHUB = EditorColorScheme(
        id = "github", name = "GitHub", builtIn = true,
        dark = variant(
            background = 0xFF0D1117, caret = 0xFF58A6FF, selection = 0xFF264F78, currentLine = 0xFF161B22,
            gutter = 0xFF6E7681, gutterCurrent = 0xFFC9D1D9, gutterBorder = 0xFF21262D,
            text = 0xFFE6EDF3, keyword = 0xFFFF7B72, string = 0xFFA5D6FF, number = 0xFF79C0FF,
            comment = 0xFF8B949E, annotation = 0xFFD2A8FF, function = 0xFFD2A8FF, type = 0xFFFFA657,
            property = 0xFF79C0FF, variable = 0xFFFFA657, constant = 0xFF79C0FF, punctuation = 0xFFC9D1D9,
            label = 0xFFFFA657,
            composable = 0xFF7EE787, extension = 0xFFD2A8FF, suspendFn = 0xFFFF7B72, mutableVar = 0xFFFFA657,
            operator = 0xFFFF7B72, docComment = 0xFF8B949E,
        ),
        light = variant(
            background = 0xFFFFFFFF, caret = 0xFF0969DA, selection = 0xFFB6D6FD, currentLine = 0xFFF6F8FA,
            gutter = 0xFF8C959F, gutterCurrent = 0xFF1F2328, gutterBorder = 0xFFD1D9E0,
            text = 0xFF1F2328, keyword = 0xFFCF222E, string = 0xFF0A3069, number = 0xFF0550AE,
            comment = 0xFF6E7781, annotation = 0xFF8250DF, function = 0xFF8250DF, type = 0xFF953800,
            property = 0xFF0550AE, variable = 0xFF953800, constant = 0xFF0550AE, punctuation = 0xFF1F2328,
            label = 0xFF953800,
            composable = 0xFF1A7F37, extension = 0xFF8250DF, suspendFn = 0xFFCF222E, mutableVar = 0xFF953800,
            operator = 0xFFCF222E, docComment = 0xFF6E7781,
        ),
    )

    // The two Halloween palettes are our own rather than ports. Each still pins its paper, for the same reason:
    // a pumpkin orange on someone else's grey background is just an orange.

    /** Jack-o'-lantern keywords, slime-green strings and witch's-hat purple numbers on a plum night. */
    val PUMPKIN = EditorColorScheme(
        id = "pumpkin", name = "Pumpkin Patch", builtIn = true,
        dark = variant(
            background = 0xFF1A1418, caret = 0xFFFF8C1A, selection = 0xFF4A2E52, currentLine = 0xFF231B22,
            gutter = 0xFF6B5A66, gutterCurrent = 0xFFE8B98A, gutterBorder = 0xFF2A2129,
            text = 0xFFE8DCCF, keyword = 0xFFFF8C1A, keywordBold = true, string = 0xFFA8D86A,
            number = 0xFFC792EA, comment = 0xFF7D6E79, annotation = 0xFFFFCB6B, function = 0xFFFFB86C,
            type = 0xFFD7A8FF, property = 0xFFF2A97E, variable = 0xFFE8DCCF, constant = 0xFFC792EA,
            punctuation = 0xFFB8A9B3, label = 0xFFFFCB6B,
            composable = 0xFFA8D86A, extension = 0xFFFFB86C, suspendFn = 0xFFD7A8FF, mutableVar = 0xFFE8DCCF,
            control = 0xFFFF5F45, docComment = 0xFF9A8794,
        ),
        light = variant(
            background = 0xFFFFF7EE, caret = 0xFFD35F00, selection = 0xFFF3D9C2, currentLine = 0xFFFCEFE2,
            gutter = 0xFFB0A090, gutterCurrent = 0xFF5A3A28, gutterBorder = 0xFFF0E2D3,
            text = 0xFF2E2226, keyword = 0xFFC45200, keywordBold = true, string = 0xFF4E7A12,
            number = 0xFF7B3FB0, comment = 0xFF9C8C82, annotation = 0xFFA06A00, function = 0xFFB34700,
            type = 0xFF6A35A0, property = 0xFFA0462E, variable = 0xFF2E2226, constant = 0xFF7B3FB0,
            punctuation = 0xFF5E4E52, label = 0xFFA06A00,
            composable = 0xFF4E7A12, extension = 0xFFB34700, suspendFn = 0xFF6A35A0, mutableVar = 0xFF2E2226,
            control = 0xFFB3261E, docComment = 0xFF8A7A70,
        ),
    )

    /** Moonlit fog: ghost-green keywords, parchment strings and faded-red numbers on a cold green-black. */
    val GRAVEYARD = EditorColorScheme(
        id = "graveyard", name = "Graveyard", builtIn = true,
        dark = variant(
            background = 0xFF121614, caret = 0xFFB8F2C8, selection = 0xFF2C3A33, currentLine = 0xFF18201C,
            gutter = 0xFF4E5C55, gutterCurrent = 0xFFB9C9C0, gutterBorder = 0xFF1E2622,
            text = 0xFFC9D3CD, keyword = 0xFF8FD6A6, string = 0xFFD9C38C, number = 0xFFE0A0A0,
            comment = 0xFF5F6E66, annotation = 0xFFA7B8E8, function = 0xFFE9F0EC, type = 0xFFA7B8E8,
            property = 0xFF9CC9C0, variable = 0xFFC9D3CD, constant = 0xFFE0A0A0, punctuation = 0xFF8A9890,
            label = 0xFFA7B8E8,
            composable = 0xFF8FD6A6, extension = 0xFFE9F0EC, suspendFn = 0xFFB9A7E8, mutableVar = 0xFFC9D3CD,
            docComment = 0xFF718078,
        ),
        light = variant(
            background = 0xFFF3F5F2, caret = 0xFF2F6B4A, selection = 0xFFD3E0D8, currentLine = 0xFFE9EEEA,
            gutter = 0xFF9AA59F, gutterCurrent = 0xFF3A4540, gutterBorder = 0xFFE0E6E2,
            text = 0xFF26302B, keyword = 0xFF2F6B4A, string = 0xFF7A5A12, number = 0xFF9A3B3B,
            comment = 0xFF8A968F, annotation = 0xFF3E55A0, function = 0xFF1B2420, type = 0xFF3E55A0,
            property = 0xFF2C6B66, variable = 0xFF26302B, constant = 0xFF9A3B3B, punctuation = 0xFF5A6560,
            label = 0xFF3E55A0,
            composable = 0xFF2F6B4A, extension = 0xFF1B2420, suspendFn = 0xFF5E48A0, mutableVar = 0xFF26302B,
            docComment = 0xFF77837C,
        ),
    )

    // The two winter palettes, our own as well. Both are meant to be coded in for weeks, so they stay soft:
    // the season is in the hues (aurora, starlight, cocoa), not in saturation.

    /** Violet keywords, aurora-teal strings and star-gold numbers on a deep navy night. */
    val POLAR_NIGHT = EditorColorScheme(
        id = "polar-night", name = "Polar Night", builtIn = true,
        dark = variant(
            background = 0xFF0E1424, caret = 0xFFF2CF7A, selection = 0xFF253252, currentLine = 0xFF131A2C,
            gutter = 0xFF4E587A, gutterCurrent = 0xFFC9D0E6, gutterBorder = 0xFF1A2236,
            text = 0xFFDCE2F0, keyword = 0xFFB9A6FF, string = 0xFF6FE0C8, number = 0xFFF2CF7A,
            comment = 0xFF5F6A88, annotation = 0xFFF2CF7A, function = 0xFFE8EEFF, type = 0xFF8FC8FF,
            property = 0xFFA9C2F0, variable = 0xFFDCE2F0, constant = 0xFFF2CF7A, punctuation = 0xFF8A94B4,
            label = 0xFFF2CF7A,
            composable = 0xFF6FE0C8, extension = 0xFFE8EEFF, suspendFn = 0xFFB9A6FF, mutableVar = 0xFFDCE2F0,
            control = 0xFFD3C4FF, docComment = 0xFF6C7798,
        ),
        light = variant(
            background = 0xFFF7F8FD, caret = 0xFF0E7A6A, selection = 0xFFD7DCF2, currentLine = 0xFFEEF0F8,
            gutter = 0xFF9AA0B8, gutterCurrent = 0xFF2E3550, gutterBorder = 0xFFE0E3EF,
            text = 0xFF1B2133, keyword = 0xFF5B4BB0, string = 0xFF0E7A6A, number = 0xFF8A6400,
            comment = 0xFF8890A8, annotation = 0xFF8A6400, function = 0xFF141A2E, type = 0xFF1F5F99,
            property = 0xFF3D5590, variable = 0xFF1B2133, constant = 0xFF8A6400, punctuation = 0xFF586080,
            label = 0xFF8A6400,
            composable = 0xFF0E7A6A, extension = 0xFF141A2E, suspendFn = 0xFF5B4BB0, mutableVar = 0xFF1B2133,
            control = 0xFF4A3AA0, docComment = 0xFF7B84A2,
        ),
    )

    /** Cinnamon keywords, caramel strings, berry numbers and cream functions on a warm cup of cocoa. */
    val COCOA = EditorColorScheme(
        id = "cocoa", name = "Cocoa", builtIn = true,
        dark = variant(
            background = 0xFF1B1512, caret = 0xFFF5D6A8, selection = 0xFF45342A, currentLine = 0xFF231B17,
            gutter = 0xFF6E5D52, gutterCurrent = 0xFFE6D3C0, gutterBorder = 0xFF2A211C,
            text = 0xFFEEE2D6, keyword = 0xFFE8A87C, keywordBold = true, string = 0xFFE6C17A,
            number = 0xFFF29E8E, comment = 0xFF857266, annotation = 0xFFE3B5A4, function = 0xFFF5E6D3,
            type = 0xFFD9B38C, property = 0xFFE0B894, variable = 0xFFEEE2D6, constant = 0xFFF29E8E,
            punctuation = 0xFFB3A196, label = 0xFFE3B5A4,
            composable = 0xFFE6C17A, extension = 0xFFF5E6D3, suspendFn = 0xFFD9B38C, mutableVar = 0xFFEEE2D6,
            docComment = 0xFF9A8578,
        ),
        light = variant(
            background = 0xFFFCF7F2, caret = 0xFF7A4A2A, selection = 0xFFEEDCCB, currentLine = 0xFFF6EDE4,
            gutter = 0xFFB3A296, gutterCurrent = 0xFF5A4030, gutterBorder = 0xFFEFE3D8,
            text = 0xFF2E231D, keyword = 0xFF9A4E1E, keywordBold = true, string = 0xFF8A5A00,
            number = 0xFFB03A3A, comment = 0xFF9C8B80, annotation = 0xFF8E4A5A, function = 0xFF5A2E14,
            type = 0xFF7A5230, property = 0xFF8A4A2C, variable = 0xFF2E231D, constant = 0xFFB03A3A,
            punctuation = 0xFF5E4E46, label = 0xFF8E4A5A,
            composable = 0xFF8A5A00, extension = 0xFF5A2E14, suspendFn = 0xFF7A5230, mutableVar = 0xFF2E231D,
            docComment = 0xFF8A7A70,
        ),
    )

    val all: List<EditorColorScheme> = listOf(DEFAULT, DARCULA, ONE, SOLARIZED, GITHUB, PUMPKIN, GRAVEYARD, POLAR_NIGHT, COCOA)

    fun byId(id: String): EditorColorScheme? = all.firstOrNull { it.id == id }

    // ---- keepsakes: the schemes the winter countdown gives away ----
    //
    // Presets like the rest (code, read-only), but not in [all]: a keepsake is listed only once its day has
    // been opened, which [ColorSchemeStore] decides from the unlocked ids it stores. Once unlocked one stays,
    // season or not; it was a present.

    /** Ice-blue keywords, mint strings and lilac numbers on a frosted night. */
    val FROSTED_GLASS = EditorColorScheme(
        id = "frosted-glass", name = "Frosted Glass", builtIn = true,
        dark = variant(
            background = 0xFF101820, caret = 0xFFBFE3F2, selection = 0xFF24384A, currentLine = 0xFF15202A,
            gutter = 0xFF4E6272, gutterCurrent = 0xFFC4D8E6, gutterBorder = 0xFF1A2733,
            text = 0xFFD8E6EF, keyword = 0xFF8CC8F0, string = 0xFFB8E0D2, number = 0xFFE0C8F5,
            comment = 0xFF62788A, annotation = 0xFFA8C0F0, function = 0xFFF0F6FA, type = 0xFF9FD8E8,
            property = 0xFFB4CFE0, variable = 0xFFD8E6EF, constant = 0xFFE0C8F5, punctuation = 0xFF8FA4B4,
            label = 0xFFA8C0F0,
            composable = 0xFFB8E0D2, extension = 0xFFF0F6FA, suspendFn = 0xFFA8C0F0, mutableVar = 0xFFD8E6EF,
            docComment = 0xFF71889A,
        ),
        light = variant(
            background = 0xFFF5F9FC, caret = 0xFF2A6E9A, selection = 0xFFD3E5F2, currentLine = 0xFFEAF2F8,
            gutter = 0xFF9AAAB8, gutterCurrent = 0xFF33485A, gutterBorder = 0xFFDCE6EE,
            text = 0xFF1F2B36, keyword = 0xFF1E6A9E, string = 0xFF2F7A68, number = 0xFF7A4FB0,
            comment = 0xFF8395A4, annotation = 0xFF3E5CA8, function = 0xFF15212B, type = 0xFF16707F,
            property = 0xFF3A6078, variable = 0xFF1F2B36, constant = 0xFF7A4FB0, punctuation = 0xFF55687A,
            label = 0xFF3E5CA8,
            composable = 0xFF2F7A68, extension = 0xFF15212B, suspendFn = 0xFF3E5CA8, mutableVar = 0xFF1F2B36,
            docComment = 0xFF7A8C9B,
        ),
    )

    /** Ember keywords, sage strings and rosy numbers on a warm hearth, with firelit cream for text. */
    val FIRELIGHT = EditorColorScheme(
        id = "firelight", name = "Firelight", builtIn = true,
        dark = variant(
            background = 0xFF1E1712, caret = 0xFFF5E6C8, selection = 0xFF4A3526, currentLine = 0xFF261D17,
            gutter = 0xFF75604E, gutterCurrent = 0xFFE8D3B5, gutterBorder = 0xFF2D231B,
            text = 0xFFF0E2CC, keyword = 0xFFE59A5C, keywordBold = true, string = 0xFFA8C97F,
            number = 0xFFF07F86, comment = 0xFF8A7563, annotation = 0xFFF5C77E, function = 0xFFF5E6C8,
            type = 0xFFD9B38C, property = 0xFFE0B894, variable = 0xFFF0E2CC, constant = 0xFFF07F86,
            punctuation = 0xFFB8A08A, label = 0xFFF5C77E,
            composable = 0xFFA8C97F, extension = 0xFFF5E6C8, suspendFn = 0xFFD9B38C, mutableVar = 0xFFF0E2CC,
            docComment = 0xFF9C8673,
        ),
        light = variant(
            background = 0xFFFCF5EA, caret = 0xFF8A4B1E, selection = 0xFFF0DCC2, currentLine = 0xFFF6EBDC,
            gutter = 0xFFB5A08A, gutterCurrent = 0xFF5A3D28, gutterBorder = 0xFFEEE0CC,
            text = 0xFF33251B, keyword = 0xFFA1501A, keywordBold = true, string = 0xFF4F7A22,
            number = 0xFFB8323F, comment = 0xFFA08C78, annotation = 0xFF9A6400, function = 0xFF5A2E10,
            type = 0xFF8A5A2E, property = 0xFF8A4A26, variable = 0xFF33251B, constant = 0xFFB8323F,
            punctuation = 0xFF6A5444, label = 0xFF9A6400,
            composable = 0xFF4F7A22, extension = 0xFF5A2E10, suspendFn = 0xFF8A5A2E, mutableVar = 0xFF33251B,
            docComment = 0xFF8E7A68,
        ),
    )

    /** Aurora-green keywords, violet strings and pink numbers across a polar night. */
    val NORTHERN_LIGHTS = EditorColorScheme(
        id = "northern-lights", name = "Northern Lights", builtIn = true,
        dark = variant(
            background = 0xFF0E1220, caret = 0xFF7CF2C4, selection = 0xFF243150, currentLine = 0xFF141A2B,
            gutter = 0xFF4D587A, gutterCurrent = 0xFFC4CCE8, gutterBorder = 0xFF1A2135,
            text = 0xFFDCE2F2, keyword = 0xFF7CF2C4, string = 0xFFB79CFF, number = 0xFFFF9EC7,
            comment = 0xFF5D6888, annotation = 0xFF6FD3F2, function = 0xFFE9F4FF, type = 0xFF6FD3F2,
            property = 0xFFA9C2F0, variable = 0xFFDCE2F2, constant = 0xFFFF9EC7, punctuation = 0xFF8A94B4,
            label = 0xFF6FD3F2,
            composable = 0xFF7CF2C4, extension = 0xFFE9F4FF, suspendFn = 0xFFB79CFF, mutableVar = 0xFFDCE2F2,
            docComment = 0xFF6C7798,
        ),
        light = variant(
            background = 0xFFF6F7FC, caret = 0xFF0E8A63, selection = 0xFFD9DEF5, currentLine = 0xFFECEFF8,
            gutter = 0xFF9AA2BE, gutterCurrent = 0xFF2F3754, gutterBorder = 0xFFDDE1EF,
            text = 0xFF1C2238, keyword = 0xFF0E7A58, string = 0xFF6A3FC8, number = 0xFFB8326E,
            comment = 0xFF8790AE, annotation = 0xFF0A6F96, function = 0xFF141A30, type = 0xFF0A6F96,
            property = 0xFF3D5590, variable = 0xFF1C2238, constant = 0xFFB8326E, punctuation = 0xFF586080,
            label = 0xFF0A6F96,
            composable = 0xFF0E7A58, extension = 0xFF141A30, suspendFn = 0xFF6A3FC8, mutableVar = 0xFF1C2238,
            docComment = 0xFF7B84A2,
        ),
    )

    val keepsakes: List<EditorColorScheme> = listOf(FROSTED_GLASS, FIRELIGHT, NORTHERN_LIGHTS)

    fun keepsake(id: String): EditorColorScheme? = keepsakes.firstOrNull { it.id == id }

    /**
     * One preset variant, written as the handful of anchors a palette actually names.
     *
     * Everything a preset leaves out — modifier keywords, escapes, type parameters, the XML and Markdown
     * groups — reaches its color through the fallback chain, which is the whole reason the chain exists: a
     * palette designed years before this editor distinguished a suspend function still colors one.
     */
    private fun variant(
        background: Long, caret: Long, selection: Long, currentLine: Long,
        gutter: Long, gutterCurrent: Long, gutterBorder: Long,
        text: Long, keyword: Long, string: Long, number: Long, comment: Long, annotation: Long,
        function: Long, type: Long, property: Long, variable: Long, constant: Long, punctuation: Long,
        label: Long, composable: Long, extension: Long, suspendFn: Long, mutableVar: Long,
        keywordBold: Boolean = false,
        /** Control words, where the palette sets them apart from other keywords. */
        control: Long? = null,
        /** Operators, where the palette sets them apart from brackets and separators. */
        operator: Long? = null,
        /** Doc comments, where the palette sets them apart from ordinary ones. */
        docComment: Long? = null,
    ): Map<String, AttributeStyle> = buildMap {
        put(K.EDITOR_BACKGROUND, AttributeStyle.bg(Color(background)))
        put(K.EDITOR_CARET, AttributeStyle.fg(Color(caret)))
        put(K.EDITOR_SELECTION, AttributeStyle.bg(Color(selection)))
        put(K.EDITOR_CURRENT_LINE, AttributeStyle.bg(Color(currentLine)))
        put(K.GUTTER_TEXT, AttributeStyle.fg(Color(gutter)))
        put(K.GUTTER_CURRENT, AttributeStyle.fg(Color(gutterCurrent)))
        put(K.GUTTER_BORDER, AttributeStyle.fg(Color(gutterBorder)))
        put(K.TEXT, AttributeStyle.fg(Color(text)))
        put(K.KEYWORD, AttributeStyle(foreground = Color(keyword), bold = if (keywordBold) true else null))
        put(K.STRING, AttributeStyle.fg(Color(string)))
        put(K.NUMBER, AttributeStyle.fg(Color(number)))
        put(K.COMMENT, AttributeStyle(foreground = Color(comment), italic = true))
        put(K.ANNOTATION, AttributeStyle.fg(Color(annotation)))
        put(K.FUNCTION, AttributeStyle.fg(Color(function)))
        put(K.TYPE, AttributeStyle.fg(Color(type)))
        put(K.PROPERTY, AttributeStyle.fg(Color(property)))
        put(K.VARIABLE, AttributeStyle.fg(Color(variable)))
        put(K.CONSTANT, AttributeStyle.fg(Color(constant)))
        put(K.PUNCTUATION, AttributeStyle.fg(Color(punctuation)))
        put(K.LABEL, AttributeStyle(foreground = Color(label), italic = true))
        put(K.KOTLIN_COMPOSABLE, AttributeStyle(foreground = Color(composable), bold = true))
        put(K.KOTLIN_EXTENSION, AttributeStyle(foreground = Color(extension), italic = true))
        put(K.KOTLIN_SUSPEND, AttributeStyle(foreground = Color(suspendFn), italic = true))
        put(K.KOTLIN_MUTABLE, AttributeStyle(foreground = Color(mutableVar), underline = true))
        // The interpolation delimiters have to leave the string to read as code; every palette here agrees
        // with the shipped one that the keyword color is what does that.
        put(K.STRING_TEMPLATE, AttributeStyle.fg(Color(keyword)))
        put(K.STRING_ESCAPE, AttributeStyle.fg(Color(number)))
        // The finer classes, only where the palette being ported actually distinguishes them. A preset that
        // says nothing leaves them inheriting, which is how the palette itself renders.
        control?.let { put(K.KEYWORD_CONTROL, AttributeStyle.fg(Color(it))) }
        operator?.let { put(K.OPERATOR, AttributeStyle.fg(Color(it))) }
        docComment?.let { put(K.COMMENT_DOC, AttributeStyle(foreground = Color(it), italic = true)) }
    }
}
