package de.sanniki.netsession

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.app.Activity
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4101
private const val PREFS_NAME = "netsession_measurement"
private const val PREF_MEASUREMENT_ACTIVE = "measurement_active"
private const val PREF_STARTED_AT = "started_at"
private const val PREF_BASELINE = "baseline"
private const val PREF_LAST_RESULTS = "last_results"
private const val PREF_LAST_DURATION = "last_duration"
private const val PREF_LAST_COMPLETED_AT = "last_completed_at"
private const val PREF_LAST_STARTED_AT = "last_started_at"
private const val PREF_HISTORY = "measurement_history"
private const val PREF_ACTIVE_NAME = "active_measurement_name"
private const val MAX_HISTORY_ENTRIES = 25

class MainActivity : ComponentActivity() {

    private val preferences by lazy {
        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    private var shizukuState by mutableStateOf(ShizukuUiState())
    private var measurementActive by mutableStateOf(false)
    private var measurementStartedAt by mutableLongStateOf(0L)
    private var operationRunning by mutableStateOf(false)
    private var statusMessage by mutableStateOf("")
    private var results by mutableStateOf<List<AppTrafficResult>>(
        emptyList()
    )

    private var lastMeasurementDuration by mutableLongStateOf(0L)
    private var lastMeasurementStartedAt by mutableLongStateOf(0L)
    private var lastMeasurementCompletedAt by mutableLongStateOf(0L)

    private var measurementName by mutableStateOf("")
    private var measurementHistory by
        mutableStateOf<List<MeasurementSession>>(
            emptyList()
        )

    private var displayedSessionName by
        mutableStateOf("Letzte Messung")

    private var displayedSessionId by
        mutableStateOf<String?>(null)

    @Volatile
    private var netSessionUserService:
        INetSessionUserService? = null

    @Volatile
    private var userServiceConnectionLatch:
        CountDownLatch? = null

    private val userServiceConnection =
        object : ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?
            ) {
                netSessionUserService =
                    INetSessionUserService.Stub.asInterface(
                        binder
                    )

                userServiceConnectionLatch?.countDown()
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {
                netSessionUserService = null
                userServiceConnectionLatch?.countDown()
            }
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            updateShizukuState()
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            updateShizukuState()
        }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener {
                requestCode,
                grantResult ->

            if (
                requestCode ==
                SHIZUKU_PERMISSION_REQUEST_CODE
            ) {
                updateShizukuState()

                if (
                    grantResult ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    statusMessage =
                        "Shizuku wurde freigegeben."
                } else {
                    statusMessage =
                        "Die Shizuku-Berechtigung wurde nicht erteilt."
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addBinderReceivedListenerSticky(
            binderReceivedListener
        )
        Shizuku.addBinderDeadListener(
            binderDeadListener
        )
        Shizuku.addRequestPermissionResultListener(
            permissionResultListener
        )

        measurementActive =
            preferences.getBoolean(
                PREF_MEASUREMENT_ACTIVE,
                false
            )

        measurementStartedAt =
            preferences.getLong(
                PREF_STARTED_AT,
                0L
            )

        results =
            decodeResults(
                preferences.getString(
                    PREF_LAST_RESULTS,
                    null
                )
            )

        lastMeasurementDuration =
            preferences.getLong(
                PREF_LAST_DURATION,
                0L
            )

        lastMeasurementCompletedAt =
            preferences.getLong(
                PREF_LAST_COMPLETED_AT,
                0L
            )

        lastMeasurementStartedAt =
            preferences.getLong(
                PREF_LAST_STARTED_AT,
                max(
                    0L,
                    lastMeasurementCompletedAt -
                        lastMeasurementDuration
                )
            )

        measurementName =
            preferences.getString(
                PREF_ACTIVE_NAME,
                ""
            ).orEmpty()

        measurementHistory =
            decodeHistory(
                preferences.getString(
                    PREF_HISTORY,
                    null
                )
            )

        if (
            measurementHistory.isEmpty() &&
            results.isNotEmpty() &&
            lastMeasurementCompletedAt > 0L
        ) {
            val migratedSession =
                MeasurementSession(
                    id =
                        "legacy-" +
                            lastMeasurementCompletedAt,
                    name = "Letzte Messung",
                    startedAt =
                        max(
                            0L,
                            lastMeasurementCompletedAt -
                                lastMeasurementDuration
                        ),
                    durationMillis =
                        lastMeasurementDuration,
                    completedAt =
                        lastMeasurementCompletedAt,
                    results = results
                )

            measurementHistory =
                listOf(migratedSession)

            preferences
                .edit()
                .putString(
                    PREF_HISTORY,
                    encodeHistory(
                        measurementHistory
                    )
                )
                .apply()
        }

        measurementHistory
            .firstOrNull()
            ?.let { newestSession ->
                results =
                    newestSession.results

                lastMeasurementDuration =
                    newestSession.durationMillis

                lastMeasurementStartedAt =
                    newestSession.startedAt

                lastMeasurementCompletedAt =
                    newestSession.completedAt

                displayedSessionName =
                    newestSession.name

                displayedSessionId =
                    newestSession.id
            }

        updateShizukuState()

        setContent {
            NetSessionTheme {
                NetSessionScreen(
                    shizukuState = shizukuState,
                    measurementActive = measurementActive,
                    measurementStartedAt =
                        measurementStartedAt,
                    operationRunning = operationRunning,
                    statusMessage = statusMessage,
                    results = results,
                    lastMeasurementDuration =
                        lastMeasurementDuration,
                    lastMeasurementStartedAt =
                        lastMeasurementStartedAt,
                    lastMeasurementCompletedAt =
                        lastMeasurementCompletedAt,
                    measurementName =
                        measurementName,
                    measurementHistory =
                        measurementHistory,
                    displayedSessionName =
                        displayedSessionName,
                    displayedSessionId =
                        displayedSessionId,
                    onMeasurementNameChange = {
                        measurementName = it
                    },
                    onOpenHistorySession =
                        ::openHistorySession,
                    onRenameHistorySession =
                        ::renameHistorySession,
                    onDeleteHistorySession =
                        ::deleteHistorySession,
                    onClearHistory =
                        ::clearHistory,
                    onRequestPermission =
                        ::requestShizukuPermission,
                    onStartMeasurement =
                        ::startMeasurement,
                    onStopMeasurement =
                        ::stopMeasurement,
                    onDiscardMeasurement =
                        ::discardMeasurement
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuState()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(
            binderReceivedListener
        )
        Shizuku.removeBinderDeadListener(
            binderDeadListener
        )
        Shizuku.removeRequestPermissionResultListener(
            permissionResultListener
        )

        super.onDestroy()
    }

    private fun updateShizukuState() {
        val binderAvailable =
            try {
                Shizuku.pingBinder()
            } catch (_: Throwable) {
                false
            }

        if (!binderAvailable) {
            shizukuState =
                ShizukuUiState(
                    binderAvailable = false,
                    permissionGranted = false,
                    message =
                        "Shizuku ist nicht gestartet oder nicht erreichbar."
                )

            return
        }

        val permissionGranted =
            try {
                Shizuku.checkSelfPermission() ==
                    PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }

        val serverUid =
            try {
                Shizuku.getUid()
            } catch (_: Throwable) {
                null
            }

        shizukuState =
            ShizukuUiState(
                binderAvailable = true,
                permissionGranted = permissionGranted,
                serverUid = serverUid,
                message =
                    if (permissionGranted) {
                        "Shizuku ist verbunden und berechtigt."
                    } else {
                        "Shizuku ist verbunden. Berechtigung fehlt noch."
                    }
            )
    }

    private fun requestShizukuPermission() {
        updateShizukuState()

        if (!shizukuState.binderAvailable) {
            statusMessage =
                "Bitte Shizuku zuerst starten."

            return
        }

        if (shizukuState.permissionGranted) {
            statusMessage =
                "Die Shizuku-Berechtigung ist bereits vorhanden."

            return
        }

        try {
            Shizuku.requestPermission(
                SHIZUKU_PERMISSION_REQUEST_CODE
            )
        } catch (throwable: Throwable) {
            statusMessage =
                "Berechtigungsanfrage fehlgeschlagen: " +
                    throwable.readableMessage()
        }
    }

    private fun startMeasurement() {
        updateShizukuState()

        if (!shizukuState.permissionGranted) {
            statusMessage =
                "Für die Messung wird die Shizuku-Berechtigung benötigt."

            return
        }

        if (operationRunning || measurementActive) {
            return
        }

        operationRunning = true
        statusMessage =
            "Ausgangswerte werden gelesen …"
        thread(name = "NetSession-Start") {
            try {
                val snapshot =
                    readCompactSnapshot()

                if (snapshot.isEmpty()) {
                    throw IllegalStateException(
                        "Die Netzwerkstatistik enthielt keine auswertbaren UID-Daten."
                    )
                }

                val startedAt =
                    System.currentTimeMillis()

                preferences
                    .edit()
                    .putBoolean(
                        PREF_MEASUREMENT_ACTIVE,
                        true
                    )
                    .putLong(
                        PREF_STARTED_AT,
                        startedAt
                    )
                    .putString(
                        PREF_BASELINE,
                        encodeSnapshot(snapshot)
                    )
                    .putString(
                        PREF_ACTIVE_NAME,
                        measurementName.trim()
                    )
                    .apply()

                runOnUiThread {
                    measurementStartedAt = startedAt
                    measurementActive = true
                    statusMessage =
                        "Messung läuft."
                    operationRunning = false
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    statusMessage =
                        "Messung konnte nicht gestartet werden: " +
                            throwable.readableMessage()

                    operationRunning = false
                }
            }
        }
    }

    private fun stopMeasurement() {
        updateShizukuState()

        if (!measurementActive || operationRunning) {
            return
        }

        if (!shizukuState.permissionGranted) {
            statusMessage =
                "Zum Stoppen wird die Shizuku-Berechtigung benötigt."

            return
        }

        operationRunning = true
        statusMessage =
            "Endwerte werden gelesen und ausgewertet …"

        thread(name = "NetSession-Stop") {
            try {
                val baselineText =
                    preferences.getString(
                        PREF_BASELINE,
                        null
                    )
                        ?: throw IllegalStateException(
                            "Der gespeicherte Ausgangssnapshot fehlt."
                        )

                val baseline =
                    decodeSnapshot(baselineText)

                val current =
                    readCompactSnapshot()

                val calculatedResults =
                    calculateResults(
                        baseline = baseline,
                        current = current
                    )

                val completedAt =
                    System.currentTimeMillis()

                val duration =
                    max(
                        0L,
                        completedAt -
                            measurementStartedAt
                    )

                val requestedName =
                    preferences.getString(
                        PREF_ACTIVE_NAME,
                        ""
                    )
                        .orEmpty()
                        .trim()

                val sessionName =
                    requestedName.ifBlank {
                        "Messung " +
                            formatShortDateTime(
                                completedAt
                            )
                    }

                val newSession =
                    MeasurementSession(
                        id =
                            completedAt.toString() +
                                "-" +
                                System.nanoTime(),
                        name = sessionName,
                        startedAt =
                            measurementStartedAt,
                        durationMillis = duration,
                        completedAt = completedAt,
                        results = calculatedResults
                    )

                val storedHistory =
                    decodeHistory(
                        preferences.getString(
                            PREF_HISTORY,
                            null
                        )
                    )

                val updatedHistory =
                    (
                        listOf(newSession) +
                            storedHistory.filterNot {
                                it.id == newSession.id
                            }
                    )
                        .take(
                            MAX_HISTORY_ENTRIES
                        )

                preferences
                    .edit()
                    .putBoolean(
                        PREF_MEASUREMENT_ACTIVE,
                        false
                    )
                    .putString(
                        PREF_LAST_RESULTS,
                        encodeResults(
                            calculatedResults
                        )
                    )
                    .putLong(
                        PREF_LAST_DURATION,
                        duration
                    )
                    .putLong(
                        PREF_LAST_STARTED_AT,
                        measurementStartedAt
                    )
                    .putLong(
                        PREF_LAST_COMPLETED_AT,
                        completedAt
                    )
                    .putString(
                        PREF_HISTORY,
                        encodeHistory(
                            updatedHistory
                        )
                    )
                    .remove(PREF_BASELINE)
                    .remove(PREF_ACTIVE_NAME)
                    .apply()

                runOnUiThread {
                    results = calculatedResults
                    lastMeasurementDuration = duration
                    lastMeasurementStartedAt =
                        measurementStartedAt
                    lastMeasurementCompletedAt = completedAt
                    measurementHistory = updatedHistory
                    displayedSessionName =
                        newSession.name
                    displayedSessionId =
                        newSession.id
                    measurementName = ""
                    measurementActive = false
                    operationRunning = false

                    statusMessage =
                        if (calculatedResults.isEmpty()) {
                            "Im Messzeitraum wurde kein UID-Verkehr erkannt."
                        } else {
                            "Messung abgeschlossen: " +
                                "${calculatedResults.size} Einträge erkannt."
                        }
                }
            } catch (throwable: Throwable) {
                runOnUiThread {
                    statusMessage =
                        "Auswertung fehlgeschlagen: " +
                            throwable.readableMessage()

                    operationRunning = false
                }
            }
        }
    }

    private fun discardMeasurement() {
        preferences
            .edit()
            .putBoolean(
                PREF_MEASUREMENT_ACTIVE,
                false
            )
            .remove(PREF_STARTED_AT)
            .remove(PREF_BASELINE)
            .remove(PREF_ACTIVE_NAME)
            .apply()

        measurementActive = false
        measurementStartedAt = 0L
        measurementName = ""
        operationRunning = false
        statusMessage =
            "Die laufende Messung wurde verworfen."
    }

    private fun openHistorySession(
        session: MeasurementSession
    ) {
        results = session.results
        lastMeasurementDuration =
            session.durationMillis
        lastMeasurementStartedAt =
            session.startedAt
        lastMeasurementCompletedAt =
            session.completedAt
        displayedSessionName =
            session.name
        displayedSessionId =
            session.id

        statusMessage =
            "Gespeicherte Sitzung geöffnet."
    }

    private fun renameHistorySession(
        sessionId: String,
        requestedName: String
    ) {
        val cleanName =
            requestedName
                .trim()
                .take(60)

        if (cleanName.isBlank()) {
            statusMessage =
                "Der Sitzungsname darf nicht leer sein."
            return
        }

        val updatedHistory =
            measurementHistory.map { session ->
                if (session.id == sessionId) {
                    session.copy(
                        name = cleanName
                    )
                } else {
                    session
                }
            }

        if (updatedHistory == measurementHistory) {
            return
        }

        measurementHistory =
            updatedHistory

        preferences
            .edit()
            .putString(
                PREF_HISTORY,
                encodeHistory(
                    updatedHistory
                )
            )
            .apply()

        if (displayedSessionId == sessionId) {
            displayedSessionName =
                cleanName
        }

        statusMessage =
            "Sitzung wurde umbenannt."
    }

    private fun deleteHistorySession(
        sessionId: String
    ) {
        val updatedHistory =
            measurementHistory.filterNot {
                it.id == sessionId
            }

        measurementHistory =
            updatedHistory

        preferences
            .edit()
            .putString(
                PREF_HISTORY,
                encodeHistory(
                    updatedHistory
                )
            )
            .apply()

        if (displayedSessionId == sessionId) {
            val fallback =
                updatedHistory.firstOrNull()

            if (fallback != null) {
                results =
                    fallback.results
                lastMeasurementDuration =
                    fallback.durationMillis
                lastMeasurementStartedAt =
                    fallback.startedAt
                lastMeasurementCompletedAt =
                    fallback.completedAt
                displayedSessionName =
                    fallback.name
                displayedSessionId =
                    fallback.id
            } else {
                results = emptyList()
                lastMeasurementDuration = 0L
                lastMeasurementStartedAt = 0L
                lastMeasurementCompletedAt = 0L
                displayedSessionName =
                    "Letzte Messung"
                displayedSessionId = null

                preferences
                    .edit()
                    .remove(PREF_LAST_RESULTS)
                    .remove(PREF_LAST_DURATION)
                    .remove(PREF_LAST_STARTED_AT)
                    .remove(
                        PREF_LAST_COMPLETED_AT
                    )
                    .apply()
            }
        }

        statusMessage =
            "Sitzung wurde gelöscht."
    }

    private fun clearHistory() {
        measurementHistory = emptyList()
        results = emptyList()

        lastMeasurementDuration = 0L
        lastMeasurementStartedAt = 0L
        lastMeasurementCompletedAt = 0L

        displayedSessionName =
            "Letzte Messung"

        displayedSessionId = null

        preferences
            .edit()
            .remove(PREF_HISTORY)
            .remove(PREF_LAST_RESULTS)
            .remove(PREF_LAST_DURATION)
            .remove(PREF_LAST_STARTED_AT)
            .remove(PREF_LAST_COMPLETED_AT)
            .apply()

        statusMessage =
            "Alle gespeicherten Sitzungen wurden gelöscht."
    }

    private fun calculateResults(
        baseline: Map<TrafficKey, TrafficValue>,
        current: Map<TrafficKey, TrafficValue>
    ): List<AppTrafficResult> {
        val byUid =
            mutableMapOf<Int, MutableTrafficResult>()

        val allKeys =
            baseline.keys + current.keys

        for (key in allKeys) {
            val start =
                baseline[key] ?: TrafficValue()

            val end =
                current[key] ?: TrafficValue()

            val rxDelta =
                max(0L, end.rxBytes - start.rxBytes)

            val txDelta =
                max(0L, end.txBytes - start.txBytes)

            if (rxDelta == 0L && txDelta == 0L) {
                continue
            }

            val result =
                byUid.getOrPut(key.uid) {
                    MutableTrafficResult(
                        uid = key.uid
                    )
                }

            result.rxBytes += rxDelta
            result.txBytes += txDelta

            when (key.networkType) {
                NetworkType.WIFI -> {
                    result.wifiRxBytes += rxDelta
                    result.wifiTxBytes += txDelta
                }

                NetworkType.MOBILE -> {
                    result.mobileRxBytes += rxDelta
                    result.mobileTxBytes += txDelta
                }

                NetworkType.OTHER -> {
                    result.otherRxBytes += rxDelta
                    result.otherTxBytes += txDelta
                }
            }

            when (key.trafficSet) {
                TrafficSet.FOREGROUND -> {
                    result.foregroundRxBytes += rxDelta
                    result.foregroundTxBytes += txDelta
                }

                TrafficSet.DEFAULT -> {
                    result.defaultRxBytes += rxDelta
                    result.defaultTxBytes += txDelta
                }

                TrafficSet.OTHER -> Unit
            }
        }

        return byUid
            .values
            .map { mutable ->
                val appIdentity =
                    resolveUidIdentity(mutable.uid)

                AppTrafficResult(
                    uid = mutable.uid,
                    appName = appIdentity.first,
                    packageNames = appIdentity.second,
                    rxBytes = mutable.rxBytes,
                    txBytes = mutable.txBytes,
                    wifiRxBytes = mutable.wifiRxBytes,
                    wifiTxBytes = mutable.wifiTxBytes,
                    mobileRxBytes =
                        mutable.mobileRxBytes,
                    mobileTxBytes =
                        mutable.mobileTxBytes,
                    otherRxBytes =
                        mutable.otherRxBytes,
                    otherTxBytes =
                        mutable.otherTxBytes,
                    foregroundRxBytes =
                        mutable.foregroundRxBytes,
                    foregroundTxBytes =
                        mutable.foregroundTxBytes,
                    defaultRxBytes =
                        mutable.defaultRxBytes,
                    defaultTxBytes =
                        mutable.defaultTxBytes
                )
            }
            .sortedByDescending {
                it.totalBytes
            }
    }

    private fun resolveUidIdentity(
        uid: Int
    ): Pair<String, List<String>> {
        val packageNames =
            packageManager
                .getPackagesForUid(uid)
                ?.toList()
                ?.sorted()
                .orEmpty()

        if (packageNames.isEmpty()) {
            return systemUidName(uid) to emptyList()
        }

        val labels =
            packageNames
                .mapNotNull { packageName ->
                    try {
                        val applicationInfo =
                            packageManager.getApplicationInfo(
                                packageName,
                                0
                            )

                        packageManager
                            .getApplicationLabel(
                                applicationInfo
                            )
                            .toString()
                            .trim()
                            .takeIf {
                                it.isNotBlank()
                            }
                    } catch (_: Throwable) {
                        null
                    }
                }
                .distinct()
                .sorted()

        val displayName =
            when {
                uid == 1000 ->
                    "Android-System"

                uid == 1001 ->
                    "Telefonie-System"

                uid == 1020 ->
                    "Download-Manager"

                uid == 2000 ->
                    "ADB-Shell"

                packageNames.size == 1 &&
                    labels.size == 1 ->
                    labels.first()

                packageNames.size == 1 ->
                    packageNames.first()

                labels.size == 1 ->
                    labels.first()

                packageNames.all {
                    it.startsWith("com.google.android.gms")
                } ->
                    "Google Play-Dienste"

                else ->
                    sharedUidDisplayName(
                        uid = uid,
                        labels = labels,
                        packageNames = packageNames
                    )
            }

        return displayName to packageNames
    }

    private fun sharedUidDisplayName(
        uid: Int,
        labels: List<String>,
        packageNames: List<String>
    ): String =
        when {
            uid == 1000 ->
                "Android-System"

            packageNames.any {
                it == "com.google.android.gms"
            } ->
                "Google Play-Dienste"

            labels.isNotEmpty() ->
                labels.first() +
                    " und ${packageNames.size - 1} weitere"

            else ->
                "Gemeinsam genutzte UID $uid"
        }

    private fun systemUidName(uid: Int): String =
        when (uid) {
            -4 -> "Entfernter oder gelöschter Nutzer"
            0 -> "Kernel / Root"
            1000 -> "Android-System"
            1001 -> "Telefonie"
            1013 -> "Medienserver"
            1020 -> "Download-Manager"
            1073 -> "Netzwerkdienst"
            2000 -> "ADB-Shell"
            else -> "UID $uid"
        }

    private fun userServiceArgs():
        Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(
                this,
                NetSessionUserService::class.java
            )
        )
            .daemon(false)
            .processNameSuffix("netsession")
            .debuggable(BuildConfig.DEBUG)
            .version(2)

    private fun obtainUserService():
        INetSessionUserService {

        netSessionUserService?.let {
            return it
        }

        val latch = CountDownLatch(1)
        userServiceConnectionLatch = latch

        try {
            Shizuku.bindUserService(
                userServiceArgs(),
                userServiceConnection
            )
        } catch (throwable: Throwable) {
            userServiceConnectionLatch = null

            throw IllegalStateException(
                "Der Shizuku-UserService konnte nicht gestartet werden: " +
                    throwable.readableMessage(),
                throwable
            )
        }

        val connected =
            latch.await(
                12,
                TimeUnit.SECONDS
            )

        userServiceConnectionLatch = null

        if (!connected) {
            throw IllegalStateException(
                "Zeitüberschreitung beim Starten des Shizuku-UserService."
            )
        }

        return netSessionUserService
            ?: throw IllegalStateException(
                "Der Shizuku-UserService wurde vor der Messung getrennt."
            )
    }

    private fun readCompactSnapshot():
        Map<TrafficKey, TrafficValue> {

        val output =
            obtainUserService()
                .runCommand(
                    "dumpsys netstats detail 2>&1"
                )

        val snapshot =
            parseNetStatsDetail(output)

        if (snapshot.isEmpty()) {
            val uidStatsPosition =
                output.indexOf("UID stats:")

            val diagnosticPreview =
                if (uidStatsPosition >= 0) {
                    output
                        .substring(uidStatsPosition)
                        .lineSequence()
                        .take(45)
                        .joinToString("\n")
                        .take(6_000)
                } else {
                    output
                        .lineSequence()
                        .take(45)
                        .joinToString("\n")
                        .take(6_000)
                }

            throw IllegalStateException(
                buildString {
                    appendLine(
                        "Der Kotlin-Parser fand keine auswertbaren UID-Datensätze."
                    )
                    appendLine()
                    appendLine(
                        "UID-stats-Abschnitt vorhanden: " +
                            if (uidStatsPosition >= 0) {
                                "ja"
                            } else {
                                "nein"
                            }
                    )
                    appendLine()
                    appendLine("Technische Vorschau:")
                    append(
                        diagnosticPreview.ifBlank {
                            "Keine Ausgabe vom UserService."
                        }
                    )
                }
            )
        }

        return snapshot
    }

    private fun parseNetStatsDetail(
        output: String
    ): Map<TrafficKey, TrafficValue> {

        val result =
            mutableMapOf<TrafficKey, TrafficValue>()

        var insideUidStats = false
        var insideHistory = false

        var currentKey: TrafficKey? = null
        var currentTag: String? = null
        var currentRxBytes = 0L
        var currentTxBytes = 0L

        fun finishRecord() {
            val key = currentKey

            if (
                key != null &&
                currentTag == "0x0"
            ) {
                val previous =
                    result[key] ?: TrafficValue()

                result[key] =
                    TrafficValue(
                        rxBytes =
                            previous.rxBytes +
                                currentRxBytes,
                        txBytes =
                            previous.txBytes +
                                currentTxBytes
                    )
            }

            currentKey = null
            currentTag = null
            currentRxBytes = 0L
            currentTxBytes = 0L
        }

        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()

            when {
                line == "UID stats:" -> {
                    finishRecord()
                    insideUidStats = true
                    insideHistory = false
                    return@forEach
                }

                !insideUidStats -> {
                    return@forEach
                }

                line == "History since boot:" -> {
                    insideHistory = true
                    return@forEach
                }

                line == "UID tag stats:" ||
                    line.startsWith("UID tag stats:") -> {

                    finishRecord()
                    insideUidStats = false
                    insideHistory = false
                    return@forEach
                }

                !insideHistory -> {
                    return@forEach
                }

                line.startsWith("ident=") -> {
                    finishRecord()

                    val uid =
                        Regex(
                            """\buid=(-?\d+)"""
                        )
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                    val setText =
                        Regex(
                            """\bset=([A-Z]+)"""
                        )
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)

                    val tag =
                        Regex(
                            """\btag=(0x[0-9a-fA-F]+)"""
                        )
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)

                    if (uid == null) {
                        currentKey = null
                        currentTag = tag
                        return@forEach
                    }

                    val trafficSet =
                        when (setText) {
                            "DEFAULT" ->
                                TrafficSet.DEFAULT

                            "FOREGROUND" ->
                                TrafficSet.FOREGROUND

                            else ->
                                TrafficSet.OTHER
                        }

                    val networkType =
                        when {
                            Regex(
                                """\btype=1\b"""
                            ).containsMatchIn(line) ->
                                NetworkType.WIFI

                            Regex(
                                """\btype=0\b"""
                            ).containsMatchIn(line) ->
                                NetworkType.MOBILE

                            else ->
                                NetworkType.OTHER
                        }

                    currentKey =
                        TrafficKey(
                            uid = uid,
                            trafficSet = trafficSet,
                            networkType = networkType
                        )

                    currentTag = tag
                    return@forEach
                }

                line.startsWith("st=") &&
                    currentKey != null -> {

                    val rxBytes =
                        Regex(
                            """\brb=(\d+)"""
                        )
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toLongOrNull()
                            ?: 0L

                    val txBytes =
                        Regex(
                            """\btb=(\d+)"""
                        )
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toLongOrNull()
                            ?: 0L

                    currentRxBytes += rxBytes
                    currentTxBytes += txBytes
                }
            }
        }

        finishRecord()

        return result
    }
}

private data class ShizukuUiState(
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val serverUid: Int? = null,
    val message: String =
        "Shizuku-Status wird geprüft."
)

private enum class NetworkType {
    WIFI,
    MOBILE,
    OTHER
}

private enum class TrafficSet {
    DEFAULT,
    FOREGROUND,
    OTHER
}

private data class TrafficKey(
    val uid: Int,
    val trafficSet: TrafficSet,
    val networkType: NetworkType
)

private data class TrafficValue(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L
)

private data class MutableTrafficResult(
    val uid: Int,
    var rxBytes: Long = 0L,
    var txBytes: Long = 0L,
    var wifiRxBytes: Long = 0L,
    var wifiTxBytes: Long = 0L,
    var mobileRxBytes: Long = 0L,
    var mobileTxBytes: Long = 0L,
    var otherRxBytes: Long = 0L,
    var otherTxBytes: Long = 0L,
    var foregroundRxBytes: Long = 0L,
    var foregroundTxBytes: Long = 0L,
    var defaultRxBytes: Long = 0L,
    var defaultTxBytes: Long = 0L
)

private data class MeasurementSession(
    val id: String,
    val name: String,
    val startedAt: Long,
    val durationMillis: Long,
    val completedAt: Long,
    val results: List<AppTrafficResult>
) {
    val totalBytes: Long
        get() =
            results.sumOf {
                it.totalBytes
            }

    val strongestConsumer: String
        get() =
            results
                .firstOrNull()
                ?.appName
                ?: "Kein Netzwerkverkehr"
}

private data class AppTrafficResult(
    val uid: Int,
    val appName: String,
    val packageNames: List<String>,
    val rxBytes: Long,
    val txBytes: Long,
    val wifiRxBytes: Long,
    val wifiTxBytes: Long,
    val mobileRxBytes: Long,
    val mobileTxBytes: Long,
    val otherRxBytes: Long,
    val otherTxBytes: Long,
    val foregroundRxBytes: Long,
    val foregroundTxBytes: Long,
    val defaultRxBytes: Long,
    val defaultTxBytes: Long
) {
    val totalBytes: Long
        get() = rxBytes + txBytes

    val wifiBytes: Long
        get() = wifiRxBytes + wifiTxBytes

    val mobileBytes: Long
        get() = mobileRxBytes + mobileTxBytes

    val otherBytes: Long
        get() = otherRxBytes + otherTxBytes

    val foregroundBytes: Long
        get() =
            foregroundRxBytes +
                foregroundTxBytes

    val defaultBytes: Long
        get() =
            defaultRxBytes +
                defaultTxBytes
}

private fun encodeSnapshot(
    snapshot: Map<TrafficKey, TrafficValue>
): String =
    snapshot.entries.joinToString("\n") {
            entry ->

        listOf(
            entry.key.uid,
            entry.key.trafficSet.name,
            entry.key.networkType.name,
            entry.value.rxBytes,
            entry.value.txBytes
        ).joinToString("|")
    }

private fun decodeSnapshot(
    text: String
): Map<TrafficKey, TrafficValue> =
    text
        .lineSequence()
        .mapNotNull { line ->
            val parts =
                line.split('|')

            if (parts.size != 5) {
                return@mapNotNull null
            }

            val uid =
                parts[0].toIntOrNull()
                    ?: return@mapNotNull null

            val trafficSet =
                runCatching {
                    TrafficSet.valueOf(parts[1])
                }.getOrNull()
                    ?: return@mapNotNull null

            val networkType =
                runCatching {
                    NetworkType.valueOf(parts[2])
                }.getOrNull()
                    ?: return@mapNotNull null

            val rxBytes =
                parts[3].toLongOrNull()
                    ?: return@mapNotNull null

            val txBytes =
                parts[4].toLongOrNull()
                    ?: return@mapNotNull null

            TrafficKey(
                uid = uid,
                trafficSet = trafficSet,
                networkType = networkType
            ) to
                TrafficValue(
                    rxBytes = rxBytes,
                    txBytes = txBytes
                )
        }
        .toMap()

private fun Throwable.readableMessage(): String =
    message?.takeIf {
        it.isNotBlank()
    }
        ?: cause?.message?.takeIf {
            it.isNotBlank()
        }
        ?: "Unbekannter Fehler"

@Composable
private fun NetSessionScreen(
    shizukuState: ShizukuUiState,
    measurementActive: Boolean,
    measurementStartedAt: Long,
    operationRunning: Boolean,
    statusMessage: String,
    results: List<AppTrafficResult>,
    lastMeasurementDuration: Long,
    lastMeasurementStartedAt: Long,
    lastMeasurementCompletedAt: Long,
    measurementName: String,
    measurementHistory: List<MeasurementSession>,
    displayedSessionName: String,
    displayedSessionId: String?,
    onMeasurementNameChange: (String) -> Unit,
    onOpenHistorySession:
        (MeasurementSession) -> Unit,
    onRenameHistorySession:
        (String, String) -> Unit,
    onDeleteHistorySession:
        (String) -> Unit,
    onClearHistory: () -> Unit,
    onRequestPermission: () -> Unit,
    onStartMeasurement: () -> Unit,
    onStopMeasurement: () -> Unit,
    onDiscardMeasurement: () -> Unit
) {
    val context = LocalContext.current

    var pendingExportText by
        remember {
            mutableStateOf("")
        }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.CreateDocument(
                    "text/plain"
                )
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context
                        .contentResolver
                        .openOutputStream(uri)
                        ?.bufferedWriter()
                        ?.use { writer ->
                            writer.write(
                                pendingExportText
                            )
                        }
                        ?: error(
                            "Die Zieldatei konnte nicht geöffnet werden."
                        )
                }
            }

            pendingExportText = ""
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.background
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 18.dp,
                    vertical = 18.dp
                ),
        verticalArrangement =
            Arrangement.spacedBy(16.dp)
    ) {
        Header()

        ShizukuCard(
            state = shizukuState,
            onRequestPermission =
                onRequestPermission
        )

        MeasurementCard(
            measurementActive =
                measurementActive,
            measurementStartedAt =
                measurementStartedAt,
            operationRunning =
                operationRunning,
            permissionGranted =
                shizukuState.permissionGranted,
            measurementName =
                measurementName,
            onMeasurementNameChange =
                onMeasurementNameChange,
            onStartMeasurement =
                onStartMeasurement,
            onStopMeasurement =
                onStopMeasurement,
            onDiscardMeasurement =
                onDiscardMeasurement
        )

        if (statusMessage.isNotBlank()) {
            StatusCard(statusMessage)
        }

        if (results.isNotEmpty()) {
            ResultsCard(
                sessionName =
                    displayedSessionName,
                results = results,
                measurementDuration =
                    lastMeasurementDuration,
                startedAt =
                    lastMeasurementStartedAt,
                completedAt =
                    lastMeasurementCompletedAt,
                onCopy = {
                    copyText(
                        context = context,
                        label =
                            "NetSession-Messergebnis",
                        text =
                            formatResultsReport(
                                sessionName =
                                    displayedSessionName,
                                results = results,
                                measurementDuration =
                                    lastMeasurementDuration,
                                startedAt =
                                    lastMeasurementStartedAt,
                                completedAt =
                                    lastMeasurementCompletedAt
                            )
                    )
                }
            )
        }

        if (measurementHistory.isNotEmpty()) {
            HistoryCard(
                sessions =
                    measurementHistory,
                selectedSessionId =
                    displayedSessionId,
                onOpen =
                    onOpenHistorySession,
                onRename =
                    onRenameHistorySession,
                onShare = { session ->
                    shareTextReport(
                        context = context,
                        subject =
                            "NetSession – " +
                                session.name,
                        text =
                            formatSessionReport(
                                session
                            )
                    )
                },
                onExport = { session ->
                    pendingExportText =
                        formatSessionReport(
                            session
                        )

                    exportLauncher.launch(
                        buildSessionFileName(
                            session
                        )
                    )
                },
                onDelete =
                    onDeleteHistorySession,
                onClearAll =
                    onClearHistory
            )
        }

        Column(
            modifier =
                Modifier
                    .align(
                        Alignment.CenterHorizontally
                    )
                    .padding(
                        top = 4.dp,
                        bottom = 12.dp
                    ),
            horizontalAlignment =
                Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "NetSession v0.1h2",
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
                fontSize = 12.sp
            )

            Text(
                text = "von dernikiausd",
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
                        .copy(alpha = 0.72f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun Header() {
    Column(
        verticalArrangement =
            Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "NetSession",
            fontSize = 31.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text =
                "Netzwerkverbrauch im Messzeitraum",
            fontSize = 16.sp,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}

@Composable
private fun ShizukuCard(
    state: ShizukuUiState,
    onRequestPermission: () -> Unit
) {
    val statusColor =
        when {
            state.permissionGranted ->
                Color(0xFF76D39B)

            state.binderAvailable ->
                Color(0xFFFFC857)

            else ->
                MaterialTheme.colorScheme.error
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant
            )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement =
                Arrangement.spacedBy(11.dp)
        ) {
            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Box(
                    modifier =
                        Modifier
                            .background(
                                statusColor,
                                RoundedCornerShape(50)
                            )
                            .padding(5.dp)
                )

                Text(
                    text = "Shizuku",
                    modifier =
                        Modifier.padding(
                            start = 10.dp
                        ),
                    fontSize = 20.sp,
                    fontWeight =
                        FontWeight.SemiBold
                )
            }

            Text(
                text = state.message,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            if (
                state.binderAvailable &&
                state.serverUid != null
            ) {
                Text(
                    text =
                        "Dienstidentität: " +
                            when (
                                state.serverUid
                            ) {
                                0 ->
                                    "Root (UID 0)"

                                2000 ->
                                    "ADB-Shell (UID 2000)"

                                else ->
                                    "UID ${state.serverUid}"
                            },
                    fontSize = 13.sp,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            if (!state.permissionGranted) {
                Button(
                    onClick =
                        onRequestPermission,
                    enabled =
                        state.binderAvailable,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Berechtigung anfordern"
                    )
                }
            }
        }
    }
}

@Composable
private fun MeasurementCard(
    measurementActive: Boolean,
    measurementStartedAt: Long,
    operationRunning: Boolean,
    permissionGranted: Boolean,
    measurementName: String,
    onMeasurementNameChange: (String) -> Unit,
    onStartMeasurement: () -> Unit,
    onStopMeasurement: () -> Unit,
    onDiscardMeasurement: () -> Unit
) {
    var now by rememberCurrentTime(
        active = measurementActive
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surface
            )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement =
                Arrangement.spacedBy(13.dp)
        ) {
            Text(
                text =
                    if (measurementActive) {
                        "Messung läuft"
                    } else {
                        "Neue Messung"
                    },
                fontSize = 21.sp,
                fontWeight =
                    FontWeight.SemiBold
            )

            if (measurementActive) {
                Text(
                    text =
                        formatDuration(
                            now -
                                measurementStartedAt
                        ),
                    fontFamily =
                        FontFamily.Monospace,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text =
                        "NetSession vergleicht beim Stoppen die aktuellen UID-Zähler mit den gespeicherten Ausgangswerten.",
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    lineHeight = 21.sp
                )

                Button(
                    onClick =
                        onStopMeasurement,
                    enabled =
                        !operationRunning &&
                            permissionGranted,
                    modifier =
                        Modifier.fillMaxWidth(),
                    contentPadding =
                        PaddingValues(
                            vertical = 13.dp
                        )
                ) {
                    if (operationRunning) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier.height(
                                    20.dp
                                ),
                            strokeWidth = 2.dp
                        )

                        Spacer(
                            Modifier.width(10.dp)
                        )
                    }

                    Text(
                        if (operationRunning) {
                            "Auswertung läuft …"
                        } else {
                            "Messung stoppen"
                        }
                    )
                }

                OutlinedButton(
                    onClick =
                        onDiscardMeasurement,
                    enabled =
                        !operationRunning,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text("Messung verwerfen")
                }
            } else {
                Text(
                    text =
                        "Speichert die aktuellen Netzwerkzähler als Ausgangspunkt. Ein vorhandenes Ergebnis bleibt sichtbar, bis die neue Messung abgeschlossen ist.",
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    lineHeight = 21.sp
                )

                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    shape =
                        RoundedCornerShape(
                            16.dp
                        ),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .surfaceVariant
                        )
                ) {
                    Text(
                        text =
                            "Sehr kurze Messungen können leer bleiben oder kleine Übertragungen zeitversetzt erfassen. Für aussagekräftige Ergebnisse mindestens 30 Sekunden messen.",
                        modifier =
                            Modifier.padding(
                                14.dp
                            ),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = measurementName,
                    onValueChange = {
                        onMeasurementNameChange(
                            it.take(60)
                        )
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    enabled =
                        !operationRunning,
                    singleLine = true,
                    label = {
                        Text(
                            "Name der Messung"
                        )
                    },
                    placeholder = {
                        Text(
                            "Optional, z. B. Standby-Test"
                        )
                    },
                    supportingText = {
                        Text(
                            "Bleibt das Feld leer, vergibt NetSession automatisch einen Namen."
                        )
                    }
                )

                Button(
                    onClick =
                        onStartMeasurement,
                    enabled =
                        permissionGranted &&
                            !operationRunning,
                    modifier =
                        Modifier.fillMaxWidth(),
                    contentPadding =
                        PaddingValues(
                            vertical = 13.dp
                        )
                ) {
                    if (operationRunning) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier.height(
                                    20.dp
                                ),
                            strokeWidth = 2.dp
                        )

                        Spacer(
                            Modifier.width(10.dp)
                        )
                    }

                    Text(
                        if (operationRunning) {
                            "Ausgangswerte werden gelesen …"
                        } else {
                            "Neue Messung starten"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberCurrentTime(
    active: Boolean
): androidx.compose.runtime.MutableLongState {
    val state =
        androidx.compose.runtime.remember {
            mutableLongStateOf(
                System.currentTimeMillis()
            )
        }

    LaunchedEffect(active) {
        while (active) {
            state.longValue =
                System.currentTimeMillis()

            delay(1_000L)
        }
    }

    return state
}

@Composable
private fun StatusCard(
    message: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant
            )
    ) {
        Text(
            text = message,
            modifier =
                Modifier.padding(16.dp),
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,
            lineHeight = 21.sp
        )
    }
}

@Composable
private fun ResultsCard(
    sessionName: String,
    results: List<AppTrafficResult>,
    measurementDuration: Long,
    startedAt: Long,
    completedAt: Long,
    onCopy: () -> Unit
) {
    val totalRx =
        results.sumOf {
            it.rxBytes
        }

    val totalTx =
        results.sumOf {
            it.txBytes
        }

    val totalTraffic =
        totalRx + totalTx

    val totalWifi =
        results.sumOf {
            it.wifiBytes
        }

    val totalMobile =
        results.sumOf {
            it.mobileBytes
        }

    val totalOther =
        results.sumOf {
            it.otherBytes
        }

    val totalForeground =
        results.sumOf {
            it.foregroundBytes
        }

    val totalDefault =
        results.sumOf {
            it.defaultBytes
        }

    val strongestResult =
        results.firstOrNull()

    var showAllResults by
        androidx.compose.runtime.remember {
            mutableStateOf(false)
        }

    val visibleResults =
        if (showAllResults) {
            results
        } else {
            results.take(8)
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surface
            )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement =
                Arrangement.spacedBy(14.dp)
        ) {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Text(
                        text =
                            sessionName.ifBlank {
                                "Letzte Messung"
                            },
                        modifier =
                            Modifier.weight(1f),
                        fontSize = 22.sp,
                        fontWeight =
                            FontWeight.Bold
                    )

                    OutlinedButton(
                        onClick = onCopy,
                        contentPadding =
                            PaddingValues(
                                horizontal = 17.dp,
                                vertical = 8.dp
                            )
                    ) {
                        Text("Kopieren")
                    }
                }

                Text(
                    text =
                        buildString {
                            if (
                                measurementDuration > 0L
                            ) {
                                append(
                                    formatDuration(
                                        measurementDuration
                                    )
                                )
                                append(" · ")
                            }

                            append(
                                "${results.size} Einträge"
                            )
                        },
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    fontSize = 13.sp
                )

                if (completedAt > 0L) {
                    Text(
                        text =
                            formatDateTime(
                                completedAt
                            ),
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {
                ValueBlock(
                    label = "Download",
                    value =
                        formatBytes(totalRx)
                )

                ValueBlock(
                    label = "Upload",
                    value =
                        formatBytes(totalTx)
                )

                ValueBlock(
                    label = "Gesamt",
                    value =
                        formatBytes(
                            totalTraffic
                        )
                )
            }

            if (
                strongestResult != null &&
                totalTraffic > 0L
            ) {
                StrongestConsumerCard(
                    result = strongestResult,
                    totalTraffic = totalTraffic
                )
            }

            SummaryLine(
                label = "Netzwerk",
                value =
                    buildString {
                        append("WLAN ")
                        append(
                            formatBytes(
                                totalWifi
                            )
                        )

                        append(" · Mobilfunk ")
                        append(
                            formatBytes(
                                totalMobile
                            )
                        )

                        if (totalOther > 0L) {
                            append(" · Sonstige ")
                            append(
                                formatBytes(
                                    totalOther
                                )
                            )
                        }
                    }
            )

            SummaryLine(
                label = "Nutzungsart",
                value =
                    "Vordergrund " +
                        formatBytes(
                            totalForeground
                        ) +
                        " · Hintergrund " +
                        formatBytes(
                            totalDefault
                        )
            )

            HorizontalDivider()

            Text(
                text = "Aktivste Apps und Dienste",
                fontSize = 17.sp,
                fontWeight =
                    FontWeight.SemiBold
            )

            visibleResults.forEachIndexed {
                    index,
                    result ->

                ResultRow(
                    rank = index + 1,
                    result = result,
                    startedAt = startedAt,
                    completedAt = completedAt
                )

                if (
                    index <
                    visibleResults.lastIndex
                ) {
                    HorizontalDivider(
                        color =
                            MaterialTheme
                                .colorScheme
                                .outlineVariant
                                .copy(
                                    alpha = 0.45f
                                )
                    )
                }
            }

            if (results.size > 8) {
                OutlinedButton(
                    onClick = {
                        showAllResults =
                            !showAllResults
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (showAllResults) {
                            "Weniger anzeigen"
                        } else {
                            "Alle ${results.size} Einträge anzeigen"
                        }
                    )
                }
            }

            Text(
                text =
                    "„Hintergrund“ entspricht der Android-NetStats-Klasse „Standard“. Die Zuordnung beschreibt die systemseitige Zählerklasse und nicht zwingend den sichtbaren App-Zustand.",
                fontSize = 12.sp,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun StrongestConsumerCard(
    result: AppTrafficResult,
    totalTraffic: Long
) {
    val share =
        if (totalTraffic > 0L) {
            result.totalBytes.toDouble() /
                totalTraffic.toDouble() *
                100.0
        } else {
            0.0
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme
                        .colorScheme
                        .primaryContainer
            )
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement =
                Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Hauptverbraucher",
                fontSize = 12.sp,
                color =
                    MaterialTheme
                        .colorScheme
                        .onPrimaryContainer
                        .copy(alpha = 0.78f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Text(
                    text = result.appName,
                    modifier =
                        Modifier.weight(1f),
                    fontWeight =
                        FontWeight.SemiBold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer
                )

                Text(
                    text =
                        formatBytes(
                            result.totalBytes
                        ),
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer
                )
            }

            Text(
                text =
                    String.format(
                        Locale.GERMANY,
                        "%.1f %% des gesamten Verkehrs",
                        share
                    ),
                fontSize = 13.sp,
                color =
                    MaterialTheme
                        .colorScheme
                        .onPrimaryContainer
            )
        }
    }
}

@Composable
private fun HistoryCard(
    sessions: List<MeasurementSession>,
    selectedSessionId: String?,
    onOpen: (MeasurementSession) -> Unit,
    onRename: (String, String) -> Unit,
    onShare: (MeasurementSession) -> Unit,
    onExport: (MeasurementSession) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var showAll by
        androidx.compose.runtime.remember {
            mutableStateOf(false)
        }

    var clearConfirmation by
        remember {
            mutableStateOf(false)
        }

    val visibleSessions =
        if (showAll) {
            sessions
        } else {
            sessions.take(5)
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme
                        .colorScheme
                        .surface
            )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement =
                Arrangement.spacedBy(13.dp)
        ) {
            Text(
                text = "Sitzungsverlauf",
                fontSize = 21.sp,
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                text =
                    "${sessions.size} gespeicherte " +
                        if (sessions.size == 1) {
                            "Messung"
                        } else {
                            "Messungen"
                        },
                fontSize = 13.sp,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            HorizontalDivider()

            visibleSessions.forEachIndexed {
                    index,
                    session ->

                HistoryRow(
                    session = session,
                    selected =
                        session.id ==
                            selectedSessionId,
                    onOpen = {
                        onOpen(session)
                    },
                    onRename = { newName ->
                        onRename(
                            session.id,
                            newName
                        )
                    },
                    onShare = {
                        onShare(session)
                    },
                    onExport = {
                        onExport(session)
                    },
                    onDelete = {
                        onDelete(session.id)
                    }
                )

                if (
                    index <
                    visibleSessions.lastIndex
                ) {
                    HorizontalDivider(
                        color =
                            MaterialTheme
                                .colorScheme
                                .outlineVariant
                                .copy(alpha = 0.45f)
                    )
                }
            }

            if (sessions.size > 5) {
                OutlinedButton(
                    onClick = {
                        showAll = !showAll
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (showAll) {
                            "Weniger anzeigen"
                        } else {
                            "Alle ${sessions.size} Sitzungen anzeigen"
                        }
                    )
                }
            }

            HorizontalDivider()

            if (clearConfirmation) {
                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    shape =
                        RoundedCornerShape(16.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .errorContainer
                        )
                ) {
                    Column(
                        modifier =
                            Modifier.padding(14.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text =
                                "Wirklich alle Sitzungen löschen?",
                            fontWeight =
                                FontWeight.SemiBold,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onErrorContainer
                        )

                        Text(
                            text =
                                "Alle gespeicherten Messungen und die aktuell angezeigte Auswertung werden dauerhaft entfernt.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onErrorContainer
                        )

                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clearConfirmation = false
                                },
                                modifier =
                                    Modifier.weight(1f)
                            ) {
                                Text("Abbrechen")
                            }

                            Button(
                                onClick = {
                                    clearConfirmation = false
                                    onClearAll()
                                },
                                modifier =
                                    Modifier.weight(1f),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor =
                                            MaterialTheme
                                                .colorScheme
                                                .error,
                                        contentColor =
                                            MaterialTheme
                                                .colorScheme
                                                .onError
                                    )
                            ) {
                                Text("Alle löschen")
                            }
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = {
                        clearConfirmation = true
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Alle Sitzungen löschen"
                    )
                }
            }

            Text(
                text =
                    "NetSession speichert höchstens $MAX_HISTORY_ENTRIES Sitzungen. Danach wird die älteste Sitzung automatisch entfernt.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryRow(
    session: MeasurementSession,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var deleteConfirmation by
        remember(
            session.id
        ) {
            mutableStateOf(false)
        }

    var renameVisible by
        remember(
            session.id
        ) {
            mutableStateOf(false)
        }

    var renameValue by
        remember(
            session.id,
            session.name
        ) {
            mutableStateOf(
                session.name
            )
        }

    Column(
        modifier =
            Modifier.padding(
                vertical = 5.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.Top
        ) {
            Column(
                modifier =
                    Modifier.weight(1f)
            ) {
                Text(
                    text = session.name,
                    fontWeight =
                        FontWeight.SemiBold
                )

                Text(
                    text =
                        formatDateTime(
                            session.completedAt
                        ) +
                            " · " +
                            formatDuration(
                                session.durationMillis
                            ),
                    fontSize = 12.sp,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                Text(
                    text =
                        formatBytes(
                            session.totalBytes
                        ) +
                            " · " +
                            session.strongestConsumer,
                    fontSize = 13.sp,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    maxLines = 2
                )
            }

            if (selected) {
                Text(
                    text = "Geöffnet",
                    fontSize = 12.sp,
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary,
                    fontWeight =
                        FontWeight.SemiBold
                )
            }
        }

        if (renameVisible) {
            OutlinedTextField(
                value = renameValue,
                onValueChange = {
                    renameValue =
                        it.take(60)
                },
                modifier =
                    Modifier.fillMaxWidth(),
                singleLine = true,
                label = {
                    Text(
                        "Sitzungsname"
                    )
                }
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        renameValue =
                            session.name
                        renameVisible = false
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("Abbrechen")
                }

                Button(
                    onClick = {
                        val cleanName =
                            renameValue.trim()

                        if (cleanName.isNotBlank()) {
                            onRename(cleanName)
                            renameVisible = false
                        }
                    },
                    enabled =
                        renameValue
                            .trim()
                            .isNotBlank(),
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("Speichern")
                }
            }
        } else if (deleteConfirmation) {
            Text(
                text =
                    "Diese Sitzung wirklich löschen?",
                fontSize = 13.sp,
                color =
                    MaterialTheme
                        .colorScheme
                        .error
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        deleteConfirmation = false
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("Abbrechen")
                }

                Button(
                    onClick = {
                        deleteConfirmation = false
                        onDelete()
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("Löschen")
                }
            }
        } else {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpen,
                    enabled = !selected,
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text(
                        if (selected) {
                            "Geöffnet"
                        } else {
                            "Öffnen"
                        }
                    )
                }

                OutlinedButton(
                    onClick = {
                        renameValue =
                            session.name
                        renameVisible = true
                    },
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("Umbenennen")
                }
            }

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onShare,
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("Teilen")
                }

                OutlinedButton(
                    onClick = onExport,
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text(
                        "TXT speichern",
                        maxLines = 1
                    )
                }
            }

            TextButton(
                onClick = {
                    deleteConfirmation = true
                },
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text("Sitzung löschen")
            }
        }
    }
}

