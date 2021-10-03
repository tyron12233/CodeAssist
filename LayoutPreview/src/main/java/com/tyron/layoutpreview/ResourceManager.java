package com.tyron.layoutpreview;

import static com.tyron.layoutpreview.util.XmlUtils.advanceToRootNode;
import static com.tyron.layoutpreview.util.XmlUtils.readText;
import static com.tyron.layoutpreview.util.XmlUtils.skip;

import android.util.AttributeSet;
import android.util.Pair;
import android.util.Xml;

import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Value;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.resource.ResourceStringParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ResourceManager {

    private final File mResourceDir;

    public ResourceManager(File resourceDir) {
        mResourceDir = resourceDir;
    }


    public Map<String, Map<String, Value>> getStrings() {
        ResourceStringParser parser = new ResourceStringParser(mResourceDir);
        return parser.getStrings();
    }

}
