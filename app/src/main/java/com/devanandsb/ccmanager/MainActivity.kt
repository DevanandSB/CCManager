package com.devanandsb.ccmanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

import com.devanandsb.ccmanager.ingest.StatementSync
import com.devanandsb.ccmanager.reminders.Notifications
import com.devanandsb.ccmanager.security.Auth
import com.devanandsb.ccmanager.statementparser.presentation.StatementParserScreen
import com.devanandsb.ccmanager.ui.Appearance
import com.devanandsb.ccmanager.ui.ArchivedScreen
import com.devanandsb.ccmanager.ui.BillRemindersScreen
import com.devanandsb.ccmanager.ui.MailScreen
import com.devanandsb.ccmanager.ui.PasswordsScreen
import com.devanandsb.ccmanager.ui.StatementScreen
import com.devanandsb.ccmanager.ui.LocalLocked
import com.devanandsb.ccmanager.ui.LocalNavAnimatedScope
import com.devanandsb.ccmanager.ui.LocalOwnerName
import com.devanandsb.ccmanager.ui.LocalPersonalizeOwner
import com.devanandsb.ccmanager.ui.LocalReplaySplash
import com.devanandsb.ccmanager.ui.LocalSharedTransitionScope
import com.devanandsb.ccmanager.ui.LocalStatementLoading
import com.devanandsb.ccmanager.ui.OwnerResolution
import com.devanandsb.ccmanager.ui.PersonalizeOwnerSheet
import com.devanandsb.ccmanager.ui.resolveMobileOwnerName
import com.devanandsb.ccmanager.ui.resolveMobileOwnerNameAsync
import com.devanandsb.ccmanager.ui.setCustomOwnerOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.devanandsb.ccmanager.ui.DetailScreen
import com.devanandsb.ccmanager.ui.CardsTheme
import com.devanandsb.ccmanager.ui.EditScreen
import com.devanandsb.ccmanager.ui.FIXABLE
import com.devanandsb.ccmanager.ui.importUri
import com.devanandsb.ccmanager.ui.rememberPdfImporterForCard
import com.devanandsb.ccmanager.ui.HomeScreen
import com.devanandsb.ccmanager.ui.LockScreen
import com.devanandsb.ccmanager.ui.OnboardingExit
import com.devanandsb.ccmanager.ui.OnboardingScreen
import com.devanandsb.ccmanager.ui.SettingsScreen
import com.devanandsb.ccmanager.ui.SplashOverlay
import com.devanandsb.ccmanager.ui.StatementLoadingController
import com.devanandsb.ccmanager.ui.StatementParsingOverlay
import com.devanandsb.ccmanager.ui.UploadUnlockStatementScreen
import com.devanandsb.ccmanager.ui.VaultSecurityModal
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(true)
    private var deviceSecure by mutableStateOf(true)
    private var pendingCard by mutableStateOf<Long?>(null)
    private var pendingCardUpload by mutableStateOf(false)
    private var pendingStatement by mutableStateOf<Long?>(null)
    private var pendingImport by mutableStateOf<android.net.Uri?>(null)
    private var fixKey by mutableStateOf<String?>(null)
    private var pendingAdd by mutableStateOf<String?>(null) // from a shortcut: SHORTCUT_ADD, "scan" or "tap"
    private var backgroundedAt = 0L
    private var pickingAccount = false

    companion object {
        const val ACTION_ADD_CARD = "com.devanandsb.ccmanager.action.ADD_CARD"
        const val ACTION_SCAN_CARD = "com.devanandsb.ccmanager.action.SCAN_CARD"
        const val ACTION_TAP_CARD = "com.devanandsb.ccmanager.action.TAP_CARD"
        private const val SHORTCUT_ADD = "add"
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No screenshots, no preview in recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val app = CardsApp.from(this)
        Appearance.paletteId = app.prefs.palette
        Appearance.mode = app.prefs.themeMode
        deviceSecure = Auth.deviceSecure(this)
        // Nothing to protect before the first-run screens are done, so no lock on a fresh install.
        locked = deviceSecure && app.prefs.onboarded && app.prefs.appLockEnabled
        // Recreated (rotation, process death): the launch intent was already handled, don't open Add card again.
        handleIntent(intent, shortcuts = savedInstanceState == null)

        setContent {
            CardsTheme {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val cards by app.repo.cards.collectAsStateWithLifecycle(initialValue = null)
                val everyCard by app.repo.everyCard.collectAsStateWithLifecycle(initialValue = emptyList())
                val archived by app.repo.archived.collectAsStateWithLifecycle(initialValue = emptyList())
                val payments by app.repo.payments.collectAsStateWithLifecycle(initialValue = emptyList())

                val ledger = app.repo.ledger
                val statements by ledger.statements.collectAsStateWithLifecycle(initialValue = emptyList())
                val txns by ledger.txns.collectAsStateWithLifecycle(initialValue = emptyList())
                val categories by ledger.categories.collectAsStateWithLifecycle(initialValue = emptyList())
                val mailAccounts by ledger.mailSources.collectAsStateWithLifecycle(initialValue = emptyList())
                val connectedMailAccounts = androidx.compose.runtime.remember(mailAccounts) {
                    mailAccounts.filter { it.passwordEnc != null && it.user.isNotBlank() }
                }
                var showSplash by rememberSaveable { mutableStateOf(true) }
                var onboarded by androidx.compose.runtime.remember { mutableStateOf(app.prefs.onboarded) }
                LaunchedEffect(everyCard) {
                    if (!onboarded && everyCard.isNotEmpty()) { app.prefs.onboarded = true; onboarded = true }
                }
                var ownerRefreshTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                var personalizeOwnerSheet by androidx.compose.runtime.remember { mutableStateOf(false) }
                androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    ownerRefreshTick++
                }
                var ownerState by androidx.compose.runtime.remember {
                    val hasCustom = com.devanandsb.ccmanager.ui.getCustomOwnerOverride(this@MainActivity) != null
                    mutableStateOf(
                        OwnerResolution(
                            resolveMobileOwnerName(this@MainActivity),
                            isPersonalized = hasCustom,
                            source = if (hasCustom) "Entered by you" else "Default greeting",
                        ),
                    )
                }
                LaunchedEffect(ownerRefreshTick) {
                    ownerState = resolveMobileOwnerNameAsync(this@MainActivity)
                }
                var onGmailPicked by androidx.compose.runtime.remember { mutableStateOf<((String) -> Unit)?>(null) }
                val accountPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                    pickingAccount = false
                    val picked = res.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
                    val gmailCb = onGmailPicked
                    onGmailPicked = null
                    if (!picked.isNullOrBlank()) {
                        gmailCb?.invoke(picked)
                    }
                }
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    ownerRefreshTick++
                }
                LaunchedEffect(locked, cards?.size) {
                    if (!locked && Build.VERSION.SDK_INT >= 33 && !cards.isNullOrEmpty() && !app.prefs.askedNotifications &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        app.prefs.askedNotifications = true
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(locked, pendingCard) {
                    val id = pendingCard
                    if (!locked && id != null) {
                        val openUpload = pendingCardUpload
                        pendingCard = null
                        pendingCardUpload = false
                        nav.navigate(if (openUpload) "card/$id?upload=true" else "card/$id") { launchSingleTop = true }
                    }
                }
                val importLog by ledger.importLog.collectAsStateWithLifecycle(initialValue = emptyList())
                val sync by StatementSync.state.collectAsStateWithLifecycle()
                val runSync: () -> Unit = {
                    scope.launch {
                        val profileSrc = withContext(Dispatchers.IO) { ledger.profileSourceOnce() }
                        val needsUnlock = withContext(Dispatchers.IO) {
                            ledger.profileRequiresAuth(profileSrc) && runCatching { ledger.profileOrThrow(profileSrc) }.isFailure
                        }
                        if (needsUnlock && deviceSecure) {
                            Auth.withVault(this@MainActivity, "Unlock saved statement passwords for sync") {
                                withContext(Dispatchers.IO) { ledger.profileOrThrow(profileSrc) }
                                true
                            }
                        }
                        app.scope.launch { StatementSync.run(app) }
                    }
                }
                val pickGoogleAccount: ((String) -> Unit) -> Unit = { cb ->
                    pickingAccount = true
                    onGmailPicked = cb
                    @Suppress("DEPRECATION")
                    val intent = android.accounts.AccountManager.newChooseAccountIntent(
                        null, null, arrayOf("com.google"), null, null, null, null,
                    )
                    runCatching { accountPickerLauncher.launch(intent) }
                        .onFailure {
                            pickingAccount = false
                            onGmailPicked = null
                        }
                }
                val pendingKeys by ledger.pendingKeys.collectAsStateWithLifecycle(initialValue = emptyList())
                val needsYou = importLog.filter { it.outcome in FIXABLE && it.key in pendingKeys }

                // After a manual or shared import: open the statement, or the Upload & Unlock screen.
                val onImported: (StatementSync.Outcome, Long?) -> Unit = { o, cid ->
                    when {
                        o.statementId != null -> {
                            android.widget.Toast.makeText(this@MainActivity, o.message, android.widget.Toast.LENGTH_LONG).show()
                            nav.navigate("statement/${o.statementId}")
                        }
                        o.code in FIXABLE -> {
                            val encodedKey = android.net.Uri.encode(o.key)
                            val needPick = o.code == "unmatched"
                            nav.navigate("upload_unlock/$encodedKey?cardId=${cid ?: 0L}&unmatched=$needPick")
                        }
                        else -> {
                            android.widget.Toast.makeText(this@MainActivity, o.message, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
                val onMailFixImported: (StatementSync.Outcome) -> Unit = { o ->
                    android.widget.Toast.makeText(this@MainActivity, o.message, android.widget.Toast.LENGTH_LONG).show()
                    when {
                        o.statementId != null -> nav.navigate("statement/${o.statementId}")
                        o.code in FIXABLE -> { fixKey = o.key; nav.navigate("mail") { launchSingleTop = true } }
                    }
                }
                val statementLoading = androidx.compose.runtime.remember { StatementLoadingController() }
                var showVaultModal by androidx.compose.runtime.remember { mutableStateOf(false) }
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalReplaySplash provides { showVaultModal = true },
                    LocalOwnerName provides ownerState.name,
                    LocalPersonalizeOwner provides { personalizeOwnerSheet = true },
                    LocalStatementLoading provides statementLoading,
                ) {
                val importPdfForCard = rememberPdfImporterForCard(onImported)
                LaunchedEffect(locked, pendingImport) {
                    val uri = pendingImport
                    if (!locked && uri != null) {
                        pendingImport = null
                        onImported(importUri(this@MainActivity, uri, this@MainActivity, scope, statementLoading), null)
                    }
                }

                // App shortcuts: open Add card right away, behind the lock if it's up (EditScreen waits for
                // unlock before it starts the camera or NFC).
                LaunchedEffect(pendingAdd) {
                    val start = pendingAdd ?: return@LaunchedEffect
                    pendingAdd = null
                    nav.navigate(if (start == SHORTCUT_ADD) "edit/0" else "edit/0?start=$start")
                }

                LaunchedEffect(locked, pendingStatement) {
                    val id = pendingStatement
                    if (!locked && id != null) {
                        pendingStatement = null
                        nav.navigate("statement/$id") { launchSingleTop = true }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // Fluid 120Hz iOS quint-out slide + soft parallax fade for screen transitions.
                    val ease = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                    val slide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(280, easing = ease)
                    val fade = androidx.compose.animation.core.tween<Float>(240, easing = ease)
                    // While locked the screens stay composed (so navigation and screen state survive), but
                    // they're hidden from accessibility, and sheets/dialogs/menus keep out via LocalLocked.
                    androidx.compose.runtime.CompositionLocalProvider(LocalLocked provides locked) {
                    Box(Modifier.fillMaxSize().then(if (locked) Modifier.clearAndSetSemantics {} else Modifier)) {
                    // Lets a card face be the same element on Home and Detail (Modifier.sharedCard).
                    SharedTransitionLayout(Modifier.fillMaxSize()) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout) {
                    NavHost(
                        nav, startDestination = "home",
                        enterTransition = {
                            androidx.compose.animation.slideInHorizontally(slide) { it } + androidx.compose.animation.fadeIn(fade)
                        },
                        exitTransition = {
                            androidx.compose.animation.slideOutHorizontally(slide) { -it / 4 } + androidx.compose.animation.fadeOut(fade)
                        },
                        popEnterTransition = { androidx.compose.animation.slideInHorizontally(slide) { -it / 4 } + androidx.compose.animation.fadeIn(fade) },
                        popExitTransition = { androidx.compose.animation.slideOutHorizontally(slide) { it } + androidx.compose.animation.fadeOut(fade) },
                    ) {
                        composable("home") {
                            androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                                HomeScreen(
                                    cards = cards,
                                    deviceSecure = deviceSecure,
                                    onOpen = { nav.navigate("card/$it") },
                                    onAdd = { nav.navigate("edit/0") },
                                    onAddWith = { start -> nav.navigate(start?.let { "edit/0?start=$it" } ?: "edit/0") },
                                    onEdit = { nav.navigate("edit/$it") },
                                    onSettings = { nav.navigate("settings") },
                                    onPin = { c, pin, replacing -> app.repo.setPinned(c, pin, replacing) },
                                )
                            }
                        }
                        composable(
                            "card/{id}?upload={upload}",
                            arguments = listOf(
                                navArgument("id") { type = NavType.LongType },
                                navArgument("upload") { type = NavType.BoolType; defaultValue = false },
                            ),
                        ) { entry ->
                            val id = entry.arguments?.getLong("id") ?: 0L
                            val openUpload = entry.arguments?.getBoolean("upload") ?: false
                            androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                            DetailScreen(
                                cw = everyCard.firstOrNull { it.card.id == id },
                                payments = payments,
                                repo = app.repo,
                                statements = statements,
                                openUpload = openUpload,
                                onOpenStatement = { nav.navigate("statement/$it") },
                                onImport = { importPdfForCard(id) },
                                onBack = { nav.popBackStack() },
                                onEdit = { nav.navigate("edit/$id") },
                            )
                            }
                        }
                        composable("statement/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            StatementScreen(
                                statementId = entry.arguments?.getLong("id") ?: 0L,
                                ledger = ledger,
                                repo = app.repo,
                                cards = everyCard,
                                categories = categories,
                                statements = statements,
                                payments = payments,
                                liveTxns = txns.filter { it.statementId == null },
                                onBack = { nav.popBackStack() },
                                // Replace this statement rather than stacking: back still returns to where you came from.
                                onOpenStatement = { id -> nav.navigate("statement/$id") { popUpTo("statement/{id}") { inclusive = true } } },
                            )
                        }
                        composable(
                            "upload_unlock/{key}?cardId={cardId}&unmatched={unmatched}",
                            arguments = listOf(
                                navArgument("key") { type = NavType.StringType },
                                navArgument("cardId") { type = NavType.LongType; defaultValue = 0L },
                                navArgument("unmatched") { type = NavType.BoolType; defaultValue = false },
                            ),
                        ) { entry ->
                            val key = entry.arguments?.getString("key").orEmpty()
                            val cid = entry.arguments?.getLong("cardId")?.takeIf { it > 0L }
                            val needPick = entry.arguments?.getBoolean("unmatched") ?: false
                            UploadUnlockStatementScreen(
                                pendingKey = key,
                                preferredCardId = cid,
                                initialNeedsCardPick = needPick,
                                ledger = ledger,
                                cards = everyCard.map { it.card },
                                onBack = { nav.popBackStack() },
                                onImported = { out ->
                                    android.widget.Toast.makeText(this@MainActivity, out.message, android.widget.Toast.LENGTH_LONG).show()
                                    if (out.statementId != null) {
                                        nav.navigate("statement/${out.statementId}") {
                                            popUpTo("upload_unlock/{key}?cardId={cardId}&unmatched={unmatched}") { inclusive = true }
                                        }
                                    }
                                },
                            )
                        }
                        composable("mail") {
                            MailScreen(
                                ledger = ledger,
                                log = importLog,
                                cards = cards.orEmpty().map { it.card },
                                statements = statements,
                                needsYou = needsYou,
                                fixKey = fixKey,
                                onFixKeyConsumed = { fixKey = null },
                                onImport = { importPdfForCard(null) },
                                onImported = onMailFixImported,
                                onOpenStatement = { nav.navigate("statement/$it") },
                                onPasswords = { nav.navigate("passwords") },
                                sync = sync,
                                onSync = runSync,
                                onPickGoogleAccount = pickGoogleAccount,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        // "start" (optional): "scan" or "tap" opens the camera / NFC sheet once the screen is shown (app shortcuts).
                        composable(
                            "edit/{id}?start={start}",
                            arguments = listOf(
                                navArgument("id") { type = NavType.LongType },
                                navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { entry ->
                            EditScreen(
                                cardId = entry.arguments?.getLong("id") ?: 0L,
                                repo = app.repo,
                                defaultLeads = app.prefs.defaultLeads,
                                onDone = { nav.popBackStack() },
                                startWith = entry.arguments?.getString("start"),
                                // Duplicate found: leave the form and show the card already saved.
                                onOpenCard = { id ->
                                    nav.popBackStack()
                                    nav.navigate("card/$id") { launchSingleTop = true }
                                },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                app.prefs,
                                mailAccounts = connectedMailAccounts,
                                mailSyncRunning = sync.running,
                                onSyncMail = runSync,
                                onMail = { nav.navigate("mail") },
                                onPasswords = { nav.navigate("passwords") },
                                onBillReminders = { nav.navigate("bill_reminders") },
                                onStatementParser = { nav.navigate("statement_parser") },
                                onBack = { nav.popBackStack() },
                                onArchived = { nav.navigate("archived") },
                                archivedCount = archived.size,
                                onDataWiped = {
                                    ownerRefreshTick++
                                    nav.popBackStack("home", inclusive = false)
                                },
                            )
                        }
                        composable("statement_parser") {
                            StatementParserScreen(onBack = { nav.popBackStack() })
                        }
                        composable("passwords") {
                            PasswordsScreen(
                                ledger = ledger,
                                cards = everyCard.map { it.card },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("bill_reminders") {
                            val sample = everyCard.firstOrNull()
                            val sampleStmt = sample?.let { cw ->
                                statements.filter { it.cardId == cw.card.id }.maxByOrNull { it.statementEpochDay }
                            }
                            BillRemindersScreen(
                                sampleCard = sample,
                                sampleMinDuePaise = sampleStmt?.minDuePaise,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("archived") {
                            ArchivedScreen(
                                cards = archived,
                                onOpen = { nav.navigate("card/$it") },
                                onRestore = { id -> scope.launch { app.repo.setArchived(id, false) } },
                                onDelete = { id -> scope.launch { app.repo.delete(id) } },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                    }
                    }
                    }
                    }
                    // First run, over Home: until finished or skipped. Anyone who already has cards never sees it.
                    if (!onboarded && everyCard.isEmpty()) {
                        if (cards == null) {
                            // Still loading: cover Home so it doesn't flash before we know.
                            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {}
                        } else {
                            OnboardingScreen(
                                prefs = app.prefs,
                                onOwnerNameChanged = { ownerRefreshTick++ },
                            ) { exit ->
                                app.prefs.onboarded = true
                                onboarded = true
                                if (exit is OnboardingExit.AddCard) nav.navigate(exit.start?.let { "edit/0?start=$it" } ?: "edit/0")
                            }
                        }
                    }
                    StatementParsingOverlay(
                        visible = statementLoading.visible,
                        completed = statementLoading.completed,
                        fileName = statementLoading.fileName,
                    )
                    if (locked) {
                        // Added after every other Back handler, so it wins: Back never pops the screens behind the lock.
                        androidx.activity.compose.BackHandler { moveTaskToBack(true) }
                        if (showSplash) {
                            // Keeps protected data covered behind the 1s splash before mounting the Lock Dialog above it.
                            Box(Modifier.fillMaxSize()) {
                                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {}
                            }
                        } else {
                            LockScreen(
                                onUnlock = {
                                    scope.launch {
                                        if (Auth.authenticate(this@MainActivity, "Unlock CC Manager")) locked = false
                                    }
                                },
                                onBack = { moveTaskToBack(true) },
                            )
                        }
                    }
                    if (showSplash) {
                        SplashOverlay { showSplash = false }
                    }
                    if (showVaultModal && !locked && !showSplash) {
                        VaultSecurityModal { showVaultModal = false }
                    }
                    if (personalizeOwnerSheet && !locked && !showSplash) {
                        PersonalizeOwnerSheet(
                            owner = ownerState,
                            onDismiss = { personalizeOwnerSheet = false },
                            onSaveCustom = { custom ->
                                setCustomOwnerOverride(this@MainActivity, custom)
                                ownerRefreshTick++
                                personalizeOwnerSheet = false
                            },
                            onResetDefault = {
                                setCustomOwnerOverride(this@MainActivity, null)
                                ownerRefreshTick++
                            },
                        )
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?, shortcuts: Boolean = true) {
        val id = intent?.getLongExtra(Notifications.EXTRA_OPEN_CARD, -1L) ?: -1L
        if (id > 0) {
            pendingCard = id
            pendingCardUpload = intent?.getBooleanExtra(Notifications.EXTRA_OPEN_UPLOAD, false) == true
        }
        val sid = intent?.getLongExtra(Notifications.EXTRA_OPEN_STATEMENT, -1L) ?: -1L
        if (sid > 0) pendingStatement = sid
        // Launcher shortcuts (res/xml/shortcuts.xml).
        if (shortcuts) when (intent?.action) {
            ACTION_ADD_CARD -> pendingAdd = SHORTCUT_ADD
            ACTION_SCAN_CARD -> pendingAdd = "scan"
            ACTION_TAP_CARD -> pendingAdd = "tap"
        }
        // A PDF shared from another app (e.g. a Gmail attachment).
        if (intent?.action == Intent.ACTION_SEND && intent.type == "application/pdf") {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
            if (uri != null) pendingImport = uri
        }
    }

    override fun onStart() {
        super.onStart()
        val app = CardsApp.from(this)

        deviceSecure = Auth.deviceSecure(this)
        if (!deviceSecure || !app.prefs.onboarded || !app.prefs.appLockEnabled) {
            locked = false
            return
        }
        val timeoutMs = CardsApp.from(this).prefs.lockTimeoutSec * 1000L
        if (backgroundedAt > 0 && !Auth.inProgress && !pickingAccount && SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMs) {
            locked = true
        }
        pickingAccount = false
        backgroundedAt = 0
    }

    override fun onStop() {
        super.onStop()
        if (!Auth.inProgress && !isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }
}
