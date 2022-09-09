package org.openjdk.javax.xml.bind;

/**
 * This is an element marker interface.
 *
 * Under certain circumstances, it is necessary for the binding compiler to 
 * generate derived java content classes that implement this interface.  In 
 * those cases, client applications must supply element instances rather than 
 * types of elements.  For more detail, see section 5.7 "Element Declaration" 
 * and 5.7.1 "Bind to Java Element Interface" of the specification.
 *
 * @author <ul><li>Ryan Shoemaker, Sun Microsystems, Inc.</li><li>Kohsuke Kawaguchi, Sun Microsystems, Inc.</li><li>Joe Fialli, Sun Microsystems, Inc.</li></ul> 
 * @since 1.6, JAXB 1.0
 */

public interface Element {
}