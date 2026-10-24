@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.ember.ui.screens

import androidx.compose.material.icons.rounded.PictureAsPdf
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.produceState
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ember.ui.ExternalApp
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
private fun FormTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = EmberColors.Text)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, numeric: Boolean = false, minLines: Int = 1) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = minLines == 1,
        minLines = minLines,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

private fun formatIndex(f: Float?): String = when {
    f == null -> ""
    f % 1f == 0f -> f.toInt().toString()
    else -> f.toString()
}

@Composable
fun EditBookScreen(vm: MainViewModel, bookId: String, onDone: () -> Unit) {
    val bookState by remember(bookId) { vm.observeBook(bookId) }.collectAsState(initial = null)
    val book = bookState ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    key(book.id) {
        var title by rememberSaveable { mutableStateOf(book.title) }
        var author by rememberSaveable { mutableStateOf(book.author) }
        var narrator by rememberSaveable { mutableStateOf(book.narrator) }
        var series by rememberSaveable { mutableStateOf(book.series) }
        var index by rememberSaveable { mutableStateOf(formatIndex(book.seriesIndex)) }
        var description by rememberSaveable { mutableStateOf(book.description) }
        var link by rememberSaveable { mutableStateOf(book.externalLink) }
        var writeFile by rememberSaveable { mutableStateOf(true) }

        Column(
            Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            FormTopBar("Edit details", onDone)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Field("Title", title, { title = it })
                Field("Author", author, { author = it })
                Field("Narrator", narrator, { narrator = it })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(2f)) { Field("Series", series, { series = it }) }
                    Box(Modifier.weight(1f)) { Field("Book #", index, { v -> index = v.filter { it.isDigit() || it == '.' }.take(5) }, numeric = true) }
                }
                if (book.isExternal) Field("Link (optional)", link, { link = it })
                Field("Description", description, { description = it }, minLines = 3)
                if (!book.isExternal) PdfField(vm, book)
                if (!book.isExternal) {
                    Spacer(Modifier.height(8.dp))
                    SaveToFileOption(vm, book.id, writeFile, { writeFile = it }, what = "title, author, narrator, description and cover")
                }
                Spacer(Modifier.height(16.dp))
            }
            Button(
                onClick = {
                    vm.saveDetails(
                        bookId = book.id,
                        title = title,
                        author = author,
                        narrator = narrator,
                        series = series,
                        seriesIndex = index.toFloatOrNull(),
                        description = description,
                        externalLink = if (book.isExternal) link else null,
                        writeFile = writeFile && !book.isExternal,
                    )
                    onDone()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .height(52.dp),
            ) { Text("Save") }
        }
    }
}

@Composable
fun AddExternalScreen(vm: MainViewModel, onDone: (String?) -> Unit) {
    var app by rememberSaveable { mutableStateOf(ExternalApp.AUDIBLE) }
    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var series by rememberSaveable { mutableStateOf("") }
    var index by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var covers by remember { mutableStateOf<List<String>>(emptyList()) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        FormTopBar("Add from another app", { onDone(null) })
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "Audible, Libby and Spotify lock their audio to their own apps, so Ember keeps these on your shelf, opens the right app when you tap play, and lets you log progress so your stats stay complete.",
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
            )
            Spacer(Modifier.height(16.dp))
            Text("Where do you listen to it?", style = MaterialTheme.typography.labelLarge, color = EmberColors.Text)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExternalApp.entries.forEach { a ->
                    FilterChip(
                        selected = app == a,
                        onClick = { app = a },
                        label = { Text(a.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Field("Title", title, { title = it })
            Field("Author", author, { author = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(2f)) { Field("Series (optional)", series, { series = it }) }
                Box(Modifier.weight(1f)) { Field("Book #", index, { v -> index = v.filter { it.isDigit() || it == '.' }.take(5) }, numeric = true) }
            }
            Field("Share link (optional)", link, { link = it })
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    searching = true
                    scope.launch {
                        covers = vm.searchCovers(title, author)
                        chosen = covers.firstOrNull()
                        searching = false
                    }
                },
                enabled = title.isNotBlank() && !searching,
            ) { Text(if (searching) "Searching…" else "Find covers") }
            if (covers.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    covers.forEach { url ->
                        val selected = url == chosen
                        AsyncImage(
                            model = url,
                            contentDescription = "Cover option",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(84.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(EmberColors.SurfaceHigh)
                                .border(
                                    if (selected) 3.dp else 0.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else EmberColors.SurfaceHigh,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { chosen = url },
                        )
                    }
                }
            } else {
                Text(
                    "If you skip this, Ember looks up a cover automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextFaint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = {
                vm.addExternal(
                    title = title,
                    author = author,
                    series = series,
                    seriesIndex = index.toFloatOrNull(),
                    app = app,
                    link = link,
                    coverUrl = chosen,
                    onDone = { id -> onDone(id) },
                )
            },
            enabled = title.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(20.dp)
                .height(52.dp),
        ) { Text("Add to my shelf") }
    }
}

/**
 * "Also save into the audio file" switch, with a short explanation of what's possible
 * for this book and a way back to the original file.
 */
@Composable
fun SaveToFileOption(vm: MainViewModel, bookId: String, checked: Boolean, onChange: (Boolean) -> Unit, what: String) {
    val support by produceState<MainViewModel.FileEditSupport?>(null, bookId) { value = vm.fileEditSupport(bookId) }
    val hasBackup by produceState(false, bookId) { value = vm.hasOriginalFileBackup(bookId) }
    var confirmRestore by remember { mutableStateOf(false) }
    val s = support ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EmberColors.Surface)
            .padding(14.dp),
    ) {
        when (s) {
            MainViewModel.FileEditSupport.SUPPORTED, MainViewModel.FileEditSupport.NEEDS_ACCESS -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Also save into the audio file", style = MaterialTheme.typography.titleSmall, color = EmberColors.Text)
                        Text(
                            "Writes the $what into the M4B so other apps see them too. The audio isn't touched." +
                                if (s == MainViewModel.FileEditSupport.NEEDS_ACCESS) " The first time, Android asks you to confirm the folder." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmberColors.TextMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = checked, onCheckedChange = onChange)
                }
                if (hasBackup) {
                    TextButton(onClick = { confirmRestore = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Restore original file")
                    }
                }
            }
            MainViewModel.FileEditSupport.CLOUD -> Text(
                "Changes are saved in Ember. Books on Google Drive can't have their files changed yet.",
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
            )
            MainViewModel.FileEditSupport.NOT_M4B -> Text(
                "Changes are saved in Ember. Saving into the file works for single-file M4B and M4A books.",
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
            )
            MainViewModel.FileEditSupport.NONE -> {}
        }
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Restore the original file?") },
            text = { Text("Puts the M4B back exactly as it was before Ember first changed it, and reloads its title and chapters from the file.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    vm.restoreOriginalFile(bookId)
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}


/** The book's companion PDF: found in its folder automatically, or attached here. Changes apply right away. */
@Composable
private fun PdfField(vm: MainViewModel, book: app.ember.data.BookEntity) {
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? -> if (uri != null) vm.setPdf(book.id, uri) }
    val uri = book.pdfUri?.takeIf { it.isNotBlank() }
    val name by androidx.compose.runtime.produceState<String?>(null, uri) { value = uri?.let { vm.pdfName(it) } }
    Spacer(Modifier.height(12.dp))
    Text("Companion PDF", style = MaterialTheme.typography.labelLarge, color = EmberColors.TextMuted)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(
            Icons.Rounded.PictureAsPdf,
            null,
            tint = if (uri != null) MaterialTheme.colorScheme.primary else EmberColors.TextFaint,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (uri == null) "None. Maps and extras from the publisher go here." else name ?: "PDF",
            style = MaterialTheme.typography.bodyMedium,
            color = if (uri == null) EmberColors.TextMuted else EmberColors.Text,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.TextButton(onClick = { pick.launch(arrayOf("application/pdf")) }) {
            Text(if (uri == null) "Attach a PDF" else "Change")
        }
        if (uri != null) {
            androidx.compose.material3.TextButton(onClick = { vm.setPdf(book.id, null) }) { Text("Remove") }
        }
    }
}
