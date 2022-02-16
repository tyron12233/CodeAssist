package com.tyron.builder.compiler.manifest;

import static com.tyron.builder.compiler.manifest.ManifestModel.NodeTypes;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.tyron.builder.compiler.manifest.blame.SourceFile;
import com.tyron.builder.compiler.manifest.blame.SourcePosition;
import com.tyron.builder.util.XmlUtils;

import org.w3c.dom.Element;

/**
 * An xml element that does not belong to a {@link }
 */
public class OrphanXmlElement extends XmlNode {

    @NonNull
    private final Element mXml;

    @NonNull
    private final NodeTypes mType;

    public OrphanXmlElement(@NonNull Element xml) {

        mXml = Preconditions.checkNotNull(xml);
        NodeTypes nodeType;
        String elementName = mXml.getNodeName();
        // this is bit more complicated than it should be. Look first if there is a namespace
        // prefix in the name, most elements don't. If they do, however, strip it off if it is the
        // android prefix, but if it's custom namespace prefix, classify the node as CUSTOM.
        int indexOfColon = elementName.indexOf(':');
        if (indexOfColon != -1) {
            String androidPrefix = XmlUtils.lookupNamespacePrefix(xml, SdkConstants.ANDROID_URI);
            if (androidPrefix.equals(elementName.substring(0, indexOfColon))) {
                nodeType = NodeTypes.fromXmlSimpleName(elementName.substring(indexOfColon + 1));
            } else {
                nodeType = NodeTypes.CUSTOM;
            }
        } else {
            nodeType = NodeTypes.fromXmlSimpleName(elementName);
        }
        mType = nodeType;
    }

    /**
     * Returns true if this xml element's {@link NodeTypes} is
     * the passed one.
     */
    public boolean isA(NodeTypes type) {
        return this.mType == type;
    }

    @NonNull
    @Override
    public Element getXml() {
        return mXml;
    }


    @Override
    public NodeKey getId() {
        return new NodeKey(Strings.isNullOrEmpty(getKey())
                ? getName().toString()
                : getName().toString() + "#" + getKey());
    }

    @Override
    public NodeName getName() {
        return unwrapName(mXml);
    }

    /**
     * Returns this xml element {@link NodeTypes}
     */
    @NonNull
    public NodeTypes getType() {
        return mType;
    }

    /**
     * Returns the unique key for this xml element within the xml file or null if there can be only
     * one element of this type.
     */
    @Nullable
    public String getKey() {
        return mType.getNodeKeyResolver().getKey(mXml);
    }

    @NonNull
    @Override
    public SourcePosition getPosition() {
        return SourcePosition.UNKNOWN;
    }

    @Override
    @NonNull
    public SourceFile getSourceFile() {
        return SourceFile.UNKNOWN;
    }
}
