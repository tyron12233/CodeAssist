package com.tyron.resolver.parser;

import java.util.List;
import java.io.InputStream;
import java.io.IOException;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.util.ArrayList;
import com.tyron.resolver.model.Dependency;
import java.util.Collections;

public class POMParser {

    private static final String ns = null;

    public List<Dependency> parse(InputStream in) throws IOException, XmlPullParserException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null);
            parser.nextTag();
            return readProject(parser);
        } finally {
            in.close();
        }
    }

    private List<Dependency> readProject(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, "project");

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();

            if (name.equals("dependencies")) {
                return readDependencies(parser);
            } else {
                skip(parser);
            }
        }

        return Collections.emptyList();
    }

    private List<Dependency> readDependencies(XmlPullParser parser) throws IOException, XmlPullParserException {
        List<Dependency> dependencies = new ArrayList<>();

        parser.require(XmlPullParser.START_TAG, ns, "dependencies");
        while(parser.next() !=  XmlPullParser.END_TAG) {
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
        while(parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();
            if (name.equals("groupId")) {
                dependency.setGroupId(readDependencyGroupId(parser));
            } else if (name.equals("artifactId")) {
                dependency.setArtifactId(readArtifactId(parser));
            } else if (name.equals("version")) {
                dependency.setVersion(readVersion(parser));
            } else if (name.equalsIgnoreCase("scope")) {
                dependency.setScope(readScope(parser));
            } else if (name.equals("type")) {
                dependency.setType(readType(parser));
            } else {
                skip(parser);
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