package dev.ide.ui.icons

/**
 * Material Symbols Rounded, addressed by codepoint.
 *
 * The UI carries two icon vocabularies and they are not interchangeable. [CaIcons] is the hand-drawn
 * line set used by the editor chrome, the file tree and plugin actions. This one is the Material
 * Symbols face the Home / Explore / Learn redesign is drawn in, bundled as two subset fonts under
 * `ide-ui/src/commonMain/composeResources/font/` and rendered through `Symbol()` in `theme/`.
 *
 * Glyphs are referenced by **codepoint, not by ligature**. Material Symbols normally lets you write the
 * glyph's name as text and relies on font ligatures to fold it into one glyph; the bundled subsets drop
 * all layout features, so `"chevron_right"` would render as thirteen letters. Always go through these
 * constants (or [byName]).
 *
 * ### Regenerating the subsets
 *
 * Only the glyphs listed here are kept, which is why the two files are ~15 KB each instead of 15 MB.
 * To add one, append its name below and re-run:
 *
 * ```sh
 * pip3 install --user fonttools brotli
 * BASE=https://raw.githubusercontent.com/google/material-design-icons/master/variablefont
 * curl -sLO "$BASE/MaterialSymbolsRounded%5BFILL%2CGRAD%2Copsz%2Cwght%5D.ttf"
 * curl -sLO "$BASE/MaterialSymbolsRounded%5BFILL%2CGRAD%2Copsz%2Cwght%5D.codepoints"
 * # instance at FILL=0 and FILL=1 (wght 400, GRAD 0, opsz 24), then subset to these codepoints
 * ```
 *
 * The two instances exist because the FILL axis is how Material Symbols expresses a selected state, and
 * a static font cannot interpolate an axis: `material_symbols_rounded.ttf` is FILL 0 and
 * `material_symbols_rounded_filled.ttf` is FILL 1.
 */
object CaSymbols {
    const val accountCircle          = '\uf20b' // account_circle
    const val add                    = '\ue145' // add
    const val apartment              = '\uea40' // apartment
    const val arrowBack              = '\ue5c4' // arrow_back
    const val arrowDropDown          = '\ue5c5' // arrow_drop_down
    const val arrowDropUp            = '\ue5c7' // arrow_drop_up
    const val arrowForward           = '\ue5c8' // arrow_forward
    const val block                  = '\uf08c' // block
    const val bolt                   = '\uea0b' // bolt
    const val bookmark               = '\ue8e7' // bookmark
    const val cancel                 = '\ue888' // cancel
    const val check                  = '\ue668' // check
    const val checkCircle            = '\uf0be' // check_circle
    const val chevronLeft            = '\ue5cb' // chevron_left
    const val chevronRight           = '\ue5cc' // chevron_right
    const val close                  = '\ue5cd' // close
    const val cloudDownload          = '\ue2c0' // cloud_download
    const val codeBlocks             = '\uf84d' // code_blocks
    const val coffee                 = '\uefef' // coffee
    const val construction           = '\uea3c' // construction
    const val create                 = '\uf097' // create
    const val darkMode               = '\ue51c' // dark_mode
    const val delete                 = '\ue92e' // delete
    const val deployedCode           = '\uf720' // deployed_code
    const val dns                    = '\ue875' // dns
    const val download               = '\uf090' // download
    const val downloadDone           = '\uf091' // download_done
    const val downloading            = '\uf001' // downloading
    const val edit                   = '\uf097' // edit
    const val error                  = '\uf8b6' // error
    const val expandMore             = '\ue5cf' // expand_more
    const val explore                = '\ue87a' // explore
    const val extension              = '\ue87b' // extension
    const val folder                 = '\ue2c7' // folder
    const val folderOpen             = '\ue2c8' // folder_open
    const val forkRight              = '\uebac' // fork_right
    const val forum                  = '\ue8af' // forum
    const val functions              = '\ue24a' // functions
    const val gavel                  = '\ue90e' // gavel
    const val home                   = '\ue9b2' // home
    const val hourglassTop           = '\uea5b' // hourglass_top
    const val hub                    = '\ue9f4' // hub
    const val inbox                  = '\ue156' // inbox
    const val info                   = '\ue88e' // info
    const val layers                 = '\ue53b' // layers
    const val light                  = '\uf02a' // light
    const val lightMode              = '\ue518' // light_mode
    const val link                   = '\ue250' // link
    const val localFireDepartment    = '\uef55' // local_fire_department
    const val moreVert               = '\ue5d4' // more_vert
    const val notes                  = '\ue26c' // notes
    const val openInFull             = '\uf1ce' // open_in_full
    const val overview               = '\ue4a7' // overview
    const val palette                = '\ue40a' // palette
    const val person                 = '\uf0d3' // person
    const val phoneAndroid           = '\uf2db' // phone_android
    const val playArrow              = '\ue037' // play_arrow
    const val progressActivity       = '\ue9d0' // progress_activity
    const val rateReview             = '\ue560' // rate_review
    const val remove                 = '\ue15b' // remove
    const val reviews                = '\uf07c' // reviews
    const val rocketLaunch           = '\ueb9b' // rocket_launch
    const val schedule               = '\uefd6' // schedule
    const val school                 = '\ue80c' // school
    const val search                 = '\uef7a' // search
    const val searchOff              = '\uea76' // search_off
    const val settings               = '\ue8b8' // settings
    const val share                  = '\ue80d' // share
    const val sort                   = '\ue164' // sort
    const val star                   = '\uf09a' // star
    const val stickyNote2            = '\uf1fc' // sticky_note_2
    const val storage                = '\ue1db' // storage
    const val swapVert               = '\ue8d5' // swap_vert
    const val sync                   = '\ue627' // sync
    const val syncAlt                = '\uea18' // sync_alt
    const val terminal               = '\ueb8e' // terminal
    const val thumbUp                = '\uf577' // thumb_up
    const val travelExplore          = '\ue2db' // travel_explore
    const val trendingUp             = '\ue8e5' // trending_up
    const val tune                   = '\ue429' // tune
    const val update                 = '\ue923' // update
    const val upload                 = '\uf09b' // upload
    const val verified               = '\uef76' // verified
    const val visibility             = '\ue8f4' // visibility
    const val warning                = '\uf083' // warning
    const val workspacePremium       = '\ue7af' // workspace_premium

