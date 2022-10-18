package org.gradle.plugins.ide.idea.model;

import org.gradle.internal.xml.XmlTransformer;
import org.gradle.plugins.ide.api.XmlFileContentMerger;

import java.io.File;

/**
 * Models the generation/parsing/merging capabilities of an IDEA module.
 * <p>
 * For examples, see docs for {@link IdeaModule}.
 */
public class IdeaModuleIml extends XmlFileContentMerger {

    private File generateTo;

    public IdeaModuleIml(XmlTransformer xmlTransformer, File generateTo) {
        super(xmlTransformer);
        this.generateTo = generateTo;
    }

    /**
     * Folder where the *.iml file will be generated to
     * <p>
     * For example see docs for {@link IdeaModule}
     */
    public File getGenerateTo() {
        return generateTo;
    }

    public void setGenerateTo(File generateTo) {
        this.generateTo = generateTo;
    }
}