package com.smartstudent.planner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.remote.TokenStore
import com.smartstudent.planner.data.repo.AuthRepository
import com.smartstudent.planner.data.repo.SyncManager
import com.smartstudent.planner.ui.common.BarsIcon
import com.smartstudent.planner.ui.habits.HabitsScreen
import com.smartstudent.planner.ui.home.HomeScreen
import com.smartstudent.planner.ui.profile.ProfilePage
import com.smartstudent.planner.ui.profile.ProfileScreen
import com.smartstudent.planner.ui.progress.ProgressScreen
import com.smartstudent.planner.ui.settings.SettingsScreen
import com.smartstudent.planner.ui.tasks.TasksScreen
import com.smartstudent.planner.ui.timetable.TimetableScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Owns app-wide things: syncing, the "offline" message and signing out. */
class ShellViewModel(
    private val syncManager: SyncManager,
    private val auth: AuthRepository,
    tokenStore: TokenStore,
) : ViewModel() {

    val userName: StateFlow<String?> =
        tokenStore.userName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** True when a sync is taking long: free hosting sleeps when idle and needs about a minute to wake. */
    private val _slowHint = MutableStateFlow(false)
    val slowHint: StateFlow<Boolean> = _slowHint

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun messageShown() {
        _message.value = null
    }

    fun sync() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            val hint = launch {
                delay(4_000)
                _slowHint.value = true
            }
            syncManager.sync().onFailure { e ->
                _message.value = when (e) {
                    is java.io.IOException ->
                        "Can't reach the server. Your changes are saved and will sync when you're connected."
                    is retrofit2.HttpException ->
                        "Server problem (${e.code()}). Your changes are saved and will sync later."
                    else -> "Sync failed. Your changes are saved and will sync later."
                }
            }
            hint.cancel()
            _slowHint.value = false
            _syncing.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            syncManager.sync() // best effort: don't lose unsynced changes
            auth.signOut()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShellViewModel(container.syncManager, container.authRepository, container.tokenStore)
            }
        }
    }
}

private enum class Tab(val label: String) {
    HOME("Home"),
    TASKS("Tasks"),
    PROGRESS("Progress"),
    PROFILE("Profile"),
}

/** What the centre "+" button was used for; the target screen opens its add dialog. */
private enum class AddKind { TASK, HABIT, CLASS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(container: AppContainer) {
    val shell: ShellViewModel = viewModel(factory = ShellViewModel.factory(container))
    val syncing by shell.syncing.collectAsState()
    val slowHint by shell.slowHint.collectAsState()
    val message by shell.message.collectAsState()
    val userName by shell.userName.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var profilePage by rememberSaveable { mutableStateOf(ProfilePage.ROOT) }
    var addRequest by remember { mutableStateOf<AddKind?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    // Pull the latest from other devices whenever the app comes to the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { shell.sync() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it)
            shell.messageShown()
        }
    }

    // Back from a Profile sub-page returns to the Profile list.
    BackHandler(enabled = tab == Tab.PROFILE && profilePage != ProfilePage.ROOT) {
        profilePage = ProfilePage.ROOT
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            PlannerBottomBar(
                selected = tab,
                onSelect = {
                    tab = it
                    if (it == Tab.PROFILE) profilePage = ProfilePage.ROOT
                },
                onAdd = { showAddMenu = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (syncing && slowHint) {
                Text(
                    "Waking up the server… this can take up to a minute the first time.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }

            when (tab) {
                Tab.HOME -> HomeScreen(
                    container,
                    userName = userName,
                    onSync = shell::sync,
                    onOpenProfile = {
                        tab = Tab.PROFILE
                        profilePage = ProfilePage.ROOT
                    },
                    onViewAllTasks = { tab = Tab.TASKS },
                )
                Tab.TASKS -> TasksScreen(
                    container,
                    addRequested = addRequest == AddKind.TASK,
                    onAddConsumed = { addRequest = null },
                    onSync = shell::sync,
                )
                Tab.PROGRESS -> ProgressScreen(container)
                Tab.PROFILE -> when (profilePage) {
                    ProfilePage.ROOT -> ProfileScreen(
                        userName = userName,
                        onOpen = { profilePage = it },
                        onSignOut = shell::signOut,
                    )
                    ProfilePage.ROUTINE -> SettingsScreen(container, onBack = { profilePage = ProfilePage.ROOT })
                    ProfilePage.TIMETABLE -> TimetableScreen(
                        container,
                        onBack = { profilePage = ProfilePage.ROOT },
                        addRequested = addRequest == AddKind.CLASS,
                        onAddConsumed = { addRequest = null },
                    )
                    ProfilePage.HABITS -> HabitsScreen(
                        container,
                        onBack = { profilePage = ProfilePage.ROOT },
                        addRequested = addRequest == AddKind.HABIT,
                        onAddConsumed = { addRequest = null },
                    )
                }
            }
        }
    }

    if (showAddMenu) {
        ModalBottomSheet(onDismissRequest = { showAddMenu = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add something", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                AddMenuRow(Icons.Default.Check, "New task", "Something to get done") {
                    showAddMenu = false
                    tab = Tab.TASKS
                    addRequest = AddKind.TASK
                }
                AddMenuRow(Icons.Default.Refresh, "New habit", "Repeats every day or week") {
                    showAddMenu = false
                    tab = Tab.PROFILE
                    profilePage = ProfilePage.HABITS
                    addRequest = AddKind.HABIT
                }
                AddMenuRow(Icons.Default.DateRange, "New class", "A fixed slot in your weekly timetable") {
                    showAddMenu = false
                    tab = Tab.PROFILE
                    profilePage = ProfilePage.TIMETABLE
                    addRequest = AddKind.CLASS
                }
            }
        }
    }
}

@Composable
private fun AddMenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = scheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface)
            Text(subtitle, fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
    }
}

/** Home, Tasks, a round "+" in the middle, Progress, Profile. */
@Composable
private fun PlannerBottomBar(selected: Tab, onSelect: (Tab) -> Unit, onAdd: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 10.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarItem(Tab.HOME, selected, onSelect, Modifier.weight(1f)) { tint -> Icon(Icons.Default.Home, null, tint = tint) }
            BarItem(Tab.TASKS, selected, onSelect, Modifier.weight(1f)) { tint -> Icon(Icons.Default.Check, null, tint = tint) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = onAdd,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, "Add", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            BarItem(Tab.PROGRESS, selected, onSelect, Modifier.weight(1f)) { tint -> BarsIcon(tint) }
            BarItem(Tab.PROFILE, selected, onSelect, Modifier.weight(1f)) { tint -> Icon(Icons.Default.Person, null, tint = tint) }
        }
    }
}

@Composable
private fun BarItem(
    tab: Tab,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val active = tab == selected
    val tint = if (active) scheme.primary else scheme.onSurfaceVariant
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable { onSelect(tab) }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon(tint)
        Text(tab.label, fontSize = 11.sp, color = tint, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(4.dp).clip(CircleShape).background(if (active) scheme.primary else scheme.surface))
    }
}
