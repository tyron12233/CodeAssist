package org.gradle.plugins.ide.idea.model;

import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import org.gradle.api.Action;
import org.gradle.plugins.ide.api.XmlFileContentMerger;

import static org.gradle.util.internal.ConfigureUtil.configure;

/**
 * Enables fine-tuning workspace details (*.iws file) of the IDEA plugin.
 * <p>
 * At the moment, the only practical way of manipulating the resulting content is via the withXml hook:
 *
 * <pre class='autoTested'>
 * plugins {
 *     id 'java'
 *     id 'idea'
 * }
 *
 * idea.workspace.iws.withXml { provider -&gt;
 *     provider.asNode().appendNode('gradleRocks', 'true')
 * }
 * </pre>
 */
public class IdeaWorkspace {

    private XmlFileContentMerger iws;

    /**
     * Enables advanced manipulation of the output XML.
     * <p>
     * For example see docs for {@link IdeaWorkspace}
     */
    public XmlFileContentMerger getIws() {
        return iws;
    }

    public void setIws(XmlFileContentMerger iws) {
        this.iws = iws;
    }

    /**
     * Enables advanced manipulation of the output XML.
     * <p>
     * For example see docs for {@link IdeaWorkspace}
     */
    public void iws(@DelegatesTo(XmlFileContentMerger.class) Closure closure) {
        configure(closure, iws);
    }

    /**
     * Enables advanced manipulation of the output XML.
     * <p>
     * For example see docs for {@link IdeaWorkspace}
     *
     * @since 3.5
     */
    public void iws(Action<? super XmlFileContentMerger> action) {
        action.execute(iws);
    }

    @SuppressWarnings("unchecked")
    public void mergeXmlWorkspace(Workspace xmlWorkspace) {
        iws.getBeforeMerged().execute(xmlWorkspace);

        //we don't merge anything in the iws, yet.
        //I kept the logic for the sake of consistency
        // and compatibility with pre M4 ways of configuring IDEA information.

        iws.getWhenMerged().execute(xmlWorkspace);
    }
}
