package com.tyron.kotlin_completion.completion;

import android.util.Log;

import com.tyron.completion.model.CompletionList;
import com.tyron.kotlin_completion.CompiledFile;
import com.tyron.kotlin_completion.index.SymbolIndex;

import java.time.Duration;
import java.time.Instant;

import kotlin.sequences.SequencesKt;
import kotlin.text.MatchResult;
import kotlin.text.Regex;
import kotlin.text.StringsKt;

public class Completions {

    public CompletionList completions(CompiledFile file, int cursor, SymbolIndex index) {

        String partial = findPartialIdentifier(file, cursor);
        Instant start = Instant.now();
        CompletionList completions = CompletionUtilsKt.completions(file, cursor, index, partial);

        Log.d("Completions", "Completions took " + Duration.between(start, Instant.now()).toMillis() + " ms");
        return completions;
    }

    private String findPartialIdentifier(CompiledFile file, int cursor) {
        String line = file.lineBefore(cursor);
        if (line.matches(String.valueOf(new Regex(".*\\.")))) {
            return "";
        }
        if (line.matches(String.valueOf(new Regex(".*\\.\\w+")))) {
            return StringsKt.substringAfterLast(line, ".", ".");
        }

        MatchResult matchResult = SequencesKt.lastOrNull(new Regex("\\w+").findAll(line, 0));
        if (matchResult == null) {
            return "";
        }
        return matchResult.getValue();
    }

}
