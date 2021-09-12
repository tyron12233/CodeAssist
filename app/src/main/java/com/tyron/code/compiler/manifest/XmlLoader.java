package com.tyron.code.compiler.manifest;

import static com.tyron.code.compiler.manifest.ManifestMerger2.SystemProperty;
import static com.tyron.code.compiler.manifest.PlaceholderHandler.KeyBasedValueResolver;

import com.google.common.base.Optional;
import com.tyron.code.compiler.manifest.blame.SourceFile;
import com.tyron.code.util.PositionXmlParser;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.ParserConfigurationException;

/**
 * Responsible for loading XML files.
 */
public final class XmlLoader {

    private XmlLoader() {}

    /**
     * Loads an xml file without doing xml validation and return a {@link XmlDocument}
     *
     * @param displayName the xml file display name.
     * @param xmlFile the xml file.
     * @return the initialized {@link com.tyron.code.compiler.manifest.XmlDocument}
     */
    public static XmlDocument load(
            KeyResolver<String> selectors,
            KeyBasedValueResolver<SystemProperty> systemPropertyResolver,
            String displayName,
            File xmlFile,
            XmlDocument.Type type,
            Optional<String> mainManifestPackageName)
            throws IOException, SAXException, ParserConfigurationException {
        InputStream inputStream = new BufferedInputStream(new FileInputStream(xmlFile));

        Document domDocument = PositionXmlParser.parse(inputStream);
        return domDocument != null ? new XmlDocument(
                new SourceFile(xmlFile, displayName),
                selectors,
                systemPropertyResolver,
                domDocument.getDocumentElement(),
                type,
                mainManifestPackageName)
                : null;
    }


    /**
     * Loads a xml document from its {@link String} representation without doing xml validation and
     * return a {@link com.tyron.code.compiler.manifest.XmlDocument}
     * @param sourceFile the source location to use for logging and record collection.
     * @param xml the persisted xml.
     * @return the initialized {@link com.tyron.code.compiler.manifest.XmlDocument}
     * @throws IOException this should never be thrown.
     * @throws SAXException if the xml is incorrect
     * @throws ParserConfigurationException if the xml engine cannot be configured.
     */
    public static XmlDocument load(
            KeyResolver<String> selectors,
            KeyBasedValueResolver<SystemProperty> systemPropertyResolver,
            SourceFile sourceFile,
            String xml,
            XmlDocument.Type type,
            Optional<String> mainManifestPackageName)
            throws IOException, SAXException, ParserConfigurationException {
        Document domDocument = PositionXmlParser.parse(xml);
        return domDocument != null
                ? new XmlDocument(
                sourceFile,
                selectors,
                systemPropertyResolver,
                domDocument.getDocumentElement(),
                type,
                mainManifestPackageName)
                : null;
    }
}
