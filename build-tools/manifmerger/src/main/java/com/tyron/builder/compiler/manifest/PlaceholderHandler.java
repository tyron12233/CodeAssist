package com.tyron.builder.compiler.manifest;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.blame.SourcePosition;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces all placeholders of the form ${name} with a tool invocation provided value
 */
public class PlaceholderHandler {

    // interesting placeholders names that are documented to be automatically provided.
    public static final String INSTRUMENTATION_RUNNER = "instrumentationRunner";
    public static final String PACKAGE_NAME = "packageName";
    public static final String APPLICATION_ID = "applicationId";

    // regular expression to recognize placeholders like ${name}, potentially surrounded by a
    // prefix and suffix string. this will split in 3 groups, the prefix, the placeholder name, and
    // the suffix.
    static final Pattern PATTERN = Pattern.compile("([^\\$]*)\\$\\{([^\\}]*)\\}(.*)");

    /**
     * Interface to provide a value for a placeholder key.
     * @param <T> the key type
     */
    public interface KeyBasedValueResolver<T> {

        /**
         * Returns a placeholder value for the placeholder key or null if none exists.
         */
        @Nullable
        String getValue(@NotNull T key);
    }

    /**
     * Returns true if the passed string is a placeholder value, false otherwise.
     */
    public static boolean isPlaceHolder(@NotNull String string) {
        return PATTERN.matcher(string).matches();
    }

    /**
     * Visits a document's entire tree and check each attribute for a placeholder existence.
     * If one is found, delegate to the provided {@link KeyBasedValueResolver} to provide a value
     * for the placeholder.
     * <p>
     * If no value is provided, an error will be generated.
     *
     * @param xmlDocument the xml document to visit
     * @param valueProvider the placeholder value provider.
     * @param mergingReportBuilder to report errors and log actions.
     */
    public void visit(
            @NotNull ManifestMerger2.MergeType mergeType,
            @NotNull XmlDocument xmlDocument,
            @NotNull KeyBasedValueResolver<String> valueProvider,
            @NotNull MergingReport.Builder mergingReportBuilder) {

        visit(mergeType, xmlDocument.getRootNode(), valueProvider, mergingReportBuilder);
    }

    private void visit(
            @NotNull ManifestMerger2.MergeType mergeType,
            @NotNull XmlElement xmlElement,
            @NotNull KeyBasedValueResolver<String> valueProvider,
            @NotNull MergingReport.Builder mergingReportBuilder) {

        for (XmlAttribute xmlAttribute : xmlElement.getAttributes()) {

            StringBuilder resultString = new StringBuilder();
            String inputString = xmlAttribute.getValue();
            Matcher matcher = PATTERN.matcher(inputString);
            if (matcher.matches()) {
                while (matcher.matches()) {
                    String placeholderValue = valueProvider.getValue(matcher.group(2));
                    // whatever precedes the placeholder key is added back to the string.
                    resultString.append(matcher.group(1));
                    if (placeholderValue == null) {
                        // if this is a library, ignore the failure
                        MergingReport.Record.Severity severity =
                                mergeType == ManifestMerger2.MergeType.LIBRARY
                                        ? MergingReport.Record.Severity.INFO
                                        : MergingReport.Record.Severity.ERROR;

                        xmlAttribute.addMessage(mergingReportBuilder, severity,
                                String.format(
                                        "Attribute %1$s at %2$s requires a placeholder substitution"
                                                + " but no value for <%3$s> is provided.",
                                        xmlAttribute.getId(),
                                        xmlAttribute.printPosition(),
                                        matcher.group(2)
                                ));
                        // we add back the placeholder key, since this is not an error for libraries
                        resultString.append("${");
                        resultString.append(matcher.group(2));
                        resultString.append("}");
                    } else {
                        // record the attribute set
                        mergingReportBuilder.getActionRecorder().recordAttributeAction(
                                xmlAttribute,
                                SourcePosition.UNKNOWN,
                                Actions.ActionType.INJECTED,
                                null /* attributeOperationType */);

                        // substitute the placeholder key with its value.
                        resultString.append(placeholderValue);
                    }
                    // the new input string is the tail of the previous match, as it may contain
                    // more placeholders to substitute.
                    inputString = matcher.group(3);
                    // reset the pattern matching with that new string to test for more placeholders
                    matcher = PATTERN.matcher(inputString);
                }
                // append the last remainder (without placeholders) in the result string.
                resultString.append(inputString);
                xmlAttribute.getXml().setValue(resultString.toString());
            }
        }
        for (XmlElement childElement : xmlElement.getMergeableElements()) {
            visit(mergeType, childElement, valueProvider, mergingReportBuilder);
        }
    }
}
