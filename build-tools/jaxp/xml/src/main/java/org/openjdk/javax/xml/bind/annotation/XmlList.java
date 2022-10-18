package org.openjdk.javax.xml.bind.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;

/**
 * Used to map a property to a list simple type.
 *
 * <p><b>Usage</b> </p>
 * <p>
 * The {@code @XmlList} annotation can be used with the
 * following program elements: 
 * <ul> 
 *   <li> JavaBean property </li>
 *   <li> field </li>
 * </ul>
 *
 * <p>
 * When a collection property is annotated just with @XmlElement,
 * each item in the collection will be wrapped by an element.
 * For example,
 *
 * <pre>
 * &#64;XmlRootElement
 * class Foo {
 *     &#64;XmlElement
 *     List&lt;String&gt; data;
 * }
 * </pre>
 *
 * would produce XML like this:
 *
 * <pre>{@code
 * <foo>
 *   <data>abc</data>
 *   <data>def</data>
 * </foo>
 * }</pre>
 *
 * &#64;XmlList annotation, on the other hand, allows multiple values to be 
 * represented as whitespace-separated tokens in a single element. For example,
 *
 * <pre>
 * &#64;XmlRootElement
 * class Foo {
 *     &#64;XmlElement
 *     &#64;XmlList
 *     List&lt;String&gt; data;
 * }
 * </pre>
 *
 * the above code will produce XML like this:
 *
 * <pre>{@code
 * <foo>
 *   <data>abc def</data>
 * </foo>
 * }</pre>
 *
 * <p>This annotation can be used with the following annotations:
 *        {@link XmlElement}, 
 *        {@link XmlAttribute},
 *        {@link XmlValue},
 *        {@link XmlIDREF}.
 *  <ul>
 *    <li> The use of {@code @XmlList} with {@link XmlValue} while
 *         allowed, is redundant since  {@link XmlList} maps a
 *         collection type to a simple schema type that derives by
 *         list just as {@link XmlValue} would. </li> 
 *
 *    <li> The use of {@code @XmlList} with {@link XmlAttribute} while
 *         allowed, is redundant since  {@link XmlList} maps a
 *         collection type to a simple schema type that derives by
 *         list just as {@link XmlAttribute} would. </li> 
 *  </ul>
 *
 * @author <ul><li>Kohsuke Kawaguchi, Sun Microsystems, Inc.</li><li>Sekhar Vajjhala, Sun Microsystems, Inc.</li></ul>
 * @since 1.6, JAXB 2.0
 */
@Retention(RUNTIME) @Target({FIELD,METHOD,PARAMETER})
public @interface XmlList {
}