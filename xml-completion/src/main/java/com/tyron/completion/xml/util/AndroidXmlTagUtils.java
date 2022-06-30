package com.tyron.completion.xml.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.insert.LayoutTagInsertHandler;

import org.apache.bcel.classfile.JavaClass;

import java.util.HashMap;
import java.util.Map;

public class AndroidXmlTagUtils {

    private static final Map<String, String> sManifestTagMappings = new HashMap<>();

    static {
        sManifestTagMappings.put("manifest", "AndroidManifest");
        sManifestTagMappings.put("application", "AndroidManifestApplication");
        sManifestTagMappings.put("permission", "AndroidManifestPermission");
        sManifestTagMappings.put("permission-group", "AndroidManifestPermissionGroup");
        sManifestTagMappings.put("permission-tree", "AndroidManifestPermissionTree");
        sManifestTagMappings.put("uses-permission", "AndroidManifestUsesPermission");
        sManifestTagMappings.put("required-feature", "AndroidManifestRequiredFeature");
        sManifestTagMappings.put("required-not-feature", "AndroidManifestRequiredNotFeature");
        sManifestTagMappings.put("uses-configuration", "AndroidManifestUsesConfiguration");
        sManifestTagMappings.put("uses-feature", "AndroidManifestUsesFeature");
        sManifestTagMappings.put("feature-group", "AndroidManifestFeatureGroup");
        sManifestTagMappings.put("uses-sdk", "AndroidManifestUsesSdk");
        sManifestTagMappings.put("extension-sdk", "AndroidManifestExtensionSdk");
        sManifestTagMappings.put("library", "AndroidManifestLibrary");
        sManifestTagMappings.put("static-library", "AndroidManifestStaticLibrary");
        sManifestTagMappings.put("uses-libraries", "AndroidManifestUsesLibrary");
        sManifestTagMappings.put("uses-native-library", "AndroidManifestUsesNativeLibrary");
        sManifestTagMappings.put("uses-static-library", "AndroidManifestUsesStaticLibrary");
        sManifestTagMappings.put("additional-certificate", "AndroidManifestAdditionalCertificate");
        sManifestTagMappings.put("uses-package", "AndroidManifestUsesPackage");
        sManifestTagMappings.put("supports-screens", "AndroidManifestSupportsScreens");
        sManifestTagMappings.put("processes", "AndroidManifestProcesses");
        sManifestTagMappings.put("process", "AndroidManifestProcess");
        sManifestTagMappings.put("deny-permission", "AndroidManifestDenyPermission");
        sManifestTagMappings.put("allow-permission", "AndroidManifestAllowPermission");
        sManifestTagMappings.put("provider", "AndroidManifestProvider");
        sManifestTagMappings.put("grant-uri-permission", "AndroidManifestGrantUriPermission");
        sManifestTagMappings.put("path-permission", "AndroidManifestPathPermission");
        sManifestTagMappings.put("service", "AndroidManifestService");
        sManifestTagMappings.put("receiver", "AndroidManifestReceiver");
        sManifestTagMappings.put("activity", "AndroidManifestActivity");
        sManifestTagMappings.put("activity-alias", "AndroidManifestActivityAlias");
        sManifestTagMappings.put("meta-data", "AndroidManifestMetaData");
        sManifestTagMappings.put("property", "AndroidManifestProperty");
        sManifestTagMappings.put("intent-filter", "AndroidManifestIntentFilter");
        sManifestTagMappings.put("action", "AndroidManifestAction");
        sManifestTagMappings.put("data", "AndroidManifestData");
        sManifestTagMappings.put("category", "AndroidManifestCategory");
        sManifestTagMappings.put("instrumentation", "AndroidManifestInstrumentation");
        sManifestTagMappings.put("screen", "AndroidManifestCompatibleScreensScreen");
        sManifestTagMappings.put("input-type", "AndroidManifestSupportsInputType");
        sManifestTagMappings.put("layout", "AndroidManifestLayout");
        sManifestTagMappings.put("restrict-update", "AndroidManifestRestrictUpdate");
        sManifestTagMappings.put("uses-split", "AndroidManifestUsesSplit");
    }

    @Nullable
    public static String getManifestStyleName(String tag) {
        return sManifestTagMappings.get(tag);
    }

    public static void addManifestTagItems(@NonNull XmlRepository repository,
                                           @NonNull String prefix,
                                           @NonNull CompletionList.Builder builder) {
        for (String tag : sManifestTagMappings.keySet()) {
            CompletionItem item = new CompletionItem();
            String commitPrefix = "<";
            if (prefix.startsWith("</")) {
                commitPrefix = "</";
            }
            item.label = tag;
            item.detail = "TAG";
            item.iconKind = DrawableKind.Class;
            item.commitText = commitPrefix + tag;
            item.cursorOffset = item.commitText.length();
            item.setInsertHandler(new LayoutTagInsertHandler(null, item));
            item.setSortText("");
            item.addFilterText(tag);
            item.addFilterText("<" + tag);
            item.addFilterText("</" + tag);
            builder.addItem(item);
        }
    }

    public static void addTagItems(@NonNull XmlRepository repository,
                                   @NonNull String prefix,
                                   @NonNull CompletionList.Builder builder) {
        for (Map.Entry<String, JavaClass> entry : repository.getJavaViewClasses()
                .entrySet()) {
            CompletionItem item = new CompletionItem();
            String commitPrefix = "<";
            if (prefix.startsWith("</")) {
                commitPrefix = "</";
            }
            boolean useFqn = prefix.contains(".");
            if (!entry.getKey()
                    .startsWith("android.widget")) {
                useFqn = true;
            }
            String simpleName = StyleUtils.getSimpleName(entry.getKey());
            item.label = simpleName;
            item.detail = entry.getValue()
                    .getPackageName();
            item.iconKind = DrawableKind.Class;
            item.commitText = commitPrefix +
                              (useFqn ? entry.getValue()
                                      .getClassName() : StyleUtils.getSimpleName(entry.getValue()
                                                                                         .getClassName()));
            item.cursorOffset = item.commitText.length();
            item.setInsertHandler(new LayoutTagInsertHandler(entry.getValue(), item));
            item.setSortText("");
            item.addFilterText(entry.getKey());
            item.addFilterText("<" + entry.getKey());
            item.addFilterText("</" + entry.getKey());
            item.addFilterText(simpleName);
            item.addFilterText("<" + simpleName);
            item.addFilterText("</" + simpleName);
            builder.addItem(item);
        }
    }
}
