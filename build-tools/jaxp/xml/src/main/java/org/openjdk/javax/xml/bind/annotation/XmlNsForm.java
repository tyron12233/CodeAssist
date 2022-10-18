package org.openjdk.javax.xml.bind.annotation;

/**
 * Enumeration of XML Schema namespace qualifications. 
 *
 * <p>See "Package Specification" in javax.xml.bind.package javadoc for
 * additional common information.</p>
 *
 * <p><b>Usage</b>  
 * <p>
 * The namespace qualification values are used in the annotations
 * defined in this packge. The enumeration values are mapped as follows:
 *
 * <table class="striped">
 *   <caption style="display:none">Mapping of enumeration values</caption>
 *   <thead>
 *     <tr>
 *       <th scope="col">Enum Value</th>
 *       <th scope="col">XML Schema Value</th>
 *     </tr>
 *   </thead>
 * 
 *   <tbody>
 *     <tr>
 *       <th scope="row">UNQUALIFIED</th>
 *       <td>unqualified</td>
 *     </tr>
 *     <tr>
 *       <th scope="row">QUALIFIED</th>
 *       <td>qualified</td>
 *     </tr>
 *     <tr>
 *       <th scope="row">UNSET</th>
 *       <td>namespace qualification attribute is absent from the
 *           XML Schema fragment</td>
 *     </tr>
 *   </tbody>
 * </table>
 * 
 * @author Sekhar Vajjhala, Sun Microsystems, Inc.
 * @since 1.6, JAXB 2.0
 */
public enum XmlNsForm {UNQUALIFIED, QUALIFIED, UNSET}