    /**
     * The Material Symbol for one of the app's own `iconId` values.
     *
     * Project templates, learn tracks and store rows all name their icon in the [CaIcons] vocabulary
     * (`"kotlin"`, `"module.android"`, `"pkg"`). Those names are stored in content and in the project
     * model, so the redesigned screens translate at the point of use rather than rewriting the content.
     * A name already spelled as a Material Symbol wins; everything else falls through the map; anything
     * unrecognised returns [fallback].
     */
    fun forIconId(iconId: String, fallback: Char = folder): Char =
        byName(iconId) ?: when (iconId.substringBefore('.')) {
            "kotlin" -> bolt
            "java" -> coffee
            "module" -> if (iconId == "module.android") phoneAndroid else layers
            "workspace" -> hub
            "manifest" -> stickyNote2
            "pkg" -> extension
            "hammer" -> construction
            "sparkle" -> palette
            "layers" -> layers
            "grid" -> codeBlocks
            "android" -> phoneAndroid
            "docText", "file" -> stickyNote2
            "run" -> playArrow
            "refresh" -> sync
            else -> fallback
        }

    /**
     * The glyph for a Material Symbols [name] (`"chevron_right"`), or `null` when the subset does not
     * carry it. Backend-supplied icon names arrive as strings, so a store row naming a glyph we did not
     * bundle has to degrade to a fallback rather than render a missing box.
     */
    fun byName(name: String): Char? = when (name) {
        "account_circle" -> accountCircle
        "add" -> add
        "apartment" -> apartment
        "arrow_back" -> arrowBack
        "arrow_drop_down" -> arrowDropDown
        "arrow_drop_up" -> arrowDropUp
        "arrow_forward" -> arrowForward
        "block" -> block
        "bolt" -> bolt
        "bookmark" -> bookmark
        "cancel" -> cancel
        "check" -> check
        "check_circle" -> checkCircle
        "chevron_left" -> chevronLeft
        "chevron_right" -> chevronRight
        "close" -> close
        "cloud_download" -> cloudDownload
        "code_blocks" -> codeBlocks
        "coffee" -> coffee
        "construction" -> construction
        "create" -> create
        "dark_mode" -> darkMode
        "delete" -> delete
        "deployed_code" -> deployedCode
        "dns" -> dns
        "download" -> download
        "download_done" -> downloadDone
        "downloading" -> downloading
        "edit" -> edit
        "error" -> error
        "expand_more" -> expandMore
        "explore" -> explore
        "extension" -> extension
        "folder" -> folder
        "folder_open" -> folderOpen
        "fork_right" -> forkRight
        "forum" -> forum
        "functions" -> functions
        "gavel" -> gavel
        "home" -> home
        "hourglass_top" -> hourglassTop
        "hub" -> hub
        "inbox" -> inbox
        "info" -> info
        "layers" -> layers
        "light" -> light
        "light_mode" -> lightMode
        "link" -> link
        "local_fire_department" -> localFireDepartment
        "more_vert" -> moreVert
        "notes" -> notes
        "open_in_full" -> openInFull
        "overview" -> overview
        "palette" -> palette
        "person" -> person
        "phone_android" -> phoneAndroid
        "play_arrow" -> playArrow
        "progress_activity" -> progressActivity
        "rate_review" -> rateReview
        "remove" -> remove
        "reviews" -> reviews
        "rocket_launch" -> rocketLaunch
        "schedule" -> schedule
        "school" -> school
        "search" -> search
        "search_off" -> searchOff
        "settings" -> settings
        "share" -> share
        "sort" -> sort
        "star" -> star
        "sticky_note_2" -> stickyNote2
        "storage" -> storage
        "swap_vert" -> swapVert
        "sync" -> sync
        "sync_alt" -> syncAlt
        "terminal" -> terminal
        "thumb_up" -> thumbUp
        "travel_explore" -> travelExplore
        "trending_up" -> trendingUp
        "tune" -> tune
        "update" -> update
        "upload" -> upload
        "verified" -> verified
        "visibility" -> visibility
        "warning" -> warning
        "workspace_premium" -> workspacePremium
        else -> null
    }
}
