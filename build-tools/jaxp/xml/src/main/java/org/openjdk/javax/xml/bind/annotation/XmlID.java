package org.openjdk.javax.xml.bind.annotation;

import java.lang.annotation.Target;
import java.lang.annotation.Retention;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.*;

/**
 * <p>
 * Maps a JavaBean property to XML ID.
 *
 * <p>
 * To preserve referential integrity of an object graph across XML
 * serialization followed by a XML deserialization, requires an object
 * reference to be marshalled by reference or containment
 * appropriately. Annotations {@code @XmlID} and {@code @XmlIDREF}
 * together allow a customized mapping of a JavaBean property's
 * type by containment or reference. 
 *
 * <p><b>Usage</b> </p>
 * The {@code @XmlID} annotation can be used with the following
 * program elements: 
 * <ul> 
 *   <li> a JavaBean property </li>
 *   <li> non static, non transient field </li>
 * </ul>
 * 
 * <p>See "Package Specification" in javax.xml.bind.package javadoc for
 * additional common information.</p>
 *
 * The usage is subject to the following constraints:
 * <ul> 
 *   <li> At most one field or property in a class can be annotated
 *        with {@code @XmlID}.  </li>
 *   <li> The JavaBean property's type must be {@code java.lang.String}.</li>
 *   <li> The only other mapping annotations that can be used
 *        with {@code @XmlID}
 *        are: {@code @XmlElement} and {@code @XmlAttribute}.</li>
 * </ul>
 * 
 * <p><b>Example</b>: Map a JavaBean property's type to {@code xs:ID}</p>
 * <pre>
 *    // Example: code fragment
 *    public class Customer {
 *        &#64;XmlAttribute
 *        &#64;XmlID
 *        public String getCustomerID();
 *        public void setCustomerID(String id);
 *        .... other properties not shown 
 *    }
 * {@code
 * 
 *    <!-- Example: XML Schema fragment -->
 *    <xs:complexType name="Customer">
 *      <xs:complexContent>
 *        <xs:sequence>
 *          ....
 *        </xs:sequence>
 *        <xs:attribute name="customerID" type="xs:ID"/>
 *      </xs:complexContent>
 *    </xs:complexType>
 * }</pre>
 *
 * @author Sekhar Vajjhala, Sun Microsystems, Inc.
 * @see XmlIDREF
 * @since 1.6, JAXB 2.0
 */

@Retention(RUNTIME) @Target({FIELD, METHOD})
public @interface XmlID { }
