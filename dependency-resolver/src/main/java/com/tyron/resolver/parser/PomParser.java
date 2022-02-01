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
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.util.ArrayList;

import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.model.Pom;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PomParser {

    private static final String ns = null;
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{(.*?)\\}");

    private final Map<String, String> mProperties;

    public PomParser() {
        mProperties = new HashMap<>();
    }

    public Pom parse(File in) throws IOException, XmlPullParserException {
        return parse(FileUtils.readFileToString(in, StandardCharsets.UTF_8));
    }

    public Pom parse(String in) throws IOException, XmlPullParserException {
        if (in == null) {
            return null;
        }

        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(in));
        parser.nextTag();
        return readProject(parser);
    }

    private Pom readProject(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, "project");

        Pom pom = new Pom();
        String packaging = "jar";
        List<Dependency> dependencies = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();
            if ("packaging".equals(name)) {
                packaging = readPackaging(parser);
            } else if ("properties".equals(name)) {
                mProperties.putAll(readProperties(parser));
            } else if ("dependencies".equals(name)) {
                dependencies.addAll(readDependencies(parser));
            } else if ("groupId".equals(name)) {
                pom.setGroupId(readDependencyGroupId(parser));
            } else if ("artifactId".equals(name)) {
                pom.setArtifactId(readArtifactId(parser));
            } else if ("version".equals(name)) {
                pom.setVersionName(readVersion(parser));
            } else {
                skip(parser);
            }
        }
        pom.setDependencies(dependencies);
        pom.setPackaging(packaging);
        return pom;
    }

    private String readPackaging(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, "packaging");
        String s = readText(parser);
        parser.require(XmlPullParser.END_TAG, ns, "packaging");
        return s;
    }

    private Map<String, String> readProperties(XmlPullParser parser) throws IOException, XmlPullParserException {
        Map<String, String> properties = new HashMap<>();

        parser.require(XmlPullParser.START_TAG, ns, "properties");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String key = parser.getName();
            String value = readText(parser);

            properties.put(key, value);
        }
        return properties;
    }

    private List<Dependency> readDependencies(XmlPullParser parser) throws IOException, XmlPullParserException {
        List<Dependency> dependencies = new ArrayList<>();

        parser.require(XmlPullParser.START_TAG, ns, "dependencies");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();

            if (name.equals("dependency")) {
                dependencies.add(readDependency(parser));
            } else {
                skip(parser);
            }
        }
        return dependencies;
    }

    private Dependency readDependency(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, "dependency");
        Dependency dependency = new Dependency();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();
            switch (name) {
                case "groupId":
                    dependency.setGroupId(readDependencyGroupId(parser));
                    break;
                case "artifactId":
                    dependency.setArtifactId(readArtifactId(parser));
                    break;
                case "version":
                    dependency.setVersionName(readVersion(parser));
                    break;
                case "scope":
                    dependency.setScope(readScope(parser));
                    break;
                case "type":
                    dependency.setType(readType(parser));
                    break;
                default:
                    skip(parser);
                    break;
            }
        }

        return dependency;
    }

    private String readDependencyGroupId(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, "groupId");
        String id = readText(parser);
        parser.require(XmlPullParser.END_TAG, ns, "groupId");
        return id;
    }

    private String readArtifactId(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, "artifactId");
        String id = readText(parser);
        parser.require(XmlPullParser.END_TAG, ns, "artifactId");
        return id;
    }

    private String readVersion(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, "version");
        String version = readText(parser);
        Matcher matcher = VARIABLE_PATTERN.matcher(version);
        if (matcher.matches()) {
            String name = matcher.group(1);
            String property = mProperties.get(name);
            if (property != null) {
                version = property;
            }
        }

        parser.require(XmlPullParser.END_TAG, ns, "version");
        return version;
    }

    private String readScope(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, "scope");
        String scope = readText(parser);
        parser.require(XmlPullParser.END_TAG, ns, "scope");
        return scope;
    }

    private String readType(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, "type");
        String type = readText(parser);
        parser.require(XmlPullParser.END_TAG, ns, "type");
        return type;
    }

    private String readText(XmlPullParser parser) throws IOException, XmlPullParserException {
        String result = "";
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.getText();
            parser.nextTag();
        }
        return result;
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }
}