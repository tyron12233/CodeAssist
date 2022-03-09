package com.tyron.code.language.textmate;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.langs.textmate.folding.FoldingRegions;
import io.github.rosemoe.sora.langs.textmate.folding.IndentRange;
import io.github.rosemoe.sora.langs.textmate.folding.PreviousRegion;
import io.github.rosemoe.sora.langs.textmate.folding.RangesCollector;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.textmate.core.internal.oniguruma.OnigRegExp;
import io.github.rosemoe.sora.textmate.core.internal.oniguruma.OnigResult;
import io.github.rosemoe.sora.textmate.core.internal.oniguruma.OnigString;
import io.github.rosemoe.sora.textmate.languageconfiguration.internal.supports.Folding;

public class CodeBlockUtils {

    @SuppressWarnings("rawtype")
    public static FoldingRegions computeRanges(Content model, int tabSize , boolean offSide, Folding markers, int foldingRangesLimit, BaseIncrementalAnalyzeManager.CodeBlockAnalyzeDelegate delegate) throws Exception {

        RangesCollector result = new RangesCollector(foldingRangesLimit, tabSize);

        OnigRegExp pattern = null;
        if (markers != null) {
            pattern = new OnigRegExp("(" + markers.getMarkersStart() + ")|(?:" + markers.getMarkersEnd() + ")");
        }

        List<PreviousRegion> previousRegions = new ArrayList<>();
        int line = model.getLineCount() + 1;
        // sentinel, to make sure there's at least one entry
        previousRegions.add(new PreviousRegion(-1, line, line));

        for (line = model.getLineCount() - 1; line >= 0 && !delegate.isCancelled(); line--) {
            String lineContent = model.getLineString(line);
            int indent = IndentRange
                    .computeIndentLevel(model.getLine(line).getRawData(), model.getColumnCount(line), tabSize);
            PreviousRegion previous = previousRegions.get(previousRegions.size() - 1);
            if (indent == -1) {
                if (offSide) {
                    // for offSide languages, empty lines are associated to the previous block
                    // note: the next block is already written to the results, so this only
                    // impacts the end position of the block before
                    previous.endAbove = line;
                }
                continue; // only whitespace
            }
            OnigResult m;
            if (pattern != null && (m = pattern.search(new OnigString(lineContent), 0)) != null) {
                // folding pattern match
                if (m.count() >= 2) { // start pattern match
                    // discard all regions until the folding pattern
                    int i = previousRegions.size() - 1;
                    while (i > 0 && previousRegions.get(i).indent != -2) {
                        i--;
                    }
                    if (i > 0) {
                        //??? previousRegions.length = i + 1;
                        previous = previousRegions.get(i);

                        // new folding range from pattern, includes the end line
                        result.insertFirst(line, previous.line, indent);
                        previous.line = line;
                        previous.indent = indent;
                        previous.endAbove = line;
                        continue;
                    } else {
                        // no end marker found, treat line as a regular line
                    }
                } else { // end pattern match
                    previousRegions.add(new PreviousRegion(-2, line, line));
                    continue;
                }
            }
            if (previous.indent > indent) {
                // discard all regions with larger indent
                do {
                    previousRegions.remove(previousRegions.size() - 1);
                    previous = previousRegions.get(previousRegions.size() - 1);
                } while (previous.indent > indent);

                // new folding range
                int endLineNumber = previous.endAbove - 1;
                if (endLineNumber - line >= 1) { // needs at east size 1
                    result.insertFirst(line, endLineNumber, indent);
                }
            }
            if (previous.indent == indent) {
                previous.endAbove = line;
            } else { // previous.indent < indent
                // new region with a bigger indent
                previousRegions.add(new PreviousRegion(indent, line, line));
            }
        }
        return result.toIndentRanges(model);
    }
}
