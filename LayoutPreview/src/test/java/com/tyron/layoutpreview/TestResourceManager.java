package com.tyron.layoutpreview;

import android.util.AttributeSet;
import android.util.Xml;

import com.flipkart.android.proteus.value.Value;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.resource.ResourceStringParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
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

    @Test
    public void test() throws Exception {
        ResourceStringParser parser = new ResourceStringParser(TEST_XML);
        Map<String, Map<String, Value>> strings = parser.getStrings();
        System.out.println(strings);
    }

}
