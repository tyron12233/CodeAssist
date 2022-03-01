package com.tyron.builder.compiler.log;

import android.util.Log;
import android.util.Pair;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.common.util.StringSearch;

import org.apache.commons.io.FileUtils;
import org.openjdk.javax.xml.parsers.DocumentBuilder;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;
import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.openjdk.javax.xml.transform.Transformer;
import org.openjdk.javax.xml.transform.TransformerException;
import org.openjdk.javax.xml.transform.TransformerFactory;
import org.openjdk.javax.xml.transform.dom.DOMSource;
import org.openjdk.javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class InjectLoggerTask extends Task<AndroidModule> {

    private static final String TAG = "InjectLogger";
    private static final String APPLICATION_CLASS = "\nimport android.app.Application;\n" +
            "public class LoggerApplication extends Application {\n" +
            "   public void onCreate() {\n" +
            "       super.onCreate();\n" +
            "   }\n" +
            "}";
    private static final String LOGGER_CLASS = "import android.content.Context;\n" +
            "import android.content.Intent;\n" +
            "\n" +
            "import java.io.BufferedReader;\n" +
            "import java.io.IOException;\n" +
            "import java.io.InputStreamReader;\n" +
            "import java.util.concurrent.Executors;\n" +
            "import java.util.regex.Matcher;\n" +
            "import java.util.regex.Pattern;\n" +
            "\n" +
            "public class Logger {\n" +
            "\n" +
            "    private static final String DEBUG = \"DEBUG\";\n" +
            "    private static final String WARNING = \"WARNING\";\n" +
            "    private static final String ERROR = \"ERROR\";\n" +
            "    private static final String INFO = \"INFO\";\n" +
            "    private static final Pattern TYPE_PATTERN = Pattern.compile(\"^(.*\\\\d) ([ADEIW]) (.*): (.*)\");\n" +
            "\n" +
            "    private static volatile boolean mInitialized;\n" +
            "    private static Context mContext;\n" +
            "\n" +
            "    public static void initialize(Context context) {\n" +
            "        if (mInitialized) {\n" +
            "            return;\n" +
            "        }\n" +
            "        mInitialized = true;\n" +
            "        mContext = context.getApplicationContext();\n" +
            "\n" +
            "        start();\n" +
            "    }\n" +
            "\n" +
            "    private static void start() {\n" +
            "        Executors.newSingleThreadExecutor().execute(() -> {\n" +
            "            try {\n" +
            "                clear();\n" +
            "                Process process = Runtime.getRuntime()\n" +
            "                        .exec(\"logcat\");\n" +
            "                BufferedReader reader = new BufferedReader(new InputStreamReader(\n" +
            "                        process.getInputStream()));\n" +
            "                String line = null;\n" +
            "                while ((line = reader.readLine()) != null) {\n" +
            "                    Matcher matcher = TYPE_PATTERN.matcher(line);\n" +
            "                    if (matcher.matches()) {\n" +
            "                        String type = matcher.group(2);\n" +
            "                        if (type != null) {\n" +
            "                           switch (type) {\n" +
            "                               case \"D\": debug(line);   break;\n" +
            "                               case \"E\": error(line);   break;\n" +
            "                               case \"W\": warning(line); break;\n" +
            "                               case \"I\": info(line);    break;\n" +
            "                           }\n" +
            "                        } else {\n" +
            "                            debug(line);\n" +
            "                        }\n" +
            "                    }\n" +
            "                }\n" +
            "            } catch (IOException e) {\n" +
            "                error(\"IOException occurred on Logger: \" + e.getMessage());\n" +
            "            }\n" +
            "        });\n" +
            "    }\n" +
            "\n" +
            "    private static void clear() throws IOException {\n" +
            "        Runtime.getRuntime().exec(\"logcat -c\");\n" +
            "    }\n" +
            "\n" +
            "    private static void debug(String message) {\n" +
            "        broadcast(DEBUG, message);\n" +
            "    }\n" +
            "\n" +
            "    private static void warning(String message) {\n" +
            "        broadcast(WARNING, message);\n" +
            "    }\n" +
            "\n" +
            "    private static void error(String message) {\n" +
            "        broadcast(ERROR, message);\n" +
            "    }\n" +
            "\n" +
            "    private static void info(String message) {\n" +
            "        broadcast(INFO, message);\n" +
            "    }\n" +
            "\n" +
            "    private static void broadcast(String type, String message) {\n" +
            "        Intent intent = new Intent(mContext.getPackageName() + \".LOG\");\n" +
            "        intent.putExtra(\"type\", type);\n" +
            "        intent.putExtra(\"message\", message);\n" +
            "        mContext.sendBroadcast(intent);\n" +
            "    }\n" +
            "}\n";
    private File mLoggerFile;
    private File mApplicationFile;
    private String mOriginalApplication;

    public InjectLoggerTask(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {
        getModule().getJavaFiles();
        getModule().getKotlinFiles();
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        try {
            addLoggerClass();

            boolean isNewApplicationClass = true;

            String applicationClass = getApplicationClass();
            if (applicationClass == null) {
                applicationClass = getModule().getPackageName() + ".LoggerApplication";
                createApplicationClass(applicationClass);
            } else {
                isNewApplicationClass = false;
            }

            mApplicationFile = getModule()
                    .getJavaFile(applicationClass);
            if (mApplicationFile == null) {
                mApplicationFile = getModule()
                        .getKotlinFile(applicationClass);
            }

            if (mApplicationFile == null) {
                String message = "" +
                        "Unable to find the application class defined in manifest.\n" +
                        "fully qualified name: " + applicationClass + '\n' +
                        "This build will not have logger injected.";
                getLogger().warning(message);
                return;
            }

            if (!isNewApplicationClass) {
                mOriginalApplication = FileUtils.readFileToString(mApplicationFile, Charset.defaultCharset());
            }

            injectLogger(mApplicationFile);

            getLogger().debug("application class: " + applicationClass);
        } catch (RuntimeException | XmlPullParserException | ParserConfigurationException | SAXException | TransformerException e) {
            throw new CompilationFailedException(Log.getStackTraceString(e));
        }
    }

    @Override
    protected void clean() {
        if (mApplicationFile != null) {
            if (mOriginalApplication != null) {
                try {
                    FileUtils.writeStringToFile(mApplicationFile, mOriginalApplication, Charset.defaultCharset());
                } catch (IOException ignore) {
                }
            } else {
                try {
                    getModule().removeJavaFile(StringSearch.packageName(mApplicationFile));
                    FileUtils.delete(mApplicationFile);
                } catch (IOException e) {
                    getLogger().error("Failed to delete application class: " + e.getMessage());
                }
            }
        }

        if (mLoggerFile != null) {
            try {
                getModule().removeJavaFile(StringSearch.packageName(mLoggerFile));
                FileUtils.delete(mLoggerFile);
            } catch (IOException e) {
                getLogger().error("Failed to delete logger class: " + e.getMessage());
            }
        }
    }

    private String getApplicationClass() throws XmlPullParserException, IOException, ParserConfigurationException, SAXException, TransformerException {
        File manifest = new File(getModule().getBuildDirectory().getAbsolutePath().replaceAll("%20", " "), "bin/AndroidManifest.xml");
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
                            return getModule().getPackageName() + name;
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
        getLogger().debug("Creating application class " + name);

        File manifest = new File(getModule().getBuildDirectory().getAbsolutePath().replaceAll("%20", " "), "bin/AndroidManifest.xml");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = factory.newDocumentBuilder();
        Document document = documentBuilder.parse(manifest);

        Element app = (Element) document.getElementsByTagName("application").item(0);
        app.setAttribute("android:name", name);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(document);
        transformer.transform(source, new StreamResult(manifest.getAbsolutePath()));

        File directory = getModule().getJavaDirectory();
        File output = new File(directory, name.replace('.', '/') + ".java");
        if (!output.exists() && !output.createNewFile()) {
            throw  new IOException("Unable to create LoggerApplication");
        }

        String classString = "package " + getModule().getPackageName() + ";\n" +
                APPLICATION_CLASS;
        FileUtils.writeStringToFile(output, classString, Charset.defaultCharset());
        getModule()
                .addJavaFile(output);
    }

    private void injectLogger(File applicationClass) throws IOException, CompilationFailedException {
        String applicationContents = FileUtils.readFileToString(applicationClass, Charset.defaultCharset());
        if (applicationContents.contains("Logger.initialize(this);")) {
            getLogger().debug("Application class already initializes Logger");
            return;
        }

        String onCreateString = "super.onCreate()";
        int index = applicationContents.indexOf(onCreateString);
        if (index == -1) {
            throw new CompilationFailedException("No super method for Application.onCreate() found");
        }

        String before = applicationContents.substring(0, index + onCreateString.length() + 1);
        String after = applicationContents.substring(index + onCreateString.length());
        String injected = before + "\n" + "Logger.initialize(this);\n" +
                after;
        FileUtils.writeStringToFile(applicationClass, injected, Charset.defaultCharset());
    }

    private void addLoggerClass() throws IOException {
        getLogger().debug("Creating Logger.java");

        File loggerClass = new File(getModule().getJavaDirectory(), getModule().getPackageName()
        .replace('.', '/') + "/Logger.java");
        if (!loggerClass.exists() && !loggerClass.createNewFile()) {
            throw new IOException("Unable to create Logger.java");
        }

        String loggerString = "package " + getModule().getPackageName() + ";\n" +
                LOGGER_CLASS;
        FileUtils.writeStringToFile(loggerClass, loggerString, Charset.defaultCharset());
        mLoggerFile = loggerClass;
        getModule().addJavaFile(loggerClass);
    }
}