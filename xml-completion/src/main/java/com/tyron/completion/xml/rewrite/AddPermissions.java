package com.tyron.completion.xml.rewrite;

import static com.tyron.completion.xml.util.XmlUtils.newPullParser;

import com.tyron.builder.util.CharSequenceReader;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.model.Position;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class AddPermissions implements JavaRewrite {
    private static final String PERMISSION_TEMPLATE = "<uses-permission android:name=\"%s\" />";

    private final Path androidManifest;
    private final CharSequence androidManifestContents;
    private final List<String> permissions;

    public AddPermissions(Path androidManifest, CharSequence androidManifestContents,
                          List<String> permissions) {
        this.androidManifest = androidManifest;
        this.androidManifestContents = androidManifestContents;
        this.permissions = permissions;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        Range manifestClosingTag = findManifestClosingTag();

        String permissionsStr = permissions.stream()
                .map(s -> "\t" + String.format(PERMISSION_TEMPLATE, s))
                .collect(Collectors.joining("\n"));

        TextEdit textEdit = new TextEdit(manifestClosingTag, "\n" + permissionsStr);

        return Collections.singletonMap(androidManifest, new TextEdit[]{textEdit});
    }

    private Range findManifestClosingTag() {
        try {
            XmlPullParser xpp = newPullParser();
            xpp.setInput(new CharSequenceReader(androidManifestContents));

            int event = xpp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if ("manifest".equals(xpp.getName()) && xpp.getDepth() == 1) {
                        Position position = new Position(xpp.getLineNumber() - 1,
                                xpp.getColumnNumber() - 1);
                        return new Range(position, position);
                    }
                }
                event = xpp.next();
            }
        } catch (IOException | XmlPullParserException ignored) {
        }
        return Range.NONE;
    }

    public static Set<String> getUsedPermissions(CharSequence androidManifestContents) {
        Set<String> usedPerms = new HashSet<>();

        try {
            XmlPullParser xpp = newPullParser();
            xpp.setInput(new CharSequenceReader(androidManifestContents));

            int event = xpp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if ("uses-permission".equals(xpp.getName())) {
                        String name = xpp.getAttributeValue(null, "android:name");
                        if (name != null) {
                            usedPerms.add(name);
                        }
                    }
                }
                event = xpp.next();
            }
        } catch (IOException | XmlPullParserException ignored) {
        }

        return usedPerms;
    }

}
