package org.gradle.plugins.ide.api;

import org.gradle.api.tasks.Internal;
import org.gradle.internal.xml.XmlTransformer;
import org.gradle.plugins.ide.internal.generator.generator.PersistableConfigurationObject;
import org.gradle.plugins.ide.internal.generator.generator.PersistableConfigurationObjectGenerator;
import org.gradle.work.DisableCachingByDefault;

/**
 * A convenience superclass for those tasks which generate XML configuration files from a domain object of type T.
 *
 * @param <T> The domain object type.
 */
@DisableCachingByDefault(because = "Abstract super-class, not to be instantiated directly")
public abstract class XmlGeneratorTask<T extends PersistableConfigurationObject> extends GeneratorTask<T> {
    private final XmlTransformer xmlTransformer = new XmlTransformer();

    public XmlGeneratorTask() {
        generator = new PersistableConfigurationObjectGenerator<T>() {
            @Override
            public T create() {
                return XmlGeneratorTask.this.create();
            }

            @Override
            public void configure(T object) {
                XmlGeneratorTask.this.configure(object);
            }
        };
    }

    @Internal
    public XmlTransformer getXmlTransformer() {
        return xmlTransformer;
    }

    protected abstract void configure(T object);

    protected abstract T create();

}