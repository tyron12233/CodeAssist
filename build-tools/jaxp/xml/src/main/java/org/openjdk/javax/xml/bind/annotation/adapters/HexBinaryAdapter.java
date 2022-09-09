package org.openjdk.javax.xml.bind.annotation.adapters;

import org.openjdk.javax.xml.bind.DatatypeConverter;

/**
 * {@link XmlAdapter} for {@code xs:hexBinary}.
 *
 * <p>
 * This {@link XmlAdapter} binds {@code byte[]} to the hexBinary representation in XML.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.6, JAXB 2.0
 */
public final class HexBinaryAdapter extends XmlAdapter<String,byte[]> {
    public byte[] unmarshal(String s) {
        if (s == null) {
            return null;
        }
        return DatatypeConverter.parseHexBinary(s);
    }

    public String marshal(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return DatatypeConverter.printHexBinary(bytes);
    }
}