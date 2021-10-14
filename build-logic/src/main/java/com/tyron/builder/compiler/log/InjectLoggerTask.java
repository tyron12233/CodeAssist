package com.tyron.builder.compiler.log;

import android.util.Pair;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;

import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class InjectLoggerTask extends Task {

    private static final String TAG = "InjectLogger";
    private static final String APPLICATION_CLASS = "\nimport android.app.Application;\n" +
            "public class LoggerApplication extends Application {\n" +
            "   public void onCreate() {\n" +
            "       super.onCreate();\n" +
            "   }\n" +
            "}";
    private static final String LOGGER_CLASS =

    private Project mProject;
    private ILogger mLogger;

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(Project project, ILogger logger, BuildType type) throws IOException {
        mProject = project;
        mLogger = logger;

    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        try {
            String applicationClass = getApplicationClass();
            if (applicationClass == null) {
                applicationClass = mProject.getPackageName() + ".LoggerApplication";
                createApplicationClass(applicationClass);
            }

            mLogger.debug("application class: " + applicationClass);
        } catch (RuntimeException | XmlPullParserException | ParserConfigurationException | SAXException | TransformerException e) {
            throw new CompilationFailedException(e);
        }
    }

    private String getApplicationClass() throws XmlPullParserException, IOException, ParserConfigurationException, SAXException, TransformerException, TransformerException {
        File manifest = new File(mProject.getBuildDirectory(), "bin/AndroidManifest.xml");
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new FileInputStream(manifest), null);

        final int depth = parser.getDepth();
        int type;
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if (parser.getName().equals("application")) {
                List<Pair<String, String>> attributes = new ArrayList<>();
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    attributes.add(Pair.create(parser.getAttributeName(i), parser.getAttributeValue(i)));
                }

                for (Pair<String, String> pair : attributes) {
                    if (pair.first.equals("android:name")) {
                        String name = pair.second;
                        if (name.startsWith(".")) {
                            return mProject.getPackageName() + name;
                        } else {
                            return name;
                        }
                    }
                }
            }
        }

        return null;
    }

    private void createApplicationClass(String name) throws IOException, ParserConfigurationException, TransformerException, SAXException {
        mLogger.debug("Creating application class " + name);

        File manifest = new File(mProject.getBuildDirectory(), "bin/AndroidManifest.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = factory.newDocumentBuilder();
        Document document = documentBuilder.parse(manifest);

        Element app = (Element) document.getElementsByTagName("application").item(0);
        app.setAttribute("android:name", name);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(document);
        transformer.transform(source, new StreamResult(manifest));

        File directory = mProject.getJavaDirectory();
        File output = new File(directory, name.replace('.', '/') + ".java");
        if (!output.exists() && !output.createNewFile()) {
            throw  new IOException("Unable to create LoggerApplication");
        }

        String classString = "package " + name + ";\n" +
                APPLICATION_CLASS;
        FileUtils.writeStringToFile(output, classString, Charset.defaultCharset());
    }

    private void injectLogger(File applicationClass) throws IOException, CompilationFailedException {
        String applicationContents = FileUtils.readFileToString(applicationClass, Charset.defaultCharset());
        String onCreateString = "super.onCreate();";
        int index = applicationContents.indexOf(onCreateString);
        if (index == -1) {
            throw new CompilationFailedException("No super method for Application.onCreate() found");
        }

        String before = applicationContents.substring(0, index + onCreateString.length());
        String after = applicationContents.substring(index + onCreateString.length());
    }
}