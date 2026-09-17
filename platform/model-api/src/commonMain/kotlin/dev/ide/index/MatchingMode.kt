package dev.ide.index

/** Whether the engine builds a trigram index for an extension (controls fuzzy/substring matching). */
enum class MatchingMode { PREFIX_ONLY, PREFIX_AND_FUZZY }