package app.ember.ui.screens

import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import app.ember.util.formatDuration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsScreen(vm: MainViewModel, onOpenBook: (String) -> Unit, onOpenRecap: () -> Unit = {}) {
    val stats by vm.stats.collectAsState()
    var editGoals by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Your listening", style = MaterialTheme.typography.headlineLarge, color = EmberColors.Text, modifier = Modifier.weight(1f))
                IconButton(onClick = { editGoals = true }) { Icon(Icons.Rounded.Edit, "Edit goals", tint = EmberColors.TextMuted) }
            }
        }
        item {
            // A shareable card of your month or year.
            androidx.compose.material3.OutlinedButton(
                onClick = onOpenRecap,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(52.dp),
            ) {
                Icon(androidx.compose.material.icons.Icons.Rounded.AutoAwesome, null)
                Spacer(Modifier.size(8.dp))
                Text("Your recap · share your month or year")
            }
            Spacer(Modifier.height(8.dp))
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(Modifier.weight(1f)) {
                    Box(Modifier.size(120.dp).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                        val goalMs = stats.dailyGoalMin * 60_000f
                        val frac = if (goalMs > 0) (stats.todayMs / goalMs).coerceIn(0f, 1f) else 0f
                        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                            val stroke = 12.dp.toPx()
                            drawArc(EmberColors.SurfaceHighest, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                            drawArc(accent, -90f, 360f * frac, false, style = Stroke(stroke, cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${stats.todayMs / 60_000}", style = MaterialTheme.typography.headlineMedium, color = EmberColors.Text)
                            Text("of ${stats.dailyGoalMin} min", style = MaterialTheme.typography.labelSmall, color = EmberColors.TextMuted)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Today", style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                Card(Modifier.weight(1f)) {
                    Icon(
                        Icons.Rounded.LocalFireDepartment,
                        null,
                        tint = if (stats.streak > 0) accent else EmberColors.TextFaint,
                        modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
                    )
                    Text(
                        "${stats.streak}",
                        style = MaterialTheme.typography.displaySmall,
                        color = EmberColors.Text,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(
                        "day streak",
                        style = MaterialTheme.typography.titleSmall,
                        color = EmberColors.Text,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(
                        "Best: ${stats.longestStreak} days",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmberColors.TextMuted,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }

        item {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("This week", style = MaterialTheme.typography.titleSmall, color = EmberColors.Text)
                Text(
                    formatDuration(stats.week.sumOf { it.second }) + " listened",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextMuted,
                )
                Spacer(Modifier.height(16.dp))
                val max = (stats.week.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(stats.dailyGoalMin * 60_000L).coerceAtLeast(1L)
                val goalFrac = stats.dailyGoalMin * 60_000f / max
                Box(Modifier.fillMaxWidth().height(140.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val n = stats.week.size.coerceAtLeast(1)
                        val slot = size.width / n
                        val barW = slot * 0.5f
                        val goalY = size.height * (1 - goalFrac)
                        drawLine(EmberColors.Outline, Offset(0f, goalY), Offset(size.width, goalY), strokeWidth = 1.dp.toPx())
                        stats.week.forEachIndexed { i, (_, ms) ->
                            val h = (ms.toFloat() / max) * size.height
                            val left = i * slot + (slot - barW) / 2
                            drawRoundRect(
                                EmberColors.SurfaceHighest,
                                topLeft = Offset(left, 0f),
                                size = Size(barW, size.height),
                                cornerRadius = CornerRadius(barW / 2, barW / 2),
                            )
                            if (h > 0f) {
                                drawRoundRect(
                                    accent,
                                    topLeft = Offset(left, size.height - h),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(barW / 2, barW / 2),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    stats.week.forEach { (date, _) ->
                        Text(
                            dayLabel(date),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (date == LocalDate.now()) accent else EmberColors.TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            Card(Modifier.padding(horizontal = 16.dp)) {
                val done = stats.finishedThisYear.size
                Text("${LocalDate.now().year} reading goal", style = MaterialTheme.typography.titleSmall, color = EmberColors.Text)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$done", style = MaterialTheme.typography.displaySmall, color = EmberColors.Text)
                    Text(
                        " of ${stats.yearlyGoal} books",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmberColors.TextMuted,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                app.ember.ui.components.ThinProgress(if (stats.yearlyGoal > 0) done.toFloat() / stats.yearlyGoal else 0f, height = 6.dp)
                Spacer(Modifier.height(6.dp))
                Text(paceText(done, stats.yearlyGoal), style = MaterialTheme.typography.labelSmall, color = EmberColors.TextMuted)
            }
        }

        if (stats.finishedThisYear.isNotEmpty()) {
            item { app.ember.ui.components.SectionHeader("Finished this year") }
            item { BookRow(stats.finishedThisYear, onOpenBook) }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile("Total listening", formatDuration(stats.totalMs), Modifier.weight(1f))
                StatTile("Daily average", formatDuration(stats.last30AvgMs), Modifier.weight(1f), "last 30 days")
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile("Books finished", "${stats.finishedAllTime}", Modifier.weight(1f), "all time")
                StatTile("In progress", "${stats.inProgress}", Modifier.weight(1f))
            }
        }
    }

    if (editGoals) {
        var daily by remember { mutableFloatStateOf(stats.dailyGoalMin.toFloat()) }
        var yearly by remember { mutableFloatStateOf(stats.yearlyGoal.toFloat()) }
        AlertDialog(
            onDismissRequest = { editGoals = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Your goals") },
            text = {
                Column {
                    Text("Daily listening: ${daily.toInt()} min", color = EmberColors.Text)
                    Slider(value = daily, onValueChange = { daily = (Math.round(it / 5f) * 5).toFloat() }, valueRange = 5f..180f)
                    Spacer(Modifier.height(8.dp))
                    Text("Books this year: ${yearly.toInt()}", color = EmberColors.Text)
                    Slider(value = yearly, onValueChange = { yearly = Math.round(it).toFloat() }, valueRange = 1f..150f)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setDailyGoal(daily.toInt())
                    vm.setYearlyGoal(yearly.toInt())
                    editGoals = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editGoals = false }) { Text("Cancel") } },
        )
    }
}

private fun dayLabel(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())

private fun paceText(done: Int, goal: Int): String {
    if (goal <= 0) return ""
    val today = LocalDate.now()
    val expected = goal * today.dayOfYear / today.lengthOfYear().toFloat()
    val diff = done - expected
    return when {
        done >= goal -> "Goal reached. Everything from here is a bonus."
        diff >= 1 -> "${diff.toInt()} ${if (diff.toInt() == 1) "book" else "books"} ahead of pace"
        diff <= -1 -> "${(-diff).toInt()} ${if ((-diff).toInt() == 1) "book" else "books"} behind pace"
        else -> "Right on pace"
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(EmberColors.Surface)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(EmberColors.Surface)
            .padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = EmberColors.TextMuted)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = EmberColors.Text)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = EmberColors.TextFaint)
    }
}
