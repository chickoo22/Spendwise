package com.example

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.repository.ExpenseRepository
import com.example.ui.components.SmartParseExpenseDialog
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.BudgetsScreen
import com.example.ui.screens.CurrencyOnboardingScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TransactionsScreen
import com.example.ui.screens.WalkthroughScreen
import com.example.ui.theme.SpendWiseTheme
import com.example.ui.viewmodel.ExpenseViewModel
import com.example.ui.viewmodel.ExpenseViewModelFactory

class MainActivity : ComponentActivity() {

    private companion object {
        const val APP_LOCK_PREFS = "spendwise_prefs"
        const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        const val DEVICE_CREDENTIAL_REQUEST = 741
    }

    private val appLockPrefs by lazy { getSharedPreferences(APP_LOCK_PREFS, Context.MODE_PRIVATE) }
    private var sessionUnlocked = false
    private var authenticationInProgress = false
    private var waitingForDeviceCredential = false

    private val viewModel: ExpenseViewModel by viewModels {
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = ExpenseRepository(
            expenseDao = database.expenseDao(),
            userSettingDao = database.userSettingDao(),
            categoryDao = database.categoryDao(),
            categoryBudgetDao = database.categoryBudgetDao(),
            recurringExpenseDao = database.recurringExpenseDao()
        )
        ExpenseViewModelFactory(application, repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleSharedIntent(intent)

        setContent {
            SpendWiseTheme {
                SpendWiseApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (appLockPrefs.getBoolean(KEY_APP_LOCK_ENABLED, false) && !sessionUnlocked) {
            requestAppUnlock()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations && !authenticationInProgress && !waitingForDeviceCredential) {
            sessionUnlocked = false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DEVICE_CREDENTIAL_REQUEST) {
            waitingForDeviceCredential = false
            sessionUnlocked = resultCode == RESULT_OK
            if (!sessionUnlocked) moveTaskToBack(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                viewModel.onWhatsAppTextShared(sharedText)
            }
        }
    }

    private fun requestAppUnlock() {
        if (authenticationInProgress || waitingForDeviceCredential || isFinishing) return

        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            launchDeviceCredential()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            disableUnavailableAppLock()
            return
        }

        authenticationInProgress = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    authenticationInProgress = false
                    sessionUnlocked = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    authenticationInProgress = false
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        launchDeviceCredential()
                    } else {
                        moveTaskToBack(true)
                    }
                }
            }
        )

        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock SpendWise")
            .setSubtitle("Use your phone's biometric or screen lock")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptBuilder.setAllowedAuthenticators(authenticators)
        } else {
            promptBuilder
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("Use screen lock")
        }
        prompt.authenticate(promptBuilder.build())
    }

    private fun launchDeviceCredential() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = keyguardManager.createConfirmDeviceCredentialIntent(
            "Unlock SpendWise",
            "Confirm your screen lock to continue"
        )
        if (intent == null) {
            disableUnavailableAppLock()
            return
        }
        waitingForDeviceCredential = true
        startActivityForResult(intent, DEVICE_CREDENTIAL_REQUEST)
    }

    private fun disableUnavailableAppLock() {
        appLockPrefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, false).apply()
        sessionUnlocked = true
    }
}

