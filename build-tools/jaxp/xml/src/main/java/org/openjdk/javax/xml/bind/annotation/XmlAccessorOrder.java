package org.openjdk.javax.xml.bind.annotation;

import java.lang.annotation.Target;
import java.lang.annotation.Retention;
import java.lang.annotation.Inherited;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.*;

/**
 * <p> Controls the ordering of fields and properties in a class. </p>
 *
 * <h3>Usage </h3>
 *
 * <p> {@code @XmlAccessorOrder} annotation can be used with the following
 * program elements:</p> 
 * 
 * <ul> 
 *   <li> package</li>
 *   <li> a top level class </li>
 * </ul>
 *
 * <p> See "Package Specification" in {@code javax.xml.bind} package javadoc for
 * additional common information.</p>
 *
 * <p>The effective {@link XmlAccessOrder} on a class is determined
 * as follows:
 *
 * <ul>
 *   <li> If there is a {@code @XmlAccessorOrder} on a class, then
 *        it is used. </li>
 *   <li> Otherwise, if a {@code @XmlAccessorOrder} exists on one of
 *        its super classes, then it is inherited (by the virtue of
 *        {@link Inherited})
 *   <li> Otherwise, the {@code @XmlAccessorOrder} on the package
 *        of the class is used, if it's there.
 *   <li> Otherwise {@link XmlAccessOrder#UNDEFINED}.
 * </ul>
 *
 * <p>This annotation can be used with the following annotations:
 *    {@link XmlType}, {@link XmlRootElement}, {@link XmlAccessorType}, 
 *    {@link XmlSchema}, {@link XmlSchemaType}, {@link XmlSchemaTypes}, 
 *    , {@link XmlJavaTypeAdapter}. It can also be used with the
 *    following annotations at the package level: {@link XmlJavaTypeAdapter}.
 *
 * @author Sekhar Vajjhala, Sun Microsystems, Inc.
 * @since 1.6, JAXB 2.0
 * @see XmlAccessOrder
 */

@Inherited @Retention(RUNTIME) @Target({PACKAGE, TYPE})
public @interface XmlAccessorOrder {
	XmlAccessOrder value() default XmlAccessOrder.UNDEFINED;
}