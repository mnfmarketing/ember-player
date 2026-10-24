@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.ember.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.ember.cast.CastStatus
import app.ember.sound.SoundPresets
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import kotlin.math.roundToInt

/** Equalizer presets, custom bands and narration boost. */
@Composable
fun SoundSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsState()
    val current = SoundPresets.gainsFor(s)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EmberColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
                    Text("Shape the sound for narration, music or your headphones", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
                }
                Switch(checked = s.eqEnabled, onCheckedChange = { vm.setEqEnabled(it) })
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoundPresets.presets.forEach { p ->
                    FilterChip(
                        selected = s.eqPreset == p.key,
                        onClick = {
                            vm.setEqPreset(p.key)
                            if (!s.eqEnabled) vm.setEqEnabled(true)
                        },
                        label = { Text(p.label) },
                        enabled = true,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
                FilterChip(
                    selected = s.eqPreset == "custom",
                    onClick = {
                        vm.setEqCustom(current)
                        if (!s.eqEnabled) vm.setEqEnabled(true)
                    },
                    label = { Text("Custom") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))
            SoundPresets.bandLabels.forEachIndexed { i, label ->
                var dragging by remember(i) { mutableStateOf<Float?>(null) }
                val value = dragging ?: current[i].toFloat()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = EmberColors.TextMuted, modifier = Modifier.width(64.dp))
                    Slider(
                        value = value,
                        onValueChange = { dragging = it.roundToInt().toFloat() },
                        onValueChangeFinished = {
                            val v = dragging?.roundToInt() ?: current[i]
                            dragging = null
                            vm.setEqCustom(current.toMutableList().also { it[i] = v })
                            if (!s.eqEnabled) vm.setEqEnabled(true)
                        },
                        valueRange = SoundPresets.MIN_DB.toFloat()..SoundPresets.MAX_DB.toFloat(),
                        steps = SoundPresets.MAX_DB - SoundPresets.MIN_DB - 1,
                        modifier = Modifier.weight(1f),
                    )
                    val v = value.roundToInt()
                    Text(
                        (if (v > 0) "+" else "") + "$v dB",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmberColors.Text,
                        modifier = Modifier.width(52.dp).padding(start = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Boost quiet narration", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                    Text("Lifts soft voices without cranking the volume", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
                }
                Switch(checked = s.narrationBoost, onCheckedChange = { vm.setNarrationBoost(it) })
            }
            val np by vm.nowPlaying.collectAsState()
            np?.takeIf { !it.isEpisode }?.let { current ->
                Spacer(Modifier.height(16.dp))
                BookSoundSection(vm, current.book.id)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Sound settings apply on this phone and its headphones. Chromecast devices use their own sound settings.",
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextFaint,
            )
        }
    }
}

/** Lists Chromecast and Google speaker devices on your Wi-Fi. */
@Composable
fun CastSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val device by CastStatus.device.collectAsState()
    val ready = remember {
        try {
            CastContext.getSharedInstance(context)
            true
        } catch (e: Exception) {
            false
        }
    }
    val router = remember { MediaRouter.getInstance(context) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastStatus.RECEIVER_ID))
            .build()
    }
    val routes = remember { mutableStateListOf<MediaRouter.RouteInfo>() }
    var connecting by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        fun refresh() {
            routes.clear()
            routes.addAll(router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) })
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
                connecting = null
                refresh()
            }
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        onDispose { router.removeCallback(callback) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EmberColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Play on", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (device != null) router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
                        onDismiss()
                    }
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Speaker, null, tint = if (device == null) MaterialTheme.colorScheme.primary else EmberColors.TextMuted)
                Spacer(Modifier.width(14.dp))
                Text("This phone", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, modifier = Modifier.weight(1f))
                if (device == null) Text("Playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            routes.forEach { route ->
                val selected = route.isSelected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (!selected) {
                                connecting = route.id
                                router.selectRoute(route)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                        null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else EmberColors.TextMuted,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(route.name, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                        route.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
                        }
                    }
                    when {
                        connecting == route.id && !selected -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        selected -> Text("Playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (routes.isEmpty()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ready) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        if (ready) "Looking for Chromecast and Google speakers on your Wi-Fi…" else "Casting needs Google Play services, which isn't available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                    )
                }
            }
            if (device != null) {
                TextButton(onClick = {
                    router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
                    onDismiss()
                }) { Text("Stop casting") }
            }
            Text(
                "Books on your phone or Drive stream to the speaker through your phone, so keep it on the same Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextFaint,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}
