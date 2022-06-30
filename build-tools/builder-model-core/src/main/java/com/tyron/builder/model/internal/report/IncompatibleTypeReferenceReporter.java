package com.tyron.builder.model.internal.report;

import javax.annotation.concurrent.ThreadSafe;
import com.tyron.builder.model.internal.core.ModelPath;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

import java.io.PrintWriter;
import java.io.StringWriter;

@ThreadSafe
public class IncompatibleTypeReferenceReporter {

    private final static String INDENT = "  ";

    private final String creator;
    private final String path;
    private final String type;
    private final String description;
    private final boolean writable;
    private final Iterable<String> candidateTypes;

    public IncompatibleTypeReferenceReporter(String creator, String path, String type, String description, boolean writable, Iterable<String> candidateTypes) {
        this.creator = creator;
        this.path = path;
        this.type = type;
        this.description = description;
        this.writable = writable;
        this.candidateTypes = candidateTypes;
    }

    public static IncompatibleTypeReferenceReporter of(MutableModelNode node, ModelType<?> type, String description, boolean writable) {
        ModelPath path = node.getPath();
        ModelRuleDescriptor creatorDescriptor = node.getDescriptor();
        return new IncompatibleTypeReferenceReporter(
            creatorDescriptor.toString(), path.toString(), type.toString(), description, writable,
            node.getTypeDescriptions()
        );
    }

    public String asString() {
        StringWriter string = new StringWriter();
        writeTo(new PrintWriter(string));
        return string.toString();
    }

    public void writeTo(PrintWriter writer) {
        //"type-only model reference of type '%s'%s is ambiguous as multiple model elements are available for this type:%n  %s (created by %s)%n  %s (created by %s)",
        writer.print("Model reference to element '");
        writer.print(path);
        writer.print("' with type ");
        writer.print(type);
        if (description != null) {
            writer.print(" (");
            writer.print(description);
            writer.print(")");
        }
        writer.println(" is invalid due to incompatible types.");
        writer.print("This element was created by ");
        writer.print(creator);
        writer.print(" and can be ");
        writer.print(writable ? "mutated" : "read");
        writer.println(" as the following types:");
        boolean first = true;
        for (String candidateType : candidateTypes) {
            if (!first) {
                writer.println();
            }
            writer.print(INDENT);
            writer.print("- ");
            writer.print(candidateType);
            first = false;
        }
    }
}
