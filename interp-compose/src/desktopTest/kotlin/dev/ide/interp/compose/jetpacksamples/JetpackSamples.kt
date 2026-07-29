@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ide.interp.compose.jetpacksamples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A curated corpus of REAL Jetpack Compose Material3 samples (faithful to the AndroidX
 * `androidx.compose.material3.samples` bodies, Apache-2.0), used as a device conformance sweep for the
 * Material3 interpret flip: each is a no-arg `@Composable` the VM interprets on ART and composes into a real
 * Compose UI. The list of samples the sweep runs lives in `VmJetpackSamplesArtSpike.samples`.
 */

// ---- Buttons -------------------------------------------------------------------------------------
@Composable fun ButtonSample() { Button(onClick = {}) { Text("Button") } }
@Composable fun ElevatedButtonSample() { ElevatedButton(onClick = {}) { Text("Elevated Button") } }
@Composable fun FilledTonalButtonSample() { FilledTonalButton(onClick = {}) { Text("Filled Tonal Button") } }
@Composable fun OutlinedButtonSample() { OutlinedButton(onClick = {}) { Text("Outlined Button") } }
@Composable fun TextButtonSample() { TextButton(onClick = {}) { Text("Text Button") } }

// ---- FABs / icon buttons -------------------------------------------------------------------------
@Composable fun FloatingActionButtonSample() { FloatingActionButton(onClick = {}) { Text("+") } }
@Composable fun ExtendedFabSample() { ExtendedFloatingActionButton(onClick = {}) { Text("Extended") } }
@Composable fun IconButtonSample() { IconButton(onClick = {}) { Text("i") } }
@Composable fun FilledIconButtonSample() { FilledIconButton(onClick = {}) { Text("f") } }
@Composable fun OutlinedIconButtonSample() { OutlinedIconButton(onClick = {}) { Text("o") } }

// ---- Selection controls --------------------------------------------------------------------------
@Composable fun CheckboxSample() { var c by remember { mutableStateOf(true) }; Checkbox(checked = c, onCheckedChange = { c = it }) }
@Composable fun SwitchSample() { var c by remember { mutableStateOf(true) }; Switch(checked = c, onCheckedChange = { c = it }) }
@Composable fun RadioButtonSample() { var s by remember { mutableStateOf(true) }; RadioButton(selected = s, onClick = { s = !s }) }
@Composable fun SliderSample() { var v by remember { mutableFloatStateOf(0f) }; Slider(value = v, onValueChange = { v = it }) }

// ---- Chips ---------------------------------------------------------------------------------------
@Composable fun AssistChipSample() { AssistChip(onClick = {}, label = { Text("Assist") }) }
@Composable fun FilterChipSample() { var s by remember { mutableStateOf(false) }; FilterChip(selected = s, onClick = { s = !s }, label = { Text("Filter") }) }
@Composable fun SuggestionChipSample() { SuggestionChip(onClick = {}, label = { Text("Suggestion") }) }

// ---- Containers / surfaces -----------------------------------------------------------------------
@Composable fun CardSample() { Card(Modifier.width(180.dp)) { Text("Card", Modifier.padding(16.dp)) } }
@Composable fun SurfaceSample() { Surface { Text("Surface", Modifier.padding(16.dp)) } }
@Composable fun ScaffoldSample() { Scaffold { p -> Text("Scaffold body", Modifier.padding(p)) } }
@Composable fun TopAppBarSample() { TopAppBar(title = { Text("Title") }) }
@Composable fun ListItemSample() { ListItem(headlineContent = { Text("Headline") }) }
@Composable fun BadgeSample() { Badge { Text("9") } }
@Composable fun SnackbarSample() { Snackbar { Text("Snackbar") } }
@Composable fun HorizontalDividerSample() { HorizontalDivider() }

// ---- Text fields ---------------------------------------------------------------------------------
@Composable fun TextFieldSample() { var t by remember { mutableStateOf("") }; TextField(value = t, onValueChange = { t = it }, label = { Text("Label") }) }
@Composable fun OutlinedTextFieldSample() { var t by remember { mutableStateOf("") }; OutlinedTextField(value = t, onValueChange = { t = it }, label = { Text("Label") }) }

// ---- Navigation / tabs ---------------------------------------------------------------------------
@Composable fun NavigationBarSample() {
    NavigationBar { NavigationBarItem(selected = true, onClick = {}, icon = { Text("H") }, label = { Text("Home") }) }
}
@Composable fun TabRowSample() {
    TabRow(selectedTabIndex = 0) { Tab(selected = true, onClick = {}) { Text("Tab", Modifier.padding(8.dp)) } }
}

// ---- Progress ------------------------------------------------------------------------------------
@Composable fun LinearProgressIndicatorSample() { LinearProgressIndicator(progress = { 0.5f }) }
@Composable fun CircularProgressIndicatorSample() { CircularProgressIndicator(progress = { 0.5f }) }
@Composable fun LinearIndeterminateSample() { LinearProgressIndicator() }
@Composable fun CircularIndeterminateSample() { CircularProgressIndicator() }

// ---- Text / composite ----------------------------------------------------------------------------
@Composable fun TextSample() { Text("Hello, Compose") }

/** A small composite screen: several components in a Column, the shape a real @Preview usually takes. */
@Composable
fun MiniScreenSample() {
    var checked by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mini Screen")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Spacer(Modifier.width(8.dp))
            Text(if (checked) "On" else "Off")
        }
        Button(onClick = {}) { Text("Continue") }
    }
}
