package org.gradle.plugins.ide.idea.model;

import groovy.util.Node;
import org.gradle.internal.xml.XmlTransformer;
import org.gradle.plugins.ide.internal.generator.XmlPersistableConfigurationObject;

/**
 * Represents the customizable elements of an ipr (via XML hooks everything of the ipr is customizable).
 */
public class Workspace extends XmlPersistableConfigurationObject {
    public Workspace(XmlTransformer withXmlActions) {
        super(withXmlActions);
    }

    @Override
    protected String getDefaultResourceName() {
        return "defaultWorkspace.xml";
    }

    @Override
    protected void load(Node xml) {
    }

    @Override
    protected void store(Node xml) {
    }
}