@Composable
private fun SummaryLine(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement =
            Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Text(
            text = value,
            fontSize = 14.sp,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun ValueBlock(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Text(
            text = value,
            fontWeight =
                FontWeight.SemiBold
        )
    }
}

@Composable
private fun ResultRow(
    rank: Int,
    result: AppTrafficResult,
    startedAt: Long,
    completedAt: Long
) {
    var detailsVisible by
        androidx.compose.runtime.remember(
            result.uid,
            result.totalBytes
        ) {
            mutableStateOf(false)
        }

    Column(
        modifier =
            Modifier.padding(
                vertical = 6.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Text(
                text = "$rank.",
                modifier =
                    Modifier.width(30.dp),
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {
                Text(
                    text = result.appName,
                    fontWeight =
                        FontWeight.SemiBold
                )

                Text(
                    text =
                        result.packageSummary(),
                    fontSize = 11.sp,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    maxLines = 1
                )
            }

            Text(
                text =
                    formatBytes(
                        result.totalBytes
                    ),
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 30.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Text(
                text =
                    "↓ ${formatBytes(result.rxBytes)}  " +
                        "↑ ${formatBytes(result.txBytes)}",
                modifier =
                    Modifier.weight(1f),
                fontSize = 13.sp,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            TextButton(
                onClick = {
                    detailsVisible =
                        !detailsVisible
                },
                contentPadding =
                    PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp
                    )
            ) {
                Text(
                    text =
                        if (detailsVisible) {
                            "Weniger"
                        } else {
                            "Details"
                        },
                    fontSize = 12.sp
                )
            }
        }

        if (detailsVisible) {
            Column(
                modifier =
                    Modifier.padding(
                        start = 30.dp,
                        top = 1.dp
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(3.dp)
            ) {
                if (
                    startedAt > 0L &&
                    completedAt > 0L
                ) {
                    Text(
                        text =
                            "Erfasst: " +
                                formatMeasurementPeriod(
                                    startedAt =
                                        startedAt,
                                    completedAt =
                                        completedAt
                                ),
                        fontSize = 12.sp,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                Text(
                    text =
                        buildString {
                            append("WLAN ")
                            append(
                                formatBytes(
                                    result.wifiBytes
                                )
                            )

                            append(" · Mobilfunk ")
                            append(
                                formatBytes(
                                    result.mobileBytes
                                )
                            )

                            if (
                                result.otherBytes > 0L
                            ) {
                                append(" · Sonstige ")
                                append(
                                    formatBytes(
                                        result.otherBytes
                                    )
                                )
                            }
                        },
                    fontSize = 12.sp,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                Text(
                    text =
                        "Vordergrund " +
                            formatBytes(
                                result.foregroundBytes
                            ) +
                            " · Hintergrund " +
                            formatBytes(
                                result.defaultBytes
                            ),
                    fontSize = 12.sp,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                if (
                    result.packageNames.size > 1
                ) {
                    Text(
                        text =
                            "${result.packageNames.size} Pakete verwenden diese UID gemeinsam.",
                        fontSize = 12.sp,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun AppTrafficResult.packageSummary(): String =
    when {
        uid == 0 ->
            "Systemdienst · UID 0"

        uid == 1000 ->
            "${packageNames.size} gemeinsam genutzte Systempakete"

        uid == 1001 ->
            "Telefonie-System · UID 1001"

        uid == 1020 ->
            "Systemdienst · UID 1020"

        uid == 1073 ->
            "Netzwerkdienst · UID 1073"

        uid == 2000 ->
            "Shell-Systemdienst · UID 2000"

        packageNames.isEmpty() ->
            "UID $uid · nicht aufgelöst"

        packageNames.size == 1 ->
            packageNames.first()

        else ->
            "${packageNames.size} zugehörige Pakete · UID $uid"
    }

private fun formatMeasurementPeriod(
    startedAt: Long,
    completedAt: Long
): String {
    val dateFormat =
        java.text.SimpleDateFormat(
            "dd.MM.yyyy",
            Locale.GERMANY
        )

    val timeFormat =
        java.text.SimpleDateFormat(
            "HH:mm",
            Locale.GERMANY
        )

    val startDate =
        java.util.Date(startedAt)

    val endDate =
        java.util.Date(completedAt)

    val sameDay =
        dateFormat.format(startDate) ==
            dateFormat.format(endDate)

    return if (sameDay) {
        dateFormat.format(startDate) +
            " · " +
            timeFormat.format(startDate) +
            "–" +
            timeFormat.format(endDate)
    } else {
        dateFormat.format(startDate) +
            " · " +
            timeFormat.format(startDate) +
            " bis " +
            dateFormat.format(endDate) +
            " · " +
            timeFormat.format(endDate)
    }
}

private fun formatShortDateTime(
    timeMillis: Long
): String =
    java.text.SimpleDateFormat(
        "dd.MM. · HH:mm",
        Locale.GERMANY
    ).format(
        java.util.Date(timeMillis)
    )

private fun formatDateTime(
    timeMillis: Long
): String =
    java.text.SimpleDateFormat(
        "dd.MM.yyyy · HH:mm",
        Locale.GERMANY
    ).format(
        java.util.Date(timeMillis)
    )

private fun formatDuration(
    durationMillis: Long
): String {
    val safeMillis =
        max(0L, durationMillis)

    val totalSeconds =
        safeMillis / 1_000L

    val hours =
        totalSeconds / 3_600L

    val minutes =
        (totalSeconds % 3_600L) / 60L

    val seconds =
        totalSeconds % 60L

    return String.format(
        Locale.GERMANY,
        "%02d:%02d:%02d",
        hours,
        minutes,
        seconds
    )
}

private fun formatBytes(
    bytes: Long
): String {
    val safeBytes =
        max(0L, bytes)

    return when {
        safeBytes >= 1_000_000_000L ->
            String.format(
                Locale.GERMANY,
                "%.2f GB",
                safeBytes /
                    1_000_000_000.0
            )

        safeBytes >= 1_000_000L ->
            String.format(
                Locale.GERMANY,
                "%.2f MB",
                safeBytes /
                    1_000_000.0
            )

        safeBytes >= 1_000L ->
            String.format(
                Locale.GERMANY,
                "%.1f KB",
                safeBytes /
                    1_000.0
            )

        else ->
            "$safeBytes B"
    }
}

private fun formatResultsReport(
    sessionName: String,
    results: List<AppTrafficResult>,
    measurementDuration: Long,
    startedAt: Long,
    completedAt: Long
): String =
    buildString {
        val totalRx =
            results.sumOf {
                it.rxBytes
            }

        val totalTx =
            results.sumOf {
                it.txBytes
            }

        val totalWifi =
            results.sumOf {
                it.wifiBytes
            }

        val totalMobile =
            results.sumOf {
                it.mobileBytes
            }

        val totalOther =
            results.sumOf {
                it.otherBytes
            }

        val totalForeground =
            results.sumOf {
                it.foregroundBytes
            }

        val totalDefault =
            results.sumOf {
                it.defaultBytes
            }

        appendLine("NetSession v0.1h2")
        appendLine(
            "Netzwerkverbrauch im Messzeitraum"
        )
        appendLine(
            "Sitzung: " +
                sessionName.ifBlank {
                    "Ohne Namen"
                }
        )
        appendLine()

        if (measurementDuration > 0L) {
            appendLine(
                "Messdauer: " +
                    formatDuration(
                        measurementDuration
                    )
            )
        }

        if (
            startedAt > 0L &&
            completedAt > 0L
        ) {
            appendLine(
                "Messzeitraum: " +
                    formatMeasurementPeriod(
                        startedAt = startedAt,
                        completedAt = completedAt
                    )
            )
        }

        if (completedAt > 0L) {
            appendLine(
                "Abgeschlossen: " +
                    formatDateTime(
                        completedAt
                    )
            )
        }

        appendLine(
            "Einträge: ${results.size}"
        )
        appendLine()

        appendLine(
            "Download: " +
                formatBytes(
                    totalRx
                )
        )

        appendLine(
            "Upload: " +
                formatBytes(
                    totalTx
                )
        )

        appendLine(
            "Gesamt: " +
                formatBytes(
                    totalRx +
                        totalTx
                )
        )

        appendLine()
        appendLine(
            "WLAN: " +
                formatBytes(
                    totalWifi
                )
        )

        appendLine(
            "Mobilfunk: " +
                formatBytes(
                    totalMobile
                )
        )

        if (totalOther > 0L) {
            appendLine(
                "Sonstige Netze: " +
                    formatBytes(
                        totalOther
                    )
            )
        }

        appendLine()
        appendLine(
            "Vordergrund: " +
                formatBytes(
                    totalForeground
                )
        )

        appendLine(
            "Hintergrund (Android: Standard): " +
                formatBytes(
                    totalDefault
                )
        )

        appendLine()
        appendLine(
            "Aktivste Apps und UIDs"
        )
        appendLine()

        results.forEachIndexed {
                index,
                result ->

            appendLine(
                "${index + 1}. " +
                    result.appName
            )

            appendLine(
                "   ${result.packageSummary()}"
            )

            if (
                startedAt > 0L &&
                completedAt > 0L
            ) {
                appendLine(
                    "   Erfasst: " +
                        formatMeasurementPeriod(
                            startedAt = startedAt,
                            completedAt = completedAt
                        )
                )
            }

            appendLine(
                "   Gesamt: " +
                    formatBytes(
                        result.totalBytes
                    )
            )

            appendLine(
                "   Download: " +
                    formatBytes(
                        result.rxBytes
                    )
            )

            appendLine(
                "   Upload: " +
                    formatBytes(
                        result.txBytes
                    )
            )

            appendLine(
                "   WLAN: " +
                    formatBytes(
                        result.wifiBytes
                    )
            )

            appendLine(
                "   Mobilfunk: " +
                    formatBytes(
                        result.mobileBytes
                    )
            )

            if (result.otherBytes > 0L) {
                appendLine(
                    "   Sonstige Netze: " +
                        formatBytes(
                            result.otherBytes
                        )
                )
            }

            appendLine(
                "   Vordergrund: " +
                    formatBytes(
                        result.foregroundBytes
                    )
            )

            appendLine(
                "   Hintergrund (Android: Standard): " +
                    formatBytes(
                        result.defaultBytes
                    )
            )

            if (
                result.packageNames.size > 1
            ) {
                appendLine(
                    "   Pakete: " +
                        result.packageNames
                            .joinToString()
                )
            }

            appendLine()
        }

        appendLine(
            "Hinweis: Vordergrund und Hintergrund entsprechen den Android-NetStats-Klassen FOREGROUND und DEFAULT."
        )
    }

private fun formatSessionReport(
    session: MeasurementSession
): String =
    formatResultsReport(
        sessionName = session.name,
        results = session.results,
        measurementDuration =
            session.durationMillis,
        startedAt = session.startedAt,
        completedAt = session.completedAt
    )

private fun buildSessionFileName(
    session: MeasurementSession
): String {
    val datePart =
        java.text.SimpleDateFormat(
            "yyyy-MM-dd_HH-mm",
            Locale.GERMANY
        ).format(
            java.util.Date(
                session.completedAt
            )
        )

    val safeName =
        session.name
            .trim()
            .lowercase(Locale.GERMANY)
            .replace(
                Regex("[^a-z0-9äöüß]+"),
                "_"
            )
            .trim('_')
            .take(40)
            .ifBlank {
                "messung"
            }

    return "NetSession_" +
        safeName +
        "_" +
        datePart +
        ".txt"
}

private fun shareTextReport(
    context: Context,
    subject: String,
    text: String
) {
    val shareIntent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(
                Intent.EXTRA_SUBJECT,
                subject
            )
            .putExtra(
                Intent.EXTRA_TEXT,
                text
            )

    context.startActivity(
        Intent.createChooser(
            shareIntent,
            "Sitzungsbericht teilen"
        )
    )
}

private fun encodeResults(
    results: List<AppTrafficResult>
): String {
    val array = JSONArray()

    results.forEach { result ->
        array.put(
            JSONObject()
                .put("uid", result.uid)
                .put("appName", result.appName)
                .put(
                    "packageNames",
                    JSONArray(
                        result.packageNames
                    )
                )
                .put("rxBytes", result.rxBytes)
                .put("txBytes", result.txBytes)
                .put(
                    "wifiRxBytes",
                    result.wifiRxBytes
                )
                .put(
                    "wifiTxBytes",
                    result.wifiTxBytes
                )
                .put(
                    "mobileRxBytes",
                    result.mobileRxBytes
                )
                .put(
                    "mobileTxBytes",
                    result.mobileTxBytes
                )
                .put(
                    "otherRxBytes",
                    result.otherRxBytes
                )
                .put(
                    "otherTxBytes",
                    result.otherTxBytes
                )
                .put(
                    "foregroundRxBytes",
                    result.foregroundRxBytes
                )
                .put(
                    "foregroundTxBytes",
                    result.foregroundTxBytes
                )
                .put(
                    "defaultRxBytes",
                    result.defaultRxBytes
                )
                .put(
                    "defaultTxBytes",
                    result.defaultTxBytes
                )
        )
    }

    return array.toString()
}

private fun decodeResults(
    text: String?
): List<AppTrafficResult> {
    if (text.isNullOrBlank()) {
        return emptyList()
    }

    return runCatching {
        val array = JSONArray(text)

        buildList {
            for (
                index in 0 until array.length()
            ) {
                val item =
                    array.getJSONObject(index)

                val packages =
                    item.optJSONArray(
                        "packageNames"
                    )

                val packageNames =
                    buildList {
                        if (packages != null) {
                            for (
                                packageIndex in
                                0 until packages.length()
                            ) {
                                add(
                                    packages.getString(
                                        packageIndex
                                    )
                                )
                            }
                        }
                    }

                add(
                    AppTrafficResult(
                        uid =
                            item.getInt("uid"),
                        appName =
                            item.getString(
                                "appName"
                            ),
                        packageNames =
                            packageNames,
                        rxBytes =
                            item.getLong(
                                "rxBytes"
                            ),
                        txBytes =
                            item.getLong(
                                "txBytes"
                            ),
                        wifiRxBytes =
                            item.getLong(
                                "wifiRxBytes"
                            ),
                        wifiTxBytes =
                            item.getLong(
                                "wifiTxBytes"
                            ),
                        mobileRxBytes =
                            item.getLong(
                                "mobileRxBytes"
                            ),
                        mobileTxBytes =
                            item.getLong(
                                "mobileTxBytes"
                            ),
                        otherRxBytes =
                            item.getLong(
                                "otherRxBytes"
                            ),
                        otherTxBytes =
                            item.getLong(
                                "otherTxBytes"
                            ),
                        foregroundRxBytes =
                            item.getLong(
                                "foregroundRxBytes"
                            ),
                        foregroundTxBytes =
                            item.getLong(
                                "foregroundTxBytes"
                            ),
                        defaultRxBytes =
                            item.getLong(
                                "defaultRxBytes"
                            ),
                        defaultTxBytes =
                            item.getLong(
                                "defaultTxBytes"
                            )
                    )
                )
            }
        }
    }.getOrElse {
        emptyList()
    }
}

private fun encodeHistory(
    sessions: List<MeasurementSession>
): String {
    val array = JSONArray()

    sessions.forEach { session ->
        array.put(
            JSONObject()
                .put("id", session.id)
                .put("name", session.name)
                .put(
                    "startedAt",
                    session.startedAt
                )
                .put(
                    "durationMillis",
                    session.durationMillis
                )
                .put(
                    "completedAt",
                    session.completedAt
                )
                .put(
                    "results",
                    JSONArray(
                        encodeResults(
                            session.results
                        )
                    )
                )
        )
    }

    return array.toString()
}

private fun decodeHistory(
    text: String?
): List<MeasurementSession> {
    if (text.isNullOrBlank()) {
        return emptyList()
    }

    return runCatching {
        val array = JSONArray(text)

        buildList {
            for (
                index in 0 until array.length()
            ) {
                val item =
                    array.getJSONObject(index)

                add(
                    MeasurementSession(
                        id =
                            item.optString(
                                "id",
                                "session-$index"
                            ),
                        name =
                            item.optString(
                                "name",
                                "Messung"
                            ),
                        startedAt =
                            item.optLong(
                                "startedAt",
                                max(
                                    0L,
                                    item.optLong(
                                        "completedAt",
                                        0L
                                    ) -
                                        item.optLong(
                                            "durationMillis",
                                            0L
                                        )
                                )
                            ),
                        durationMillis =
                            item.optLong(
                                "durationMillis",
                                0L
                            ),
                        completedAt =
                            item.optLong(
                                "completedAt",
                                0L
                            ),
                        results =
                            decodeResults(
                                item
                                    .optJSONArray(
                                        "results"
                                    )
                                    ?.toString()
                            )
                    )
                )
            }
        }
            .sortedByDescending {
                it.completedAt
            }
            .take(
                MAX_HISTORY_ENTRIES
            )
    }.getOrElse {
        emptyList()
    }
}

private fun copyText(
    context: Context,
    label: String,
    text: String
) {
    val clipboard =
        context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            label,
            text
        )
    )
}

@Composable
private fun NetSessionTheme(
    content: @Composable () -> Unit
) {
    val darkTheme =
        isSystemInDarkTheme()

    val colorScheme =
        if (darkTheme) {
            darkColorScheme(
                primary =
                    Color(0xFF8DB8FF),
                onPrimary =
                    Color(0xFF002E68),
                primaryContainer =
                    Color(0xFF17477F),
                onPrimaryContainer =
                    Color(0xFFD6E4FF),
                background =
                    Color(0xFF090C10),
                onBackground =
                    Color(0xFFE1E6EE),
                surface =
                    Color(0xFF12161C),
                onSurface =
                    Color(0xFFE1E6EE),
                surfaceVariant =
                    Color(0xFF191E26),
                onSurfaceVariant =
                    Color(0xFFBAC2CF),
                outline =
                    Color(0xFF8B93A1),
                outlineVariant =
                    Color(0xFF404752),
                error =
                    Color(0xFFFFB4AB)
            )
        } else {
            lightColorScheme(
                primary =
                    Color(0xFF27579E),
                onPrimary =
                    Color.White,
                primaryContainer =
                    Color(0xFFD7E6FF),
                onPrimaryContainer =
                    Color(0xFF0A315F),
                background =
                    Color(0xFFF4F6FA),
                onBackground =
                    Color(0xFF171A1F),
                surface =
                    Color(0xFFFFFFFF),
                onSurface =
                    Color(0xFF171A1F),
                surfaceVariant =
                    Color(0xFFE7ECF4),
                onSurfaceVariant =
                    Color(0xFF4F5662),
                outline =
                    Color(0xFF747C89),
                outlineVariant =
                    Color(0xFFC4CAD4),
                error =
                    Color(0xFFBA1A1A)
            )
        }

    val view =
        LocalView.current

    val context =
        LocalContext.current

    SideEffect {
        val activity =
            context as? Activity

        if (activity != null) {
            val controller =
                WindowCompat.getInsetsController(
                    activity.window,
                    view
                )

            controller.isAppearanceLightStatusBars =
                !darkTheme

            controller.isAppearanceLightNavigationBars =
                !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

