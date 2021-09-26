package com.tyron.kotlin_completion.completion;

import android.util.Log;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.kotlin_completion.CompiledFile;
import com.tyron.kotlin_completion.index.SymbolIndex;

import org.jetbrains.kotlin.builtins.KotlinBuiltIns;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.descriptors.ModuleDescriptor;
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtElement;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtQualifiedExpression;
import org.jetbrains.kotlin.psi.KtTypeElement;
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter;

import java.util.Arrays;

import kotlin.sequences.Sequence;
import kotlin.sequences.SequencesKt;
import kotlin.text.MatchResult;
import kotlin.text.Regex;
import kotlin.text.StringsKt;

public class Completions {

    public CompletionList completions(CompiledFile file, int cursor, SymbolIndex index) {
        String partial = findPartialIdentifier(file, cursor);
        return CompletionUtilsKt.completions(file, cursor, index, partial);
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
