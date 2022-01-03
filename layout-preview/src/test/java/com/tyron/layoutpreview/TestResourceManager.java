package com.tyron.layoutpreview;

import static com.google.common.truth.Truth.assertThat;

import com.flipkart.android.proteus.value.Value;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.layoutpreview.resource.ResourceStringParser;
import com.tyron.layoutpreview.resource.ResourceValueParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TestResourceManager {

    private static final String TEST_XML = "" +
            "<resources>\n" +
            "\n" +
            "    <string name=\"app_name\">CodeAssist</string>\n" +
            "    <string name=\"hello_world\">Hello world!</string>\n" +
            "    <item name=\"paste\" type=\"string\">Paste</item>\n" +
            "    <item name=\"copy\" type=\"string\">Copy</item>\n" +
            "    <item name=\"selectAll\" formatted=\"true\" type=\"string\">Select All</item>\n" +
            "    <item name=\"saveToProject\" translatable=\"false\" type=\"string\">Project</item>\n" +
            "    <item name=\"cut\" type=\"string\">Cut</item>\n" +
            "    <item name=\"last\" type=\"string\">Last</item>\n" +
            "    <item name=\"next\" type=\"string\">Next</item>\n" +
            "    <item name=\"replace\" type=\"string\">Replace</item>\n" +
            "    <item name=\"replaceAll\" type=\"string\">Replace All</item>\n" +
            "    <item name=\"replacement\" type=\"string\">Replacement</item>\n" +
            "    <item name=\"cancel\" type=\"string\">Cancel</item>\n" +
            "    <item name=\"text_to_search\" type=\"string\">Text to search</item>\n" +
            "\n" +
            "    <item name=\"more\" translatable=\"false\" type=\"string\">More</item>\n" +
            "\n" +
            "    <!-- Preference Titles -->\n" +
            "    <string name=\"messages_header\">Messages</string>\n" +
            "    <string name=\"sync_header\">Sync</string>\n" +
            "\n" +
            "    <!-- Messages Preferences -->\n" +
            "    <string name=\"signature_title\">Your signature</string>\n" +
            "    <string name=\"reply_title\">Default reply action</string>\n" +
            "\n" +
            "    <!-- Sync Preferences -->\n" +
            "    <string name=\"sync_title\">Sync email periodically</string>\n" +
            "    <string name=\"attachment_title\">Download incoming attachments</string>\n" +
            "    <string name=\"attachment_summary_on\">Automatically download attachments for incoming emails\n" +
            "    </string>\n" +
            "    <string name=\"attachment_summary_off\">Only download attachments when manually requested</string>\n" +
            "\n" +
            "\n" +
            "    <!-- wizard -->\n" +
            "    <string name=\"wizard_next\">Next</string>\n" +
            "    <string name=\"wizard_finish\">Finish</string>\n" +
            "    <string name=\"wizard_cancel\">Cancel</string>\n" +
            "    <string name=\"wizard_templates\">Templates</string>\n" +
            "    <string name=\"wizard_exit\">Exit</string>\n" +
            "    <string name=\"wizard_desc\">Select from Activity templates</string>\n" +
            "    <string name=\"wizard_empty_templates\">Templates are empty :(</string>\n" +
            "    <string name=\"wizard_loading\">Loading templates</string>\n" +
            "    <string name=\"wizard_app_name\">App name</string>\n" +
            "    <string name=\"wizard_package_name\">com.my.myapplication</string>\n" +
            "    <string name=\"wizard_save_location\">Save location</string>\n" +
            "    <string name=\"wizard_language\">Language</string>\n" +
            "    <string name=\"wizard_minimum_sdk\">Minimum SDK</string>\n" +
            "    <string name=\"wizard_create\">Create</string>\n" +
            "    <string name=\"wizard_previous\">Previous</string>\n" +
            "    <string name=\"wizard_scoped_storage_info\">Due to Android 11 storage restrictions, you can only save files on the app\\'s internal storage.</string>\n" +
            "    <string name=\"wizard_file_not_writable\">Directory is not writable, select another location</string>\n" +
            "    <string name=\"wizard_path_exceeds\">Location path exceeds 240 characters.</string>\n" +
            "    <string name=\"wizard_package_illegal\">Package name contains illegal characters.</string>\n" +
            "    <string name=\"wizard_package_too_short\">Package name is too short.</string>\n" +
            "    <string name=\"wizard_package_contains_spaces\">Package name cannot contain spaces.</string>\n" +
            "    <string name=\"wizard_error_name_empty\">Name cannot be empty.</string>\n" +
            "    <string name=\"wizard_package_empty\">Package name cannot be empty.</string>\n" +
            "    <string name=\"wizard_select_min_sdk\">Select a minimum SDK version.</string>\n" +
            "    <string name=\"wizard_select_save_location\">Select a save location</string>\n" +
            "\n" +
            "    <string name=\"menu_open_project\">Open project</string>\n" +
            "    <string name=\"menu_create_project\">Create project</string>\n" +
            "    <string name=\"menu_run\">Run</string>\n" +
            "    <string name=\"menu_refresh\">Refresh project</string>\n" +
            "    <string name=\"menu_format\">Format</string>\n" +
            "    <string name=\"menu_settings\">Settings</string>\n" +
            "\n" +
            "    <!-- preferences -->\n" +
            "    <string name=\"settings_title_editor\">Editor</string>\n" +
            "    <string name=\"title_activity_settings\">SettingsActivity</string>\n" +
            "\n" +
            "    <!-- Editor settings -->\n" +
            "    <string name=\"code_engine_title\">Completion Engine</string>\n" +
            "    <string name=\"settings_code_completions\">Enable code completions</string>\n" +
            "    <string name=\"code_editor_error_highlight\">Enable error highlighting</string>\n" +
            "\n" +
            "    <string name=\"text_settings_title\">Text settings</string>\n" +
            "    <string name=\"font_size_title\">Font size</string>\n" +
            "    <string name=\"font_size_message\">Set editor font size</string>\n" +
            "    <string name=\"font_size_summary\">Specify the default font size of the editor</string>\n" +
            "\n" +
            "    <!-- file manager menu -->\n" +
            "    <string name=\"menu_action_new_java_class\">Java class file</string>\n" +
            "    <string name=\"menu_new\">New</string>\n" +
            "    <string name=\"menu_refactor\">Refactor</string>\n" +
            "    <string name=\"item_project\">Project</string>\n" +
            "    <string name=\"dialog_create_class_title\">Create a class</string>\n" +
            "    <string name=\"create_class_dialog_class_name\">Class name</string>\n" +
            "    <string name=\"create_class_dialog_positive\">Create class</string>\n" +
            "    <string name=\"create_class_dialog_class_type\">Class type</string>\n" +
            "    <string name=\"create_class_dialog_invalid_name\">Invalid class name</string>\n" +
            "\n" +
            "    <string name=\"dialog_confirm_delete\">Are you sure you want to delete %1$s?</string>\n" +
            "    <string name=\"dialog_delete\">Delete</string>\n" +
            "    <string name=\"menu_preview_layout\">Preview Layout</string>\n" +
            "</resources>\n";

    public static final String STYLE_XML = "<resources>\n" + "    <style name=\"AppTheme\" parent=\"Theme.MaterialComponents.NoActionBar\">\n" + "        <item name=\"android:colorBackground\">@color/colorBackground</item>\n" + "        <item name=\"colorAccent\">@color/colorAccent</item>\n" + "        <item name=\"colorPrimary\">@color/colorPrimary</item>\n" + "        <item name=\"colorSecondary\">@color/colorSecondary</item>\n" + "        <item name=\"colorOnSecondary\">@color/colorOnSecondary</item>\n" + "        <item name=\"android:textColorPrimary\">@color/white</item>\n" + "        <item name=\"android:statusBarColor\">@color/colorBackground</item>\n" + "        <item name=\"colorControlNormal\">@color/colorControlNormal</item>\n" + "        <item name=\"actionModeBackground\">@android:color/transparent</item>\n" + "        <item name=\"aboutStyle\">@style/Widget.CodeAssist.AboutPage</item>\n" + "\t</style>\n" + "\n" + "    <style name=\"HorizontalDivider\">\n" + "        <item name=\"android:layout_width\">match_parent</item>\n" + "        <item name=\"android:layout_height\">1dp</item>\n" + "        <item name=\"android:background\">?android:attr/listDivider</item>\n" + "    </style>\n" + "\n" + "    <style name=\"VerticalDivider\">\n" + "        <item name=\"android:layout_width\">0.8dp</item>\n" + "        <item name=\"android:layout_height\">wrap_content</item>\n" + "        <item name=\"android:background\">?android:attr/listDivider</item>\n" + "    </style>\n" + "\n" + "    <style name=\"TextButton.IconOnly\" parent=\"Widget.MaterialComponents.Button.TextButton\">\n" + "        <item name=\"iconPadding\">0dp</item>\n" + "        <item name=\"cornerFamily\">cut</item>\n" + "        <item name=\"android:insetTop\">0dp</item>\n" + "        <item name=\"android:insetBottom\">0dp</item>\n" + "        <item name=\"android:paddingLeft\">12dp</item>\n" + "        <item name=\"android:paddingRight\">12dp</item>\n" + "        <item name=\"android:paddingTop\">8dp</item>\n" + "        <item name=\"android:paddingBottom\">8dp</item>\n" + "        <item name=\"android:minWidth\">48dp</item>\n" + "        <item name=\"android:minHeight\">48dp</item>\n" + "    </style>\n" + "\n" + "    <style name=\"OutlinedButton.IconOnly\" parent=\"Widget.MaterialComponents.Button.OutlinedButton\">\n" + "        <item name=\"iconPadding\">0dp</item>\n" + "        <item name=\"android:insetTop\">0dp</item>\n" + "        <item name=\"android:insetBottom\">0dp</item>\n" + "        <item name=\"android:paddingLeft\">12dp</item>\n" + "        <item name=\"android:paddingRight\">12dp</item>\n" + "        <item name=\"android:minWidth\">48dp</item>\n" + "        <item name=\"android:minHeight\">48dp</item>\n" + "    </style>\n" + "\n" + "    <style name=\"RoundOutlinedButton.IconOnly\" parent=\"\">\n" + "        <item name=\"cornerFamily\">rounded</item>\n" + "        <item name=\"cornerSize\">50%</item>\n" + "        <item name=\"android:maxWidth\">48dp</item>\n" + "        <item name=\"android:maxHeight\">48dp</item>\n" + "    </style>\n" + "\n" + "    <style name=\"ButtonBar.IconOnly.Left\" parent=\"\">\n" + "        <item name=\"cornerFamily\">rounded</item>\n" + "        <item name=\"cornerSizeTopLeft\">8dp</item>\n" + "        <item name=\"cornerSizeTopRight\">0dp</item>\n" + "        <item name=\"cornerSizeBottomLeft\">8dp</item>\n" + "        <item name=\"cornerSizeBottomRight\">0dp</item>\n" + "    </style>\n" + "\n" + "    <style name=\"ButtonBar.IconOnly.Middle\" parent=\"\">\n" + "        <item name=\"cornerFamily\">rounded</item>\n" + "        <item name=\"cornerSize\">0dp</item>\n" + "    </style>\n" + "\n" + "    <style name=\"ButtonBar.IconOnly.Right\" parent=\"\">\n" + "        <item name=\"cornerFamily\">rounded</item>\n" + "        <item name=\"cornerSizeTopLeft\">0dp</item>\n" + "        <item name=\"cornerSizeTopRight\">8dp</item>\n" + "        <item name=\"cornerSizeBottomLeft\">0dp</item>\n" + "        <item name=\"cornerSizeBottomRight\">8dp</item>\n" + "    </style>\n" + "\n" + "    <style name=\"TabLayoutText\" parent=\"TextAppearance.Design.Tab\">\n" + "        <item name=\"textAllCaps\">false</item>\n" + "        <item name=\"android:textAllCaps\">false</item>\n" + "    </style>\n" + "</resources>\n";
    public static final String COLOR_XML = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + "<resources>\n" + "    <color name=\"colorAccent\">#FFCC7832</color>\n" + "    <color name=\"colorPrimary\">#FFCC7832</color>\n" + "    <color name=\"colorSecondary\">#FFCC7832</color>\n" + "    <color name=\"colorOnSecondary\">@android:color/white</color>\n" + "    <color name=\"colorBackground\">#FF212121</color>\n" + "    <color name=\"colorSurface\">#FF2b2b2b</color>\n" + "    <color name=\"colorControlNormal\">#FFEAEAEA</color>\n" + "    <color name=\"textActionNameColor\">@android:color/tertiary_text_light</color>\n" + "    <color name=\"white\">#FFEAEAEA</color>\n" + "</resources>";
    public static final String STYLEABLE_XML = "<resources>\n" + "\n" + "    <declare-styleable name=\"editor\">\n" + "        <attr name=\"text\" format=\"string\"/>\n" + "        <attr name=\"textSize\" format=\"dimension\"/>\n" + "        <attr name=\"dividerWidth\" format=\"dimension\"/>\n" + "        <attr name=\"lineNumberVisible\" format=\"boolean\"/>\n" + "        <attr name=\"autoCompleteEnabled\" format=\"boolean\"/>\n" + "        <attr name=\"symbolCompletionEnabled\" format=\"boolean\"/>\n" + "        <attr name=\"cursorBlinkPeriod\" format=\"integer\"/>\n" + "        <attr name=\"scrollbarsEnabled\" format=\"boolean\"/>\n" + "        <attr name=\"verticalScrollbarEnabled\" format=\"boolean\"/>\n" + "        <attr name=\"horizontalScrollbarEnabled\" format=\"boolean\"/>\n" + "    </declare-styleable>\n" + "\n" + "</resources>";
    @Test
    public void test() throws IOException, XmlPullParserException {
        ResourceStringParser parser = new ResourceStringParser(null,
                new MockFileManager(null));
        Map<String, Value> stringValueMap = parser.parseStringXml(TEST_XML);
        assertThat(stringValueMap).isNotNull();
        assertThat(stringValueMap).isNotEmpty();
    }

}
