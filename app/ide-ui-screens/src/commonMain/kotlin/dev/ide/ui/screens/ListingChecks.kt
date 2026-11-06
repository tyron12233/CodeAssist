package dev.ide.ui.screens

/**
 * The listing checks a moderator's review runs, run on the publisher's side before anything is sent.
 *
 * These are the same rules as `runChecks` in `supabase/admin/index.html` (its "listing" block), ported so
 * the two agree: a publisher should be told in the form what a moderator would otherwise tell them in a
 * rejection days later. They are warnings, not gates. The language one in particular is a guess, and a
 * guess that blocked sending would refuse an English listing that happens to name a Spanish city.
 */
internal enum class ListingIssue {
    /** The listing reads as another language or script. The store asks for English. */
    NotEnglish,

    /** The summary is a few characters long, or just repeats the title. */
    WeakSummary,

    /** "Download free", "mod apk" and the like: the wording a moderator rejects as spam. */
    Spammy,
}

internal fun listingIssues(title: String, summary: String, description: String): List<ListingIssue> = buildList {
    val words = "$title $summary $description"
    if (looksNonEnglish(words)) add(ListingIssue.NotEnglish)
    if (isWeakSummary(title, summary)) add(ListingIssue.WeakSummary)
    if (SPAM.containsMatchIn(words)) add(ListingIssue.Spammy)
}

/** A summary under 12 characters, or the title again. Blank is "required", which the form says itself. */
internal fun isWeakSummary(title: String, summary: String): Boolean {
    val s = summary.trim()
    if (s.isEmpty()) return false
    return s.length < 12 || s.equals(title.trim(), ignoreCase = true)
}

/**
 * Whether [text] reads as something other than English: mostly a non-Latin script, or Latin script with
 * enough of another language's commonest words in it. Short text is never judged, since twelve letters
 * of a project name say nothing about the language around it.
 */
internal fun looksNonEnglish(text: String): Boolean {
    val letters = text.filter { it.isLetter() }
    if (letters.length < 12) return false
    val nonLatin = letters.count { !isLatinLetter(it) }.toDouble() / letters.length
    if (nonLatin > 0.3) return true
    val words = lettersRuns(text.lowercase())
    if (words.size < 6) return false
    return words.count { it in NON_ENGLISH_WORDS }.toDouble() / words.size > 0.12
}

/** A-Z, a-z and U+00C0..U+024F (accented Latin), the range the admin page counts as Latin. */
private fun isLatinLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '\u00C0'..'\u024F'

/** Maximal runs of letters: the words, without relying on regex Unicode classes that differ by platform. */
private fun lettersRuns(text: String): List<String> {
    val out = mutableListOf<String>()
    val run = StringBuilder()
    for (c in text) {
        if (c.isLetter()) run.append(c) else if (run.isNotEmpty()) { out += run.toString(); run.clear() }
    }
    if (run.isNotEmpty()) out += run.toString()
    return out
}

private val SPAM = Regex(
    """(free\s+download|download\s+free|download\s+now|100%\s*free|mod\s*apk|hack(?:ed)?\s+version|unlimited\s+(?:coins|money))""",
    RegexOption.IGNORE_CASE,
)

/** Function words that are common in Spanish, Portuguese, Indonesian, French, German and Turkish, and rare in English. */
private val NON_ENGLISH_WORDS = (
    "de la el los las para con una que tus por del como más muy " +
        "um uma não você para com " + "dan yang untuk dengan ini itu dari " + "les des est pour avec une dans sur " +
        "und der die das ist mit für " + "bir ve ile için " +
        // Added after the first list let a real Portuguese listing through at 6%. Left out on purpose:
        // words English uses too ("no", "a") or that are tech names ("os" as in Android OS, "ada").
        "em sem seu sua até mas é ao pra nesse esse essa isso também vc " + "y en es su sus pero este esta al " + "et du au ce qui pas " + "ein eine nicht auch ich sie " + "ya tidak akan bisa juga"
    ).split(' ').toSet()
