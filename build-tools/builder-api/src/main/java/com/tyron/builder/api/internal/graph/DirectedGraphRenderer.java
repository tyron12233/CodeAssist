package com.tyron.builder.api.internal.graph;

import static com.tyron.builder.api.internal.graph.StyledTextOutput.Style.Info;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.logging.AbstractStyledTextOutput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DirectedGraphRenderer<N> {
    private final GraphNodeRenderer<N> nodeRenderer;
    private final DirectedGraph<N, ?> graph;
    private boolean omittedDetails;

    public DirectedGraphRenderer(GraphNodeRenderer<N> nodeRenderer, DirectedGraph<N, ?> graph) {
        this.nodeRenderer = nodeRenderer;
        this.graph = graph;
    }

    public void renderTo(N root, Appendable output) {
        renderTo(root, new GraphRenderer(new AbstractStyledTextOutput() {
            @Override
            protected void doAppend(String text) {
                try {
                    output.append(text);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }).getOutput());
    }

    public void renderTo(N root, StyledTextOutput output) {
        GraphRenderer renderer = new GraphRenderer(output);
        Set<N> rendered = new HashSet<N>();
        omittedDetails = false;
        renderTo(root, renderer, rendered, false);
        if (omittedDetails) {
            output.println();
            output.withStyle(Info).println("(*) - details omitted (listed previously)");
        }
    }

    private void renderTo(final N node, GraphRenderer graphRenderer, Collection<N> rendered, boolean lastChild) {
        final boolean alreadySeen = !rendered.add(node);

        graphRenderer.visit(output -> {
            nodeRenderer.renderTo(node, output);
            if (alreadySeen) {
                output.text(" (*)");
            }
        }, lastChild);

        if (alreadySeen) {
            omittedDetails = true;
            return;
        }

        List<N> children = new ArrayList<N>();
        graph.getNodeValues(node, new HashSet<Object>(), children);
        if (children.isEmpty()) {
            return;
        }
        graphRenderer.startChildren();
        for (int i = 0; i < children.size(); i++) {
            N child = children.get(i);
            renderTo(child, graphRenderer, rendered, i == children.size() - 1);
        }
        graphRenderer.completeChildren();
    }
}