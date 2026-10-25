package app.ember.ui

import app.ember.ui.screens.BookInfoSheet
import app.ember.ui.screens.FinishedSheet
import app.ember.ui.screens.PocketLockOverlay
import app.ember.ui.screens.RecapScreen
import app.ember.ui.screens.CarModeScreen
import app.ember.ui.screens.UpNextScreen
import app.ember.ui.screens.PdfScreen
import androidx.compose.foundation.layout.widthIn
import app.ember.ui.screens.BookSelectionBar
import app.ember.ui.components.LocalBookSelection
import app.ember.ui.components.BookSelection
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.activity.compose.BackHandler
import app.ember.ui.screens.BookQuickSheet
import app.ember.ui.components.LocalBookMenu
import androidx.compose.runtime.CompositionLocalProvider
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ember.ui.components.bookAccent
import app.ember.ui.screens.AddExternalScreen
import app.ember.ui.screens.ClipsScreen
import app.ember.ui.screens.DownloadsScreen
import app.ember.ui.screens.IssueBanner
import app.ember.ui.screens.QuoteCardScreen
import app.ember.ui.screens.SearchScreen
import app.ember.ui.screens.TorrentAddScreen
import app.ember.ui.screens.ShowSettingsScreen
import app.ember.ui.screens.PodcastPreviewScreen
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import app.ember.ui.screens.ChapterEditorScreen
import app.ember.ui.screens.DiscoverScreen
import app.ember.ui.screens.DriveFolderPickerScreen
import app.ember.ui.screens.PodcastDetailScreen
import app.ember.ui.screens.PodcastsScreen
import app.ember.playback.PlaybackService
import app.ember.ui.screens.BookDetailScreen
import app.ember.ui.screens.CollectionScreen
import app.ember.ui.screens.EditBookScreen
import app.ember.ui.screens.HomeScreen
import app.ember.ui.screens.LibraryScreen
import app.ember.ui.screens.MiniPlayer
import app.ember.ui.screens.DockedMiniPlayer
import app.ember.ui.screens.SleepSheet
import app.ember.ui.screens.SpeedSheet
import app.ember.ui.screens.PlayerScreen
import app.ember.ui.screens.SettingsScreen
import app.ember.ui.screens.StatsScreen
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.EmberTheme

private data class TopDest(val route: String, val label: String, val icon: ImageVector)

private val compactTabs = listOf(
    TopDest("home", "Home", Icons.Rounded.Home),
    TopDest("library", "Books", Icons.Rounded.LibraryBooks),
    TopDest("podcasts", "Podcasts", Icons.Rounded.Podcasts),
    // Shown as your profile circle, labeled with your first name.
    TopDest("you", "You", Icons.Rounded.Person),
)

private val railTabs = compactTabs

/** A tab's icon: your profile circle for the last tab, the usual icon for the rest. */
@Composable
private fun TabIcon(vm: MainViewModel, t: TopDest, selected: Boolean) {
    if (t.route == "you") app.ember.ui.screens.ProfileAvatar(vm, 26.dp, selected = selected) else Icon(t.icon, null)
}

private fun tabLabel(t: TopDest, profileName: String): String =
    if (t.route == "you") profileName.trim().substringBefore(' ').ifBlank { "You" } else t.label

private val formRoutes = setOf("player", "edit/{id}", "chapters/{id}", "add_external", "drive_picker", "card/{kind}/{id}", "search?q={q}", "torrent_add", "pdf/{id}", "car", "recap")

@Composable
fun EmberRoot(vm: MainViewModel, routeRequest: String? = null, onRouteHandled: () -> Unit = {}) {
    val np by vm.nowPlaying.collectAsState()
    val look by vm.settings.collectAsState()
    EmberTheme(
        themeId = look.themeId,
        mode = look.appearance,
        trueBlackOn = look.trueBlack,
        accentMode = look.accentMode,
        accentColor = look.accentColor,
        bookColor = np?.book?.coverColor,
    ) {
        val config = LocalConfiguration.current
        val expanded = config.screenWidthDp >= 600
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        // The player still on screen, including while it animates closed. The mini player and tabs
        // wait until it's fully gone; appearing earlier would take its room and squash the cover.
        val visibleEntries by nav.visibleEntries.collectAsState()
        val playerOnScreen = visibleEntries.any { it.destination.route == "player" }
        var focused by rememberSaveable { mutableStateOf(false) }
        // Unfolded: the player can shrink to a small bar so the library gets the whole screen.
        var minimized by rememberSaveable { mutableStateOf(false) }
        // About a quarter of the screen, but never so narrow the buttons get cramped.
        // Player column width: drag the handle to resize; Ember remembers it.
        val savedFraction by vm.settings.collectAsState()
        var paneFraction by remember(savedFraction.playerPaneFraction) { mutableFloatStateOf(savedFraction.playerPaneFraction) }
        val screenW = config.screenWidthDp.toFloat()
        val paneWidth = (paneFraction * screenW).coerceIn(260f, maxOf(260f, screenW * 0.6f)).dp
        var miniSpeed by rememberSaveable { mutableStateOf(false) }
        var miniSleep by rememberSaveable { mutableStateOf(false) }
        val sleepState by vm.sleep.collectAsState()

        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) vm.addFolder(uri)
        }
        val addFolder: () -> Unit = { folderPicker.launch(null) }

        // Saving edits into audio files: one-time folder permission, then status messages.
        val accessRequest by vm.folderAccessRequest.collectAsState()
        val fileNotice by vm.fileNotice.collectAsState()
        val context = LocalContext.current
        val writeAccessPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            vm.grantFolderAccess(uri)
        }
        LaunchedEffect(fileNotice) {
            val n = fileNotice ?: return@LaunchedEffect
            Toast.makeText(context, n.text, if (n.isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            if (!n.text.endsWith("…")) vm.clearFileNotice()
        }
        accessRequest?.let { folder ->
            AlertDialog(
                onDismissRequest = { vm.grantFolderAccess(null) },
                containerColor = EmberColors.SurfaceHigh,
                title = { Text("Allow saving into your files") },
                text = {
                    Text(
                        "To write changes into the audio file, Android needs you to confirm the \"${folder.name}\" folder once. " +
                            "On the next screen, make sure that folder is open and tap Use this folder, then Allow.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val tree = Uri.parse(folder.uri)
                        val initial = try {
                            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                        } catch (e: Exception) {
                            null
                        }
                        writeAccessPicker.launch(initial)
                    }) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = { vm.grantFolderAccess(null) }) { Text("Not now") } },
            )
        }

        // Plain-words playback problems, and one-off notes like the next book in a series starting.
        val issue by vm.playbackIssue.collectAsState()
        val notice by vm.playbackNotice.collectAsState()
        LaunchedEffect(notice) {
            val n = notice ?: return@LaunchedEffect
            Toast.makeText(context, n, Toast.LENGTH_LONG).show()
            vm.clearPlaybackNotice()
        }
        val onIssueAction: (app.ember.playback.PlaybackIssue.Action) -> Unit = { action ->
            when (action) {
                app.ember.playback.PlaybackIssue.Action.RETRY -> vm.retryPlayback()
                app.ember.playback.PlaybackIssue.Action.RESCAN -> {
                    vm.dismissPlaybackIssue()
                    vm.rescan()
                    Toast.makeText(context, "Rescanning your library…", Toast.LENGTH_SHORT).show()
                }
                app.ember.playback.PlaybackIssue.Action.RECONNECT_DRIVE -> {
                    vm.dismissPlaybackIssue()
                    nav.navigate("settings/Library folders") { launchSingleTop = true }
                }
                app.ember.playback.PlaybackIssue.Action.STREAM -> vm.streamInstead()
                app.ember.playback.PlaybackIssue.Action.NONE -> vm.dismissPlaybackIssue()
            }
        }

        LaunchedEffect(expanded, route) {
            if (expanded && route == "player") nav.popBackStack()
        }
        LaunchedEffect(routeRequest) {
            val r = routeRequest ?: return@LaunchedEffect
            if (r == "sleep") {
                miniSleep = true
                onRouteHandled()
                return@LaunchedEffect
            }
            if (r == "home" || r == "library" || r == "podcasts" || r == "you") nav.navigateTop(r) else nav.navigate(r) { launchSingleTop = true }
            onRouteHandled()
        }
        if (np == null && focused) focused = false

        val openPlayer: () -> Unit = {
            if (!expanded) nav.navigate("player") { launchSingleTop = true }
        }
        val hideNav = expanded && focused && np != null
        // Unfolded, a book's page opens on the right instead of covering the library.
        var sideBookId by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(expanded) {
            val id = sideBookId
            if (!expanded && id != null) {
                sideBookId = null
                nav.navigate("book/$id")
            }
        }
        val openBook: (String) -> Unit = { id ->
            if (expanded) {
                focused = false
                sideBookId = id
            } else {
                nav.navigate("book/$id")
            }
        }
        // Episodes open their show; books open their page.
        val openItem: (String) -> Unit = { id ->
            if (id.startsWith(PlaybackService.EPISODE_PREFIX)) {
                vm.nowPlaying.value?.podcastId?.let { nav.navigate("podcast/$it") }
            } else {
                openBook(id)
            }
        }

        // Picking several books at once (started from a cover's long-press sheet).
        var selectedBooks by remember { mutableStateOf(emptySet<String>()) }
        LaunchedEffect(route) { selectedBooks = emptySet() }
        val selection = BookSelection(selectedBooks) { id ->
            selectedBooks = if (id in selectedBooks) selectedBooks - id else selectedBooks + id
        }

        val bottomCovered = !expanded && (route == null || compactTabs.any { it.route == route } || (np != null && route !in formRoutes))
        // Undo for one-tap changes, shown for a few seconds.
        val snackbar = remember { SnackbarHostState() }
        val undoOffer by vm.undo.collectAsState()
        LaunchedEffect(undoOffer) {
            val offer = undoOffer ?: return@LaunchedEffect
            val result = withTimeoutOrNull(MainViewModel.UNDO_WINDOW_MS) {
                snackbar.showSnackbar(offer.message, actionLabel = offer.actionLabel, duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) vm.undoLast(offer) else vm.dismissUndo(offer)
        }
        val addDrive: () -> Unit = {
            if (vm.driveStatus.value == app.ember.drive.DriveAuth.Status.CONNECTED) nav.navigate("drive_picker") else nav.navigate("settings/Library folders") { launchSingleTop = true }
        }

        // Long-press a cover anywhere for that book's settings, without opening the book.
        var quickBookId by remember { mutableStateOf<String?>(null) }
        CompositionLocalProvider(
            LocalBookMenu provides { id: String -> quickBookId = id },
            LocalBookSelection provides selection,
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(EmberColors.Background),
            ) {
                val carScreen = route == "car"
                if (expanded && !hideNav && !carScreen) {
                    NavigationRail(
                        containerColor = EmberColors.Background,
                        header = { Spacer(Modifier.height(12.dp)) },
                    ) {
                        railTabs.forEach { t ->
                            NavigationRailItem(
                                selected = route == t.route,
                                onClick = { nav.navigateTop(t.route) },
                                icon = { TabIcon(vm, t, route == t.route) },
                                label = { Text(tabLabel(t, savedFraction.profileName), maxLines = 1) },
                                colors = NavigationRailItemDefaults.colors(
                                    indicatorColor = if (t.route == "you") Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = EmberColors.TextMuted,
                                    unselectedTextColor = EmberColors.TextMuted,
                                ),
                            )
                        }
                    }
                }

                Column(
                    if (hideNav && !carScreen) Modifier.width(0.dp) else Modifier.weight(1f).fillMaxHeight(),
                ) {
                    Box(Modifier.weight(1f)) {
                        val docked = np
                        NavHost(navController = nav, startDestination = "home") {
                            composable("home") {
                                HomeScreen(
                                    vm = vm,
                                    compact = !expanded,
                                    onOpenBook = openBook,
                                    onOpenPlayer = openPlayer,
                                    onAddFolder = addFolder,
                                    onAddExternal = { nav.navigate("add_external") },
                                    onOpenSettings = { nav.navigate("settings") },
                                    onOpenCollection = { kind, key -> nav.navigate("collection/$kind/${Uri.encode(key)}") },
                                    onOpenLibrary = { nav.navigateTop("library") },
                                    onSearch = { nav.navigate("search") },
                                    onOpenClips = { nav.navigate("clips") },
                                    onOpenUpNext = { nav.navigate("upnext") },
                                )
                            }
                            composable("upnext") {
                                UpNextScreen(vm = vm, onBack = { nav.popBackStack() }, onOpenBook = openBook)
                            }
                            composable("library") {
                                LibraryScreen(
                                    vm = vm,
                                    onOpenBook = openBook,
                                    onOpenCollection = { kind, key -> nav.navigate("collection/$kind/${Uri.encode(key)}") },
                                    onAddFolder = addFolder,
                                    onAddExternal = { nav.navigate("add_external") },
                                    onAddDrive = addDrive,
                                    onAddTorrent = { nav.navigate("torrent_add") },
                                )
                            }
                            composable("podcasts") {
                                PodcastsScreen(
                                    vm = vm,
                                    onOpenShow = { nav.navigate("podcast/$it") },
                                    onDiscover = { nav.navigate("discover") },
                                    onShowSettings = { nav.navigate("show_settings/$it") },
                                )
                            }
                            composable(
                                "podcast/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                val id = e.arguments?.getString("id").orEmpty()
                                PodcastDetailScreen(
                                    vm = vm,
                                    podcastId = id,
                                    onBack = { nav.popBackStack() },
                                    onSettings = { nav.navigate("show_settings/$id") },
                                )
                            }
                            composable(
                                "show_settings/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                ShowSettingsScreen(
                                    vm = vm,
                                    podcastId = e.arguments?.getString("id").orEmpty(),
                                    onBack = { nav.popBackStack() },
                                    onUnfollowed = { nav.navigateTop("podcasts") },
                                )
                            }
                            composable(
                                "podcast_preview?url={url}&art={art}",
                                arguments = listOf(
                                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("art") { type = NavType.StringType; defaultValue = "" },
                                ),
                            ) { e ->
                                PodcastPreviewScreen(
                                    vm = vm,
                                    feedUrl = e.arguments?.getString("url").orEmpty(),
                                    artworkUrl = e.arguments?.getString("art").orEmpty(),
                                    onBack = { nav.popBackStack() },
                                    onFollowed = { id ->
                                        nav.popBackStack()
                                        nav.navigate("podcast/$id")
                                    },
                                )
                            }
                            composable("discover") {
                                DiscoverScreen(
                                    vm = vm,
                                    onBack = { nav.popBackStack() },
                                    onOpenShow = { nav.navigate("podcast/$it") },
                                    onPreview = { r -> nav.navigate("podcast_preview?url=${Uri.encode(r.feedUrl)}&art=${Uri.encode(r.artworkUrl)}") },
                                )
                            }
                            composable("drive_picker") {
                                DriveFolderPickerScreen(vm = vm, onDone = { nav.popBackStack() })
                            }
                            composable("you") {
                                app.ember.ui.screens.YouScreen(
                                    vm = vm,
                                    onOpenSettings = { section -> nav.navigate(if (section == null) "settings" else "settings/$section") { launchSingleTop = true } },
                                    onOpenStats = { nav.navigate("stats") { launchSingleTop = true } },
                                    onOpenClips = { nav.navigate("clips") },
                                    onOpenRecap = { nav.navigate("recap") },
                                    onOpenUpNext = { nav.navigate("upnext") },
                                    onOpenDownloads = { nav.navigate("downloads") },
                                    onOpenCar = { nav.navigate("car") },
                                )
                            }
                            composable("stats") {
                                StatsScreen(vm = vm, onOpenBook = openBook, onOpenRecap = { nav.navigate("recap") }, onBack = { nav.popBackStack() })
                            }
                            composable("settings") {
                                SettingsScreen(
                                    vm = vm,
                                    onBack = { nav.popBackStack() },
                                    onAddFolder = addFolder,
                                    onPickDriveFolder = { nav.navigate("drive_picker") },
                                    onOpenDownloads = { nav.navigate("downloads") },
                                    onOpenCar = { nav.navigate("car") },
                                )
                            }
                            composable("settings/{section}") { entry ->
                                SettingsScreen(
                                    vm = vm,
                                    onBack = { nav.popBackStack() },
                                    onAddFolder = addFolder,
                                    onPickDriveFolder = { nav.navigate("drive_picker") },
                                    onOpenDownloads = { nav.navigate("downloads") },
                                    onOpenCar = { nav.navigate("car") },
                                    section = entry.arguments?.getString("section"),
                                )
                            }
                            composable("car") {
                                CarModeScreen(vm = vm, onExit = { nav.popBackStack() })
                            }
                            composable("recap") {
                                RecapScreen(vm = vm, onBack = { nav.popBackStack() })
                            }
                            composable("torrent_add") {
                                TorrentAddScreen(
                                    vm = vm,
                                    onBack = { nav.popBackStack() },
                                    onStarted = {
                                        nav.popBackStack()
                                        nav.navigate("downloads")
                                    },
                                )
                            }
                            composable("downloads") {
                                DownloadsScreen(vm = vm, onBack = { nav.popBackStack() })
                            }
                            composable("clips") {
                                ClipsScreen(vm = vm, onBack = { nav.popBackStack() }, onOpenCard = { nav.navigate("card/clip/$it") })
                            }
                            composable(
                                "search?q={q}",
                                arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" }),
                            ) { e ->
                                SearchScreen(
                                    vm = vm,
                                    initialQuery = e.arguments?.getString("q").orEmpty(),
                                    onBack = { nav.popBackStack() },
                                    onOpenBook = openBook,
                                    onOpenCollection = { kind, key -> nav.navigate("collection/$kind/${Uri.encode(key)}") },
                                    onOpenShow = { nav.navigate("podcast/$it") },
                                    onOpenCard = { nav.navigate("card/clip/$it") },
                                )
                            }
                            composable(
                                "card/{kind}/{id}",
                                arguments = listOf(
                                    navArgument("kind") { type = NavType.StringType },
                                    navArgument("id") { type = NavType.LongType },
                                ),
                            ) { e ->
                                QuoteCardScreen(
                                    vm = vm,
                                    kind = e.arguments?.getString("kind").orEmpty(),
                                    id = e.arguments?.getLong("id") ?: 0L,
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable(
                                "book/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                val id = e.arguments?.getString("id").orEmpty()
                                BookDetailScreen(
                                    vm = vm,
                                    bookId = id,
                                    onBack = { nav.popBackStack() },
                                    onEdit = { nav.navigate("edit/$it") },
                                    onEditChapters = { nav.navigate("chapters/$it") },
                                    onOpenCollection = { kind, key -> nav.navigate("collection/$kind/${Uri.encode(key)}") },
                                    onPlayed = openPlayer,
                                    onOpenRoute = { nav.navigate(it) },
                                )
                            }
                            composable(
                                "pdf/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                PdfScreen(vm = vm, bookId = e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                            }
                            composable(
                                "edit/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                EditBookScreen(vm = vm, bookId = e.arguments?.getString("id").orEmpty(), onDone = { nav.popBackStack() })
                            }
                            composable(
                                "chapters/{id}",
                                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                            ) { e ->
                                ChapterEditorScreen(vm = vm, bookId = e.arguments?.getString("id").orEmpty(), onDone = { nav.popBackStack() })
                            }
                            composable("add_external") {
                                AddExternalScreen(vm = vm, onDone = { id ->
                                    nav.popBackStack()
                                    if (id != null) nav.navigate("book/$id")
                                })
                            }
                            composable(
                                "collection/{kind}/{key}",
                                arguments = listOf(
                                    navArgument("kind") { type = NavType.StringType },
                                    navArgument("key") { type = NavType.StringType },
                                ),
                            ) { e ->
                                CollectionScreen(
                                    vm = vm,
                                    kind = e.arguments?.getString("kind").orEmpty(),
                                    key = e.arguments?.getString("key").orEmpty(),
                                    onBack = { nav.popBackStack() },
                                    onOpenBook = openBook,
                                )
                            }
                            composable("player") {
                                PlayerScreen(
                                    vm = vm,
                                    embedded = false,
                                    onCollapse = { nav.popBackStack() },
                                    onOpenBook = openItem,
                                    onOpenRoute = { nav.navigate(it) },
                                )
                            }
                        }
                        val shownIssue = issue
                        if (expanded && shownIssue != null) {
                            IssueBanner(
                                shownIssue,
                                onAction = onIssueAction,
                                onDismiss = vm::dismissPlaybackIssue,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 8.dp),
                            )
                        }
                        if (selectedBooks.isNotEmpty()) {
                            BookSelectionBar(
                                vm = vm,
                                ids = selectedBooks,
                                onSelectAll = if (route == "library") ({ selectedBooks = vm.books.value.map { it.id }.toSet() }) else null,
                                onClear = { selectedBooks = emptySet() },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .widthIn(max = 640.dp),
                            )
                        }
                        SnackbarHost(
                            snackbar,
                            modifier = Modifier
                                .align(if (expanded) Alignment.BottomStart else Alignment.BottomCenter)
                                // Clear of the system bar unless the tab bar or mini player is already below.
                                .then(if (bottomCovered) Modifier else Modifier.navigationBarsPadding())
                                .widthIn(max = 520.dp)
                                .padding(8.dp),
                        )
                        if (expanded && (minimized || sideBookId != null) && docked != null) {
                            DockedMiniPlayer(
                                np = docked,
                                onExpand = {
                                    minimized = false
                                    sideBookId = null
                                },
                                onToggle = vm::togglePlay,
                                onSleep = { miniSleep = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .navigationBarsPadding()
                                    .padding(16.dp),
                            )
                        }
                    }

                    if (!expanded) {
                        issue?.let { shownIssue ->
                            IssueBanner(shownIssue, onAction = onIssueAction, onDismiss = vm::dismissPlaybackIssue)
                        }
                        val current = np
                        val heroShown by vm.homeHeroShown.collectAsState()
                        AnimatedVisibility(
                            visible = current != null && route !in formRoutes && !(route == "home" && heroShown) && !playerOnScreen,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut(),
                        ) {
                            if (current != null) {
                                MiniPlayer(
                                    current,
                                    onOpen = openPlayer,
                                    onToggle = vm::togglePlay,
                                    onSpeed = { miniSpeed = true },
                                    onSleep = { miniSleep = true },
                                    sleepActive = sleepState.active,
                                    sleep = sleepState,
                                    skipBackSec = savedFraction.skipBackSec,
                                    skipForwardSec = savedFraction.skipForwardSec,
                                    onSkipBack = vm::skipBack,
                                    onSkipForward = vm::skipForward,
                                )
                            }
                        }
                        val onTab = route == null || compactTabs.any { it.route == route }
                        if (!onTab && current != null && route !in formRoutes) {
                            // No tab bar here: keep the mini player clear of the system navigation bar.
                            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                        }
                        if (onTab && !playerOnScreen) {
                            NavigationBar(containerColor = EmberColors.Background, tonalElevation = 0.dp) {
                                compactTabs.forEach { t ->
                                    NavigationBarItem(
                                        selected = route == t.route,
                                        onClick = { nav.navigateTop(t.route) },
                                        icon = { TabIcon(vm, t, route == t.route) },
                                        label = { Text(tabLabel(t, savedFraction.profileName), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        colors = NavigationBarItemDefaults.colors(
                                            indicatorColor = if (t.route == "you") Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = EmberColors.TextMuted,
                                            unselectedTextColor = EmberColors.TextMuted,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                val current = np
                val detailId = if (expanded) sideBookId else null
                if (carScreen) {
                    // Car mode takes the whole screen.
                } else if (detailId != null) {
                    // The book's page, beside whatever list it was opened from.
                    VerticalDivider(color = EmberColors.Wash.copy(alpha = 0.10f))
                    Box(
                        Modifier
                            .width((screenW * 0.45f).coerceIn(340f, maxOf(340f, screenW * 0.55f)).dp)
                            .fillMaxHeight()
                            .clipToBounds(),
                    ) {
                        androidx.compose.runtime.key(detailId) {
                            BookDetailScreen(
                                vm = vm,
                                bookId = detailId,
                                onBack = { sideBookId = null },
                                onEdit = { nav.navigate("edit/$it") },
                                onEditChapters = { nav.navigate("chapters/$it") },
                                onOpenCollection = { kind, key -> nav.navigate("collection/$kind/${Uri.encode(key)}") },
                                onPlayed = {
                                    sideBookId = null
                                    minimized = false
                                },
                                onOpenRoute = { nav.navigate(it) },
                            )
                        }
                    }
                } else if (expanded && current != null && !minimized) {
                    if (!hideNav) {
                        PaneResizeHandle(
                            onDrag = { deltaDp ->
                                val current = paneWidth.value
                                paneFraction = ((current - deltaDp) / screenW).coerceIn(260f / screenW, 0.6f)
                            },
                            onDragEnd = { vm.setPlayerPaneFraction(paneFraction) },
                        )
                    }
                    Box(
                        (if (hideNav) Modifier.weight(1f) else Modifier.width(paneWidth))
                            .fillMaxHeight()
                            .clipToBounds(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayerScreen(
                            vm = vm,
                            embedded = true,
                            onCollapse = null,
                            onOpenBook = {
                                focused = false
                                openItem(it)
                            },
                            focused = focused,
                            onToggleFocus = { focused = !focused },
                            onMinimize = {
                                focused = false
                                minimized = true
                            },
                            onOpenRoute = {
                                focused = false
                                nav.navigate(it)
                            },
                        )
                    }
                }
            }
        }
        // Back closes the side page or ends selecting first. These come after the screens
        // so they take priority over the screens' own Back.
        BackHandler(enabled = expanded && sideBookId != null) { sideBookId = null }
        BackHandler(enabled = selectedBooks.isNotEmpty()) { selectedBooks = emptySet() }

        // Half-folded like a laptop: the tabletop player, until you choose the library.
        val table by Posture.tabletop.collectAsState()
        val forcedTable by Posture.forced.collectAsState()
        val posture = table ?: if (forcedTable) Posture.Tabletop(0.5f, 0.5f) else null
        var tableDismissed by remember(posture != null) { mutableStateOf(false) }
        if (posture != null && np != null && !tableDismissed && route != "car") {
            BackHandler { tableDismissed = true }
            app.ember.ui.screens.TabletopPlayer(vm, posture) { tableDismissed = true }
        }

        // Pocket lock: nothing on screen responds until you press and hold.
        val locked by vm.locked.collectAsState()
        BackHandler(enabled = locked) {}
        if (locked) PocketLockOverlay { vm.setLocked(false) }

        // Car mode opens when the car connects (and closes when it disconnects).
        LaunchedEffect(Unit) {
            app.ember.playback.CarMode.open.collect { t ->
                if (t != null) {
                    app.ember.playback.CarMode.open.value = null
                    if (nav.currentDestination?.route != "car") nav.navigate("car") { launchSingleTop = true }
                }
            }
        }
        LaunchedEffect(Unit) {
            app.ember.playback.CarMode.close.collect { t ->
                if (t != null) {
                    app.ember.playback.CarMode.close.value = null
                    if (nav.currentDestination?.route == "car") nav.popBackStack()
                }
            }
        }

        // Finished a book: rate it, log it.
        // Never while driving: in car mode it waits until car mode closes.
        val finishedId by vm.finishedPrompt.collectAsState()
        finishedId?.takeIf { route != "car" }?.let { id ->
            FinishedSheet(
                vm = vm,
                bookId = id,
                onDismiss = vm::dismissFinishedPrompt,
                onTurnOff = {
                    vm.setRateOnFinish(false)
                    vm.dismissFinishedPrompt()
                },
            )
        }

        var infoBookId by remember { mutableStateOf<String?>(null) }
        infoBookId?.let { id -> BookInfoSheet(vm, id) { infoBookId = null } }

        quickBookId?.let { id ->
            BookQuickSheet(
                vm = vm,
                bookId = id,
                onDismiss = { quickBookId = null },
                onOpenBook = { openBook(id) },
                onEdit = { nav.navigate("edit/$id") },
                onEditChapters = { nav.navigate("chapters/$id") },
                onPlayed = {},
                onSelect = { selectedBooks = setOf(id) },
                onOpenPdf = { nav.navigate("pdf/$id") },
                onFindInfo = { infoBookId = id },
            )
        }

        val playing = np
        if (miniSpeed && playing != null) {
            SpeedSheet(playing.speed, onDismiss = { miniSpeed = false }, onSpeed = vm::setSpeed)
        }
        if (miniSleep) {
            SleepSheet(
                sleep = sleepState,
                onDismiss = { miniSleep = false },
                onMinutes = {
                    vm.sleepFor(it)
                    miniSleep = false
                },
                onEndOfChapter = {
                    vm.sleepEndOfChapter()
                    miniSleep = false
                },
                onCancel = {
                    vm.cancelSleep()
                    miniSleep = false
                },
            )
        }
    }
}

/**
 * Tabs always land on their own top screen. (Previously tabs restored a remembered
 * screen trail, which could replay a Settings screen when tapping Home.)
 */
private fun NavHostController.navigateTop(route: String) {
    val homeId = graph.findStartDestination().id
    if (route == "home") {
        if (!popBackStack(homeId, inclusive = false)) navigate("home")
        return
    }
    navigate(route) {
        popUpTo(homeId)
        launchSingleTop = true
    }
}

/** Grab handle between the library and the player. Drag left to widen the player. */
@Composable
private fun PaneResizeHandle(onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .width(14.dp)
            .fillMaxHeight()
            .background(EmberColors.Background)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { px -> onDrag(with(density) { px.toDp().value }) },
                onDragStopped = { onDragEnd() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(EmberColors.TextFaint),
        )
    }
}

