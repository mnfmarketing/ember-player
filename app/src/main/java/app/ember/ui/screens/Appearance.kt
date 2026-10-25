package app.ember.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ember.ui.MainViewModel
import app.ember.ui.theme.AccentSwatches
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.EmberPalette
import app.ember.ui.theme.EmberThemes
import app.ember.ui.theme.accentFromHue
import app.ember.ui.theme.hueOf
import app.ember.ui.theme.onColorFor
import app.ember.ui.theme.readableAccent
import app.ember.ui.theme.trueBlack

/**
 * Settings → Appearance: light or dark (or follow the phone, including its night schedule),
 * true black, the theme, and the accent color. Every tap changes the whole app right away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    val dark = EmberColors.isDark
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        // Light or dark
        Label("Light or dark")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Match phone", s.appearance == "system") { vm.setAppearance("system") }
            Choice("Light", s.appearance == "light") { vm.setAppearance("light") }
            Choice("Dark", s.appearance == "dark") { vm.setAppearance("dark") }
        }
        if (s.appearance == "system") {
            Note("Switches with your phone's dark mode, including when it turns on at night.")
        }
        if (s.appearance != "light") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { vm.setTrueBlack(!s.trueBlack) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("True black", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                    Text("Pure black behind everything in dark mode. Easier on the battery.", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
                }
                Switch(checked = s.trueBlack, onCheckedChange = { vm.setTrueBlack(it) })
            }
        }

        // Themes, shown in the mode you're in now
        Spacer(Modifier.height(14.dp))
        Label("Theme")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EmberThemes.forEach { t ->
                val p = if (dark) (if (s.trueBlack) trueBlack(t.dark) else t.dark) else t.light
                ThemeTile(t.name, p, selected = s.themeId == t.id) { vm.setTheme(t.id) }
            }
        }

        // Accent color
        Spacer(Modifier.height(16.dp))
        Label("Accent color")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Match the book", s.accentMode == "book") { vm.setAccent("book") }
            Choice("Theme's color", s.accentMode == "theme") { vm.setAccent("theme") }
        }
        if (s.accentMode == "book") Note("Buttons and highlights take on the color of the cover that's playing.")
        Spacer(Modifier.height(10.dp))
        val custom = s.accentMode == "fixed" && AccentSwatches.none { it.second.toArgb() == s.accentColor }
        var showSlider by remember { mutableStateOf(custom) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentSwatches.forEach { (name, color) ->
                Swatch(name, readableAccent(color), selected = s.accentMode == "fixed" && s.accentColor == color.toArgb()) {
                    showSlider = false
                    vm.setAccent("fixed", color.toArgb())
                }
            }
            // Custom: a rainbow dot that opens the slider
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                    .then(if (custom || showSlider) Modifier.border(3.dp, EmberColors.Text, CircleShape) else Modifier)
                    .semantics { contentDescription = "Custom accent" }
                    .clickable(onClickLabel = "Custom color") {
                        showSlider = true
                        if (!custom) vm.setAccent("fixed", accentFromHue(hueOf(Color(s.accentColor))).toArgb())
                    },
            )
        }
        if (showSlider || custom) {
            var hue by remember { mutableFloatStateOf(hueOf(Color(s.accentColor))) }
            Spacer(Modifier.height(10.dp))
            Text("Custom color", style = MaterialTheme.typography.bodyMedium, color = EmberColors.Text)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient((0..12).map { readableAccent(accentFromHue(it * 30f)) })),
            )
            Slider(
                value = hue,
                onValueChange = { hue = it },
                onValueChangeFinished = { vm.setAccent("fixed", accentFromHue(hue).toArgb()) },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(thumbColor = readableAccent(accentFromHue(hue)), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else EmberColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) onColorFor(accent) else EmberColors.Text, maxLines = 1)
    }
}

/** A small picture of a theme: its background, a card, text lines and its accent. */
@Composable
private fun ThemeTile(name: String, p: EmberPalette, selected: Boolean, onClick: () -> Unit) {
    // The whole tile, name included, picks the theme.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "$name theme", onClick = onClick),
    ) {
        Box(
            Modifier
                .size(92.dp, 72.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.background)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else EmberColors.Outline, RoundedCornerShape(14.dp))
                .padding(10.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.surfaceHigh)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.text))
                Box(Modifier.width(28.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.textMuted))
            }
            Box(Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(p.defaultAccent))
            if (selected) {
                Box(
                    Modifier.align(Alignment.BottomStart).size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else EmberColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Swatch(name: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, EmberColors.Text, CircleShape) else Modifier)
            .semantics { contentDescription = "$name accent" }
            .clickable(onClickLabel = name, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, name, tint = onColorFor(color), modifier = Modifier.size(20.dp))
    }
}
