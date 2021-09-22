package com.tyron.kotlin_completion.util

/**
 * Computes a string distance using a slightly modified
 * variant of the SIFT4 algorithm in linear time.
 * Note that the function is asymmetric with respect to
 * its two input strings and thus is not a metric in the
 * mathematical sense.
 *
 * Based on the JavaScript implementation from
 * https://siderite.dev/blog/super-fast-and-accurate-string-distance.html/
 *
 * @param candidate The first string
 * @param pattern The second string
 * @param maxOffset The number of characters to search for matching letters
 */
fun stringDistance(candidate: CharSequence, pattern: CharSequence, maxOffset: Int = 4): Int = when {
    candidate.length == 0 -> pattern.length
    pattern.length == 0 -> candidate.length
    else -> {
        val candidateLength = candidate.length
        val patternLength = pattern.length
        var iCandidate = 0
        var iPattern = 0
        var longestCommonSubsequence = 0
        var localCommonSubstring = 0

        while (iCandidate < candidateLength && iPattern < patternLength) {
            if (candidate[iCandidate] == pattern[iPattern]) {
                localCommonSubstring++
            } else {
                longestCommonSubsequence += localCommonSubstring
                localCommonSubstring = 0

                if (iCandidate != iPattern) {
                    // Using max to bypass the need for computer transpositions ("ab" vs "ba")
                    val iMax = Math.max(iCandidate, iPattern)
                    iCandidate = iMax
                    iPattern = iMax
                    if (iMax >= Math.min(candidateLength, patternLength)) {
                        break
                    }
                }

                searchWindow@
                for (i in 0 until maxOffset) {
                    when {
                        (iCandidate + i) < candidateLength -> {
                            if (candidate[iCandidate + i] == pattern[iPattern]) {
                                iCandidate += i
                                localCommonSubstring++
                                break@searchWindow
                            }
                        }
                        (iPattern + i) < patternLength -> {
                            if (candidate[iCandidate] == pattern[iPattern + i]) {
                                iPattern += i
                                localCommonSubstring++
                                break@searchWindow
                            }
                        }
                        else -> break@searchWindow
                    }
                }
            }

            iCandidate++
            iPattern++
        }

        longestCommonSubsequence += localCommonSubstring
        Math.max(candidateLength, patternLength) - longestCommonSubsequence
    }
}

/** Checks whether the candidate contains the pattern in order. */
fun containsCharactersInOrder(candidate: CharSequence, pattern: CharSequence, caseSensitive: Boolean): Boolean {
    var iCandidate = 0
    var iPattern = 0

    while (iCandidate < candidate.length && iPattern < pattern.length) {
        var patternChar = pattern[iPattern]
        var testChar = candidate[iCandidate]

        if (!caseSensitive) {
            patternChar = Character.toLowerCase(patternChar)
            testChar = Character.toLowerCase(testChar)
        }

        if (patternChar == testChar) {
            iPattern++
        }
        iCandidate++
    }

    return iPattern == pattern.length
}