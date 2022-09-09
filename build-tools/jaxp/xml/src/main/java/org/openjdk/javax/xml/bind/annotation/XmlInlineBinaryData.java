package org.openjdk.javax.xml.bind.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import org.openjdk.javax.xml.transform.Source;

/**
 * Disable consideration of XOP encoding for datatypes that are bound to 
 * base64-encoded binary data in XML.
 *
 * <p>
 * When XOP encoding is enabled as described in {@link AttachmentMarshaller#isXOPPackage()},
 * this annotation disables datatypes such as {@link java.awt.Image} or {@link Source}
 * or {@code byte[]} that are bound to base64-encoded binary from being considered for
 * XOP encoding. If a JAXB property is annotated with this annotation or if
 * the JAXB property's base type is annotated with this annotation, 
 * neither 
 * {@link AttachmentMarshaller#addMtomAttachment(DataHandler, String, String)}
 * nor 
 * {@link AttachmentMarshaller#addMtomAttachment(byte[], int, int, String, String, String)} is 
 * ever called for the property. The binary data will always be inlined.
 *
 * @author Joseph Fialli
 * @since 1.6, JAXB 2.0
 */
@Retention(RUNTIME)
@Target({FIELD,METHOD,TYPE})
public @interface XmlInlineBinaryData {
}