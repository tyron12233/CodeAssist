package org.gradle.plugins.ide.idea;

import org.gradle.api.tasks.Internal;
import org.gradle.internal.xml.XmlTransformer;
import org.gradle.plugins.ide.api.XmlGeneratorTask;
import org.gradle.plugins.ide.idea.model.IdeaModule;
import org.gradle.plugins.ide.idea.model.Module;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;

/**
 * Generates an IDEA module file. If you want to fine tune the idea configuration
 * <p>
 * Please refer to interesting examples on idea configuration in {@link IdeaModule}.
 * <p>
 * At this moment nearly all configuration is done via {@link IdeaModule}.
 */
@DisableCachingByDefault(because = "Not made cacheable, yet")
public class GenerateIdeaModule extends XmlGeneratorTask<Module> {

    private IdeaModule module;

    public GenerateIdeaModule() {}

    @Inject
    public GenerateIdeaModule(IdeaModule module) {
        this.module = module;
    }

    @Override
    protected Module create() {
        return new Module(getXmlTransformer(), module.getPathFactory());
    }

    @Override
    protected void configure(Module xmlModule) {
        getModule().mergeXmlModule(xmlModule);
    }

    @Override
    public XmlTransformer getXmlTransformer() {
        if (module == null) {
            return super.getXmlTransformer();
        }
        return module.getIml().getXmlTransformer();
    }

    /**
     * Configures output *.iml file. It's <b>optional</b> because the task should configure it correctly for you (including making sure it is unique in the multi-module build). If you really need to
     * change the output file name it is much easier to do it via the <b>idea.module.name</b> property. <p> Please refer to documentation in {@link IdeaModule} <b>name</b> property. In IntelliJ IDEA
     * the module name is the same as the name of the *.iml file.
     */
    @Override
    public File getOutputFile() {
        if (module == null) {
            return super.getOutputFile();
        }
        return module.getOutputFile();
    }

    @Override
    public void setOutputFile(File newOutputFile) {
        module.setOutputFile(newOutputFile);
    }

    /**
     * The Idea module model containing the details required to generate the module file.
     */
    @Internal
    public IdeaModule getModule() {
        return module;
    }

    public void setModule(IdeaModule module) {
        this.module = module;
    }

}
