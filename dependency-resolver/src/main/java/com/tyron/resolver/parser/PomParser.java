package com.tyron.resolver.parser;

import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.io.InputStream;
import java.io.IOException;

import android.util.Xml;

import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.util.ArrayList;

import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.repository.Repository;
import com.tyron.resolver.repository.RepositoryManager;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class PomParser {

    private static final String ns = null;
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{(.*?)\\}");

    private Pom parent;
    private final Map<String, String> mProperties;
    private final RepositoryManager repository;

    public PomParser(RepositoryManager repository) {
        this.repository = repository;
        mProperties = new HashMap<>();
    }

    public Pom parse(File in) throws IOException, XmlPullParserException, SAXException {
        return parse(FileUtils.readFileToString(in, StandardCharsets.UTF_8));
    }

    public Pom parse(String in) throws IOException, XmlPullParserException, SAXException {
        if (in == null) {
            return null;
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder;
        try {
            documentBuilder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new Error(e);
        }
        InputSource source = new InputSource(new StringReader(in));
        Document document = documentBuilder.parse(source);
        Element documentElement = document.getDocumentElement();
        if (!"project".equals(documentElement.getTagName())) {
            return null;
        }

        return parseProject(documentElement);
    }

    // new implementation

    private Pom parseProject(Element projectElement) {
        NodeList properties = projectElement.getElementsByTagName("properties");
        if (properties.getLength() > 0) {
            mProperties.putAll(parseProperties((Element) properties.item(0)));
        }

        Pom pom = new Pom();
        NodeList childNodes = projectElement.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node child = childNodes.item(i);

            String nodeName = child.getNodeName();
            if ("dependencies".equals(nodeName)) {
                pom.setDependencies(parseDependencies((Element) child));
            } else if ("dependencyManagement".equals(nodeName)) {
                List<Dependency> dependencies = parseDependencies((Element) child);
                pom.setManagedDependencies(dependencies);
            } else if ("parent".equals(nodeName)) {
                parent = parseParent((Element) child);
                pom.setParent(parent);
            } else if ("groupId".equals(nodeName)) {
                pom.setGroupId(getTextContent(child));
            } else if ("artifactId".equals(nodeName)) {
                pom.setArtifactId(getTextContent(child));
            } else if ("version".equals(nodeName)) {
                pom.setVersionName(getTextContent(child));
            } else if ("packaging".equals(nodeName)) {
                pom.setPackaging(getTextContent(child));
            }
        }
        return pom;
    }

    private String getTextContent(Node child) {
        String value = child.getTextContent();
        Matcher matcher = VARIABLE_PATTERN.matcher(value);
        if (matcher.matches()) {
            String name = matcher.group(1);
            String property = mProperties.get(name);
            if (property != null) {
                value = property;
            }
        }
        return value;
    }

    private Pom parseParent(Element element) {
        Dependency dependency = new Dependency();
        NodeList groupIdList = element.getElementsByTagName("groupId");
        if (groupIdList.getLength() < 1) {
            return null;
        }
        dependency.setGroupId(groupIdList.item(0).getTextContent());

        NodeList artifactIdList = element.getElementsByTagName("artifactId");
        if (artifactIdList.getLength() < 1) {
            return null;
        }
        dependency.setArtifactId(artifactIdList.item(0).getTextContent());

        NodeList versionList = element.getElementsByTagName("version");
        if (versionList.getLength() < 1) {
            return null;
        }
        String version = versionList.item(0).getTextContent();
        Matcher matcher = VARIABLE_PATTERN.matcher(version);
        if (matcher.matches()) {
            String name = matcher.group(1);
            String property = mProperties.get(name);
            if (parent != null && property == null) {
                property = parent.getProperty(name);
            }
            if (property != null) {
                version = property;
            }
        }
        dependency.setVersionName(version);
        return repository.getPom(dependency.toString());
    }

    private Map<String, String> parseProperties(Element propertyElement) {
        Map<String, String> properties = new HashMap<>();
        NodeList propertyTags = propertyElement.getChildNodes();
        for (int i = 0; i < propertyTags.getLength(); i++) {
            if (propertyTags.item(i).getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element property  = (Element) propertyTags.item(i);
            String key = property.getTagName();
            String value = property.getTextContent();
            properties.put(key, value);
        }
        return properties;
    }

    private List<Dependency> parseDependencies(Element dependenciesNode) {
        List<Dependency> dependencies = new ArrayList<>();
        NodeList dependencyList = dependenciesNode.getElementsByTagName("dependency");
        for (int i = 0; i < dependencyList.getLength(); i++) {
            Element dependencyElement = (Element) dependencyList.item(i);

            Dependency dependency = new Dependency();
            NodeList groupIdList = dependencyElement.getElementsByTagName("groupId");
            if (groupIdList.getLength() < 1) {
                continue;
            }
            dependency.setGroupId(groupIdList.item(0).getTextContent());

            NodeList artifactIdList = dependencyElement.getElementsByTagName("artifactId");
            if (artifactIdList.getLength() < 1) {
                continue;
            }
            dependency.setArtifactId(artifactIdList.item(0).getTextContent());

            NodeList scopeList = dependencyElement.getElementsByTagName("scope");
            if (scopeList.getLength() > 0) {
                dependency.setScope(getTextContent(scopeList.item(0)));
            }

            NodeList versionList = dependencyElement.getElementsByTagName("version");
            if (versionList.getLength() < 1) {
                Pom current = parent;
                boolean found = false;
                outer: while (current != null) {
                    List<Dependency> managedDependencies = current.getManagedDependencies();
                    for (Dependency managedDependency : managedDependencies) {
                        if (!managedDependency.getGroupId().equals(dependency.getGroupId())) {
                            continue;
                        }
                        if (!managedDependency.getArtifactId().equals(dependency.getArtifactId())) {
                            continue;
                        }

                        dependency.setVersionName(managedDependency.getVersionName());
                        dependency.setScope(managedDependency.getScope());
                        found = true;
                        break outer;
                    }
                    current = current.getParent();
                }

                if (!found) {
                    continue;
                }
            } else {
                dependency.setVersionName(getTextContent(versionList.item(0)));
            }

            NodeList exclusion = dependencyElement.getElementsByTagName("exclusions");
            if (exclusion.getLength() > 0) {
                Element exclusionsElement = (Element) exclusion.item(0);
                NodeList exclusionElementList = exclusionsElement.getElementsByTagName("exclusion");

                for (int j = 0; j < exclusionElementList.getLength(); j++) {
                    Element exclusionElement = (Element) exclusionElementList.item(j);

                    Dependency exclusionDependency = new Dependency();

                    NodeList groupId = exclusionElement.getElementsByTagName("groupId");
                    if (groupId.getLength() < 1) {
                        continue;
                    }
                    exclusionDependency.setGroupId(getTextContent(groupId.item(0)));

                    NodeList artifactId = exclusionElement.getElementsByTagName("artifactId");
                    if (artifactId.getLength() < 1) {
                        continue;
                    }
                    exclusionDependency.setArtifactId(getTextContent(artifactId.item(0)));

                    dependency.addExclude(exclusionDependency);
                }
            }

            dependencies.add(dependency);
        }
        return dependencies;
    }
}