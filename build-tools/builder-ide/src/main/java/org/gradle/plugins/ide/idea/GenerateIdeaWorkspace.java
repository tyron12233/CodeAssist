package org.gradle.plugins.ide.idea;

import org.gradle.api.tasks.Internal;
import org.gradle.internal.xml.XmlTransformer;
import org.gradle.plugins.ide.api.XmlGeneratorTask;
import org.gradle.plugins.ide.idea.model.IdeaWorkspace;
import org.gradle.plugins.ide.idea.model.Workspace;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;

/**
 * Generates an IDEA workspace file *only* for root project. There's little you can configure about workspace generation at the moment.
 */
@DisableCachingByDefault(because = "Not made cacheable, yet")
public class GenerateIdeaWorkspace extends XmlGeneratorTask<Workspace> {

    private IdeaWorkspace workspace;

    public GenerateIdeaWorkspace() {}

    @Inject
    public GenerateIdeaWorkspace(IdeaWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    protected Workspace create() {
        return new Workspace(getXmlTransformer());
    }

    @Override
    protected void configure(Workspace xmlWorkspace) {
        getWorkspace().mergeXmlWorkspace(xmlWorkspace);
    }

    @Override
    public XmlTransformer getXmlTransformer() {
        if (workspace == null) {
            return super.getXmlTransformer();
        }
        return workspace.getIws().getXmlTransformer();
    }

    /**
     * The Idea workspace model containing the details required to generate the workspace file.
     */
    @Internal
    public IdeaWorkspace getWorkspace() {
        return workspace;
    }

    public void setWorkspace(IdeaWorkspace workspace) {
        this.workspace = workspace;
    }

}