@Composable
fun SpendWiseApp(viewModel: ExpenseViewModel) {
    val context = LocalContext.current
    val isOnboarded by viewModel.isOnboarded.collectAsStateWithLifecycle()
    val currentCurrency by viewModel.currentCurrency.collectAsStateWithLifecycle()
    val analytics by viewModel.monthlyAnalytics.collectAsStateWithLifecycle()
    val allExpenses by viewModel.allExpenses.collectAsStateWithLifecycle()
    val filteredExpenses by viewModel.filteredExpenses.collectAsStateWithLifecycle()
    val customCategories by viewModel.customCategories.collectAsStateWithLifecycle()
    val selectedYear by viewModel.selectedYear.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val categoryFilter by viewModel.selectedCategoryFilter.collectAsStateWithLifecycle()
    val budgetProgressList by viewModel.categoryBudgetProgressList.collectAsStateWithLifecycle()
    val overallBudgetSummary by viewModel.overallBudgetSummary.collectAsStateWithLifecycle()
    val recurringList by viewModel.allRecurringExpenses.collectAsStateWithLifecycle()
    val sharedParsedExpense by viewModel.sharedParsedExpense.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val isDeviceSecure = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    var currentTab by remember { mutableIntStateOf(0) } // 0: Home, 1: Budgets, 2: Analytics, 3: Transactions, 4: Settings

    // If on a secondary tab, Back button returns to Dashboard
    BackHandler(enabled = currentTab != 0) {
        currentTab = 0
    }

    when (isOnboarded) {
        null -> {
            // Initial check in progress
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        false -> {
            var showCurrencyScreen by remember { mutableStateOf(false) }
            if (!showCurrencyScreen) {
                WalkthroughScreen(
                    onFinishWalkthrough = { showCurrencyScreen = true }
                )
            } else {
                CurrencyOnboardingScreen(
                    onCurrencySelected = { currency, seedDemoData ->
                        viewModel.completeOnboardingWithCurrency(currency, seedDemoData)
                    }
                )
            }
        }
        true -> {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.testTag("main_bottom_nav"),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        NavigationBarItem(
                            selected = currentTab == 0,
                            onClick = { currentTab = 0 },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == 0) Icons.Filled.Home else Icons.Outlined.Home,
                                    contentDescription = "Home"
                                )
                            },
                            label = { Text("Home") },
                            modifier = Modifier.testTag("nav_item_home")
                        )

                        NavigationBarItem(
                            selected = currentTab == 1,
                            onClick = { currentTab = 1 },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == 1) Icons.Filled.Savings else Icons.Outlined.Savings,
                                    contentDescription = "Budgets"
                                )
                            },
                            label = { Text("Budgets") },
                            modifier = Modifier.testTag("nav_item_budgets")
                        )

                        NavigationBarItem(
                            selected = currentTab == 2,
                            onClick = { currentTab = 2 },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == 2) Icons.Filled.BarChart else Icons.Outlined.BarChart,
                                    contentDescription = "Insights"
                                )
                            },
                            label = { Text("Insights") },
                            modifier = Modifier.testTag("nav_item_analytics")
                        )

                        NavigationBarItem(
                            selected = currentTab == 3,
                            onClick = { currentTab = 3 },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == 3) Icons.Filled.ReceiptLong else Icons.Outlined.ReceiptLong,
                                    contentDescription = "Transactions"
                                )
                            },
                            label = { Text("Activity") },
                            modifier = Modifier.testTag("nav_item_transactions")
                        )

                        NavigationBarItem(
                            selected = currentTab == 4,
                            onClick = { currentTab = 4 },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == 4) Icons.Filled.Settings else Icons.Outlined.Settings,
                                    contentDescription = "Settings"
                                )
                            },
                            label = { Text("Settings") },
                            modifier = Modifier.testTag("nav_item_settings")
                        )
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    when (currentTab) {
                        0 -> HomeScreen(
                            analytics = analytics,
                            expenses = filteredExpenses,
                            currentCurrency = currentCurrency,
                            availableCategories = com.example.data.model.Category.defaultCategories + customCategories,
                            selectedYear = selectedYear,
                            selectedMonth = selectedMonth,
                            searchQuery = searchQuery,
                            selectedCategoryFilter = categoryFilter,
                            onSearchQueryChange = { viewModel.searchQuery.value = it },
                            onCategoryFilterChange = { viewModel.selectedCategoryFilter.value = it },
                            onPreviousMonth = { viewModel.prevMonth() },
                            onNextMonth = { viewModel.nextMonth() },
                            onResetCurrentMonth = { viewModel.resetToCurrentMonth() },
                            onAddExpense = { title, amt, cat, time, payMethod, notes, isIncome ->
                                viewModel.addExpense(title, amt, cat, time, payMethod, notes, isIncome)
                            },
                            onUpdateExpense = { viewModel.updateExpense(it) },
                            onDeleteExpense = { viewModel.deleteExpense(it) },
                            onChangeCurrencyRequested = { currentTab = 4 },
                            formatCurrency = { viewModel.formatCurrency(it) },
                            overallBudgetSummary = overallBudgetSummary,
                            onNavigateToBudgets = { currentTab = 1 },
                            recurringExpenses = recurringList,
                            onNavigateToRecurring = { currentTab = 4 },
                            onAddCustomCategory = { name, icon, color ->
                                viewModel.addCustomCategory(name, icon, color)
                            }
                        )

                        1 -> BudgetsScreen(
                            budgetProgressList = budgetProgressList,
                            overallSummary = overallBudgetSummary,
                            availableCategories = com.example.data.model.Category.defaultCategories + customCategories,
                            currentCurrency = currentCurrency,
                            selectedYear = selectedYear,
                            selectedMonth = selectedMonth,
                            monthTitle = analytics.monthTitle,
                            onPreviousMonth = { viewModel.prevMonth() },
                            onNextMonth = { viewModel.nextMonth() },
                            onResetCurrentMonth = { viewModel.resetToCurrentMonth() },
                            onSetCategoryBudget = { catId, limit ->
                                viewModel.setCategoryBudget(catId, limit)
                            },
                            onRemoveCategoryBudget = { catId ->
                                viewModel.removeCategoryBudget(catId)
                            },
                            formatCurrency = { viewModel.formatCurrency(it) }
                        )

                        2 -> AnalyticsScreen(
                            analytics = analytics,
                            expenses = filteredExpenses,
                            selectedYear = selectedYear,
                            selectedMonth = selectedMonth,
                            onPreviousMonth = { viewModel.prevMonth() },
                            onNextMonth = { viewModel.nextMonth() },
                            onResetCurrentMonth = { viewModel.resetToCurrentMonth() },
                            onSelectMonth = { y, m -> viewModel.setMonth(y, m) },
                            formatCurrency = { viewModel.formatCurrency(it) }
                        )

                        3 -> TransactionsScreen(
                            allExpenses = allExpenses,
                            currentCurrency = currentCurrency,
                            availableCategories = com.example.data.model.Category.defaultCategories + customCategories,
                            selectedYear = selectedYear,
                            selectedMonth = selectedMonth,
                            monthTitle = analytics.monthTitle,
                            onAddExpense = { title, amt, cat, time, payMethod, notes, isIncome ->
                                viewModel.addExpense(title, amt, cat, time, payMethod, notes, isIncome)
                            },
                            onUpdateExpense = { viewModel.updateExpense(it) },
                            onDeleteExpense = { viewModel.deleteExpense(it) },
                            formatCurrency = { viewModel.formatCurrency(it) },
                            onAddCustomCategory = { name, icon, color ->
                                viewModel.addCustomCategory(name, icon, color)
                            }
                        )

                        4 -> SettingsScreen(
                            currentCurrency = currentCurrency,
                            customCategories = customCategories,
                            allExpenses = allExpenses,
                            selectedYear = selectedYear,
                            selectedMonth = selectedMonth,
                            monthTitle = analytics.monthTitle,
                            recurringList = recurringList,
                            appLockEnabled = appLockEnabled,
                            isDeviceSecure = isDeviceSecure,
                            onAppLockEnabledChange = { viewModel.setAppLockEnabled(it) },
                            onCurrencyChange = { viewModel.updateCurrency(it) },
                            onAddCustomCategory = { name, icon, color ->
                                viewModel.addCustomCategory(name, icon, color)
                            },
                            onSeedDemoData = { viewModel.seedDemoTransactions() },
                            onClearAllData = { viewModel.clearAllData() },
                            onAddRecurring = { title, amt, cat, freq, payMethod, notes, isIncome ->
                                viewModel.addRecurringExpense(title, amt, cat, freq, payMethod, notes, isIncome)
                            },
                            onUpdateRecurring = { viewModel.updateRecurringExpense(it) },
                            onDeleteRecurring = { viewModel.deleteRecurringExpense(it) },
                            formatCurrency = { viewModel.formatCurrency(it) }
                        )
                    }
                }
            }
        }
    }

    if (sharedParsedExpense != null) {
        SmartParseExpenseDialog(
            parsed = sharedParsedExpense!!,
            availableCategories = com.example.data.model.Category.defaultCategories + customCategories,
            currency = currentCurrency,
            onDismiss = { viewModel.dismissSharedExpense() },
            onConfirm = { title, amount, cat, payMethod, notes ->
                viewModel.addExpense(title, amount, cat, System.currentTimeMillis(), payMethod, notes)
                viewModel.dismissSharedExpense()
            },
            formatCurrency = { viewModel.formatCurrency(it) }
        )
    }
}
