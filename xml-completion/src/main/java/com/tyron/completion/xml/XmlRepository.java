package com.tyron.completion.xml;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ViewFlipper;
import android.widget.ViewSwitcher;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.util.Decompress;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.DeclareStyleable;
import com.tyron.completion.xml.model.Format;
import com.tyron.xml.completion.repository.ResourceRepository;
import com.tyron.completion.xml.util.StyleUtils;

import org.apache.bcel.Repository;
import org.apache.bcel.classfile.JavaClass;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class XmlRepository {
    private final Map<String, DeclareStyleable> mDeclareStyleables = new TreeMap<>();
    private final Map<String, DeclareStyleable> mManifestAttrs = new TreeMap<>();
    private final Map<String, AttributeInfo> mExtraAttributes = new TreeMap<>();
    private final Map<String, JavaClass> mJavaViewClasses = new TreeMap<>();

    private boolean mInitialized = false;
    private ResourceRepository mRepository;

    public XmlRepository() {

    }

    @Deprecated
    public Map<String, DeclareStyleable> getManifestAttrs() {
        return mManifestAttrs;
    }

    @Deprecated
    public Map<String, DeclareStyleable> getDeclareStyleables() {
        return mDeclareStyleables;
    }

    public Map<String, JavaClass> getJavaViewClasses() {
        return mJavaViewClasses;
    }

    @Deprecated
    public AttributeInfo getExtraAttribute(String name) {
        return mExtraAttributes.get(name);
    }

    public void initialize(AndroidModule module) throws IOException {
        if (mInitialized) {
            return;
        }
        BytecodeScanner.scanBootstrapIfNeeded();

        mRepository = new ResourceRepository(module);
        mRepository.initialize();

        for (File library : module.getLibraries()) {
            File parent = library.getParentFile();
            if (parent == null) {
                continue;
            }
            File classesFile = new File(parent, "classes.jar");
            if (classesFile.exists()) {
                try {
                    BytecodeScanner.loadJar(classesFile);
                } catch (IOException ignored) {

                }
            }
        }

        for (File library : module.getLibraries()) {
            try {
                List<JavaClass> scan =
                        BytecodeScanner.scan(library);
                for (JavaClass javaClass : scan) {
                    StyleUtils.putStyles(javaClass);
                    mJavaViewClasses.put(javaClass.getClassName(), javaClass);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        addFrameworkViews();

        Repository.clearCache();

        mInitialized = true;
    }

    private void addFrameworkViews() {
        addFrameworkView(View.class);
        addFrameworkView(ViewGroup.class);
        addFrameworkView(FrameLayout.class);
        addFrameworkView(RelativeLayout.class);
        addFrameworkView(LinearLayout.class);
        addFrameworkView(AbsoluteLayout.class);
        addFrameworkView(ListView.class);
        addFrameworkView(EditText.class);
        addFrameworkView(Button.class);
        addFrameworkView(TextView.class);
        addFrameworkView(ImageView.class);
        addFrameworkView(ImageButton.class);
        addFrameworkView(ImageSwitcher.class);
        addFrameworkView(ViewFlipper.class);
        addFrameworkView(ViewSwitcher.class);
        addFrameworkView(ScrollView.class);
        addFrameworkView(HorizontalScrollView.class);
        addFrameworkView(CompoundButton.class);
        addFrameworkView(ProgressBar.class);
        addFrameworkView(CheckBox.class);
    }

    private void addFrameworkView(Class<? extends View> viewClass) {
        org.apache.bcel.util.Repository repository = Repository.getRepository();
        try {
            JavaClass javaClass = repository.loadClass(viewClass);
            if (javaClass != null) {
                mJavaViewClasses.put(javaClass.getClassName(), javaClass);
            }
        } catch (ClassNotFoundException e) {
            // ignored
        }
    }

    private Map<String, DeclareStyleable> parse(Reader reader, String namespace) throws XmlPullParserException, IOException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();

        parser.setInput(reader);

        Map<String, DeclareStyleable> declareStyleables = new TreeMap<>();

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if ("declare-styleable".equals(name)) {
                DeclareStyleable declareStyleable = parseDeclareStyleable(parser, namespace);
                declareStyleables.put(declareStyleable.getName(), declareStyleable);
            } else if ("attr".equals(name)) {
                AttributeInfo attributeInfo = parseAttributeInfo(parser);
                if (!attributeInfo.getName().contains(":")) {
                    attributeInfo.setNamespace(namespace);
                } else {
                    String ns = attributeInfo.getName();
                    String newName = ns.substring(ns.indexOf(':') + 1);
                    ns = attributeInfo.getName().substring(0, ns.indexOf(':'));
                    attributeInfo.setName(newName);
                    attributeInfo.setNamespace(ns);
                }
                mExtraAttributes.put(attributeInfo.getName(), attributeInfo);
            }
        }

        return declareStyleables;
    }

    private Map<String, DeclareStyleable> parse(File file, String namespace) throws XmlPullParserException, IOException {
        try (FileReader reader = new FileReader(file)) {
            return parse(reader, namespace);
        }
    }

    private DeclareStyleable parseDeclareStyleable(XmlPullParser parser, String namespace) throws IOException,
            XmlPullParserException {

        String name = getAttributeValue(parser, "name", "");
        String parent = getAttributeValue(parser, "parent", "");

        Set<AttributeInfo> attributeInfos = new TreeSet<>();

        final int depth = parser.getDepth();
        int type;
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String tag = parser.getName();
            if ("attr".equals(tag)) {
                AttributeInfo attributeInfo = parseAttributeInfo(parser);
                if (!attributeInfo.getName().contains(":")) {
                    attributeInfo.setNamespace(namespace);
                } else {
                    String ns = attributeInfo.getName();
                    String newName = ns.substring(ns.indexOf(':') + 1);
                    ns = attributeInfo.getName().substring(0, ns.indexOf(':'));
                    attributeInfo.setName(newName);
                    attributeInfo.setNamespace(ns);
                }
                attributeInfos.add(attributeInfo);
            }
        }

        return new DeclareStyleable(name, attributeInfos, parent);
    }

    private AttributeInfo parseAttributeInfo(XmlPullParser parser) throws IOException,
            XmlPullParserException {
        String name = getAttributeValue(parser, "name", "");
        Set<Format> formats = new TreeSet<>();
        List<String> values = new ArrayList<>();

        String formatString = getAttributeValue(parser, "format", null);
        if (formatString != null) {
            formats.addAll(Format.fromString(formatString));
        }

        final int depth = parser.getDepth();
        int type;
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String tag = parser.getName();
            if ("enum".equals(tag)) {
                formats.add(Format.ENUM);
                String enumName = getAttributeValue(parser, "name", null);
                if (enumName != null) {
                    values.add(enumName);
                }
            } else if ("flag".equals(tag)) {
                formats.add(Format.FLAG);
                String flagName = getAttributeValue(parser, "name", null);
                if (flagName != null) {
                    values.add(flagName);
                }
            } else {
                skip(parser);
            }
        }

        if (formats.contains(Format.BOOLEAN)) {
            values.add("true");
            values.add("false");
        }
        return new AttributeInfo(name, formats, values);
    }

    public static String getAttributeValue(XmlPullParser parser, String name, String defaultValue) {
        int attributeCount = parser.getAttributeCount();
        for (int i = 0; i < attributeCount; i++) {
            String attributeName = parser.getAttributeName(i);
            String attributeValue = parser.getAttributeValue(i);

            if (name.equals(attributeName)) {
                return attributeValue;
            }
        }
        return defaultValue;
    }

    public static void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
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

    public ResourceRepository getRepository() {
        return mRepository;
    }

    public static XmlRepository getRepository(Project project, AndroidModule module) throws IOException {
        XmlIndexProvider indexProvider = CompilerService.getInstance()
                .getIndex(XmlIndexProvider.KEY);
        XmlRepository repository = indexProvider.get(project, module);
        repository.initialize(module);
        return repository;
    }
}
