package com.tyron.builder.api.internal.catalog.problems;

import com.tyron.builder.internal.logging.text.TreeFormatter;
import com.tyron.builder.problems.BaseProblem;
import com.tyron.builder.problems.Solution;
import com.tyron.builder.problems.StandardSeverity;

import java.util.List;
import java.util.function.Supplier;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class VersionCatalogProblem extends BaseProblem<VersionCatalogProblemId, StandardSeverity, String> {
    VersionCatalogProblem(VersionCatalogProblemId versionCatalogProblemId,
                          StandardSeverity severity,
                          String context,
                          Supplier<String> shortDescription,
                          Supplier<String> longDescription,
                          Supplier<String> reason,
                          Supplier<String> docUrl,
                          List<Supplier<Solution>> solutions) {
        super(versionCatalogProblemId, severity, context, shortDescription, longDescription, reason, docUrl, solutions);
    }

    public void reportInto(TreeFormatter output) {
        TreeFormatter formatter = new TreeFormatter();
        formatter.node("Problem: In " + uncapitalize(getWhere()) + ", " + maybeAppendDot(uncapitalize(getShortDescription())));
        getWhy().ifPresent(reason -> {
            formatter.blankLine();
            formatter.node("Reason: " + capitalize(maybeAppendDot(reason)));
        });
        List<Solution> possibleSolutions = getPossibleSolutions();
        int solutionCount = possibleSolutions.size();
        if (solutionCount > 0) {
            formatter.blankLine();
            if (solutionCount == 1) {
                formatter.node("Possible solution: " + capitalize(maybeAppendDot(possibleSolutions.get(0).getShortDescription())));
            } else {
                formatter.node("Possible solutions");
                formatter.startNumberedChildren();
                possibleSolutions.forEach(solution ->
                    formatter.node(capitalize(maybeAppendDot(solution.getShortDescription())))
                );
                formatter.endChildren();
            }
        }
        getDocumentationLink().ifPresent(docLink -> {
            formatter.blankLine();
            formatter.node("Please refer to ").append(docLink).append(" for more details about this problem.");
        });
        output.node(formatter.toString());
    }

    private static String maybeAppendDot(String txt) {
        if (txt.endsWith(".") || txt.endsWith("\n")) {
            return txt;
        }
        return txt + ".";
    }
}
