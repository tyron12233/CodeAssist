package com.tyron.builder.compiler.log;

import android.util.Pair;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class InjectLoggerTask extends Task {

    private static final String TAG = "InjectLogger";

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
        } catch (RuntimeException | XmlPullParserException e) {
            throw new CompilationFailedException(e);
        }
    }

    private String getApplicationClass() throws XmlPullParserException, IOException {
        File manifest = mProject.getManifestFile();
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
}
