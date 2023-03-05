package org.openjdk.javax.xml.bind.annotation;

import org.openjdk.javax.xml.bind.JAXBContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import java.lang.annotation.Target;

/**
 * Instructs JAXB to also bind other classes when binding this class.
 *
 * <p>
 * Java makes it impractical/impossible to list all sub-classes of
 * a given class. This often gets in a way of JAXB users, as it JAXB
 * cannot automatically list up the classes that need to be known
 * to {@link JAXBContext}.
 *
 * <p>
 * For example, with the following class definitions:
 *
 * <pre>
 * class Animal {}
 * class Dog extends Animal {}
 * class Cat extends Animal {}
 * </pre>
 *
 * <p>
 * The user would be required to create {@link JAXBContext} as
 * {@code JAXBContext.newInstance(Dog.class,Cat.class)}
 * ({@code Animal} will be automatically picked up since {@code Dog}
 * and {@code Cat} refers to it.)
 *
 * <p>
 * {@link XmlSeeAlso} annotation would allow you to write:
 * <pre>
 * &#64;XmlSeeAlso({Dog.class,Cat.class})
 * class Animal {}
 * class Dog extends Animal {}
 * class Cat extends Animal {}
 * </pre>
 *
 * <p>
 * This would allow you to do {@code JAXBContext.newInstance(Animal.class)}.
 * By the help of this annotation, JAXB implementations will be able to
 * correctly bind {@code Dog} and {@code Cat}.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.6, JAXB 2.1
 */
@Target({ElementType.TYPE})
@Retention(RUNTIME)
public @interface XmlSeeAlso {
    Class[] value();
}