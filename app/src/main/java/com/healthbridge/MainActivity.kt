package com.healthbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// ── Colors ────────────────────────────────────────────────────────────────────
val BgColor     = Color(0xFF090910)
val SurfaceColor = Color(0xFF11111C)
val BorderColor = Color(0xFF1E1E33)
val GreenColor  = Color(0xFF00E87A)
val BlueColor   = Color(0xFF00AAFF)
val OrangeColor = Color(0xFFFF6B35)
val TextColor   = Color(0xFFE2E2F0)
val SubColor    = Color(0xFF6868A0)

class MainActivity : ComponentActivity() {

    private lateinit var healthConnectClient: HealthConnectClient

    private val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
    )

    private var pendingServerUrl: String = ""

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(PERMISSIONS)) {
            triggerSync(pendingServerUrl)
        } else {
            statusText.value = "⚠️ Permisos denegados en Health Connect. Ábrelo y concede los permisos a HealthBridge."
        }
    }

    // Shared UI state
    private val statusText = mutableStateOf("Listo para sincronizar")
    private val isLoading  = mutableStateOf(false)
    private val lastSync   = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("bridge", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "http://192.168.1.1:8000") ?: "http://192.168.1.1:8000"

        val availability = HealthConnectClient.getSdkStatus(this)
        val hcAvailable = availability == HealthConnectClient.SDK_AVAILABLE

        if (hcAvailable) {
            healthConnectClient = HealthConnectClient.getOrCreate(this)
        }

        setContent {
            var serverUrl by remember { mutableStateOf(savedUrl) }

            MaterialTheme {
                HealthBridgeScreen(
                    serverUrl = serverUrl,
                    onServerUrlChange = {
                        serverUrl = it
                        prefs.edit().putString("server_url", it).apply()
                    },
                    status = statusText.value,
                    isLoading = isLoading.value,
                    lastSync = lastSync.value,
                    hcAvailable = hcAvailable,
                    onSync = {
                        if (hcAvailable) {
                            checkPermissionsAndSync(serverUrl)
                        }
                    }
                )
            }
        }
    }

    private fun checkPermissionsAndSync(serverUrl: String) {
        pendingServerUrl = serverUrl
        statusText.value = "Abriendo permisos de Health Connect…"
        // Try to open Health Connect permissions page for this app directly
        val opened = tryOpenHealthConnectPermissions()
        if (!opened) {
            requestPermissions.launch(PERMISSIONS)
        }
    }

    private fun tryOpenHealthConnectPermissions(): Boolean {
        val intents = listOf(
            Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName),
            Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName),
            Intent(Intent.ACTION_VIEW, Uri.parse("package:$packageName")).apply {
                setPackage("com.google.android.apps.healthdata")
            }
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                statusText.value = "Busca 'HealthBridge' en Health Connect y activa TODOS los permisos. Luego vuelve aquí y presiona Sincronizar."
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun triggerSync(serverUrl: String? = null) {
        val url = serverUrl ?: getSharedPreferences("bridge", Context.MODE_PRIVATE)
            .getString("server_url", "") ?: return

        isLoading.value = true
        statusText.value = "Leyendo Health Connect…"

        lifecycleScope.launch {
            try {
                val data = readTodayData()
                statusText.value = "Enviando datos al servidor…"
                val result = sendToServer(url, data)
                statusText.value = if (result) {
                    "✓ Sincronizado correctamente"
                } else {
                    "✗ Error al conectar con el servidor"
                }
                lastSync.value = java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            } catch (e: Exception) {
                statusText.value = "Error: ${e.message}"
            } finally {
                isLoading.value = false
            }
        }
    }

    // ── Health Connect reads ──────────────────────────────────────────────────

    private suspend fun readTodayData(): JSONObject = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val zone  = ZoneId.systemDefault()
        val start = today.atStartOfDay(zone).toInstant()
        val end   = today.plusDays(1).atStartOfDay(zone).toInstant()
        val filter = TimeRangeFilter.between(start, end)

        // Steps
        val stepsReq = ReadRecordsRequest(StepsRecord::class, filter)
        val steps = healthConnectClient.readRecords(stepsReq).records.sumOf { it.count }

        // Distance
        val distReq = ReadRecordsRequest(DistanceRecord::class, filter)
        val distanceM = healthConnectClient.readRecords(distReq).records.sumOf { it.distance.inMeters }
        val distanceKm = (distanceM / 1000.0 * 100).toLong() / 100.0

        // Active calories
        val activeCalReq = ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, filter)
        val activeCal = healthConnectClient.readRecords(activeCalReq).records
            .sumOf { it.energy.inKilocalories }

        // Total calories
        val totalCalReq = ReadRecordsRequest(TotalCaloriesBurnedRecord::class, filter)
        val totalCal = healthConnectClient.readRecords(totalCalReq).records
            .sumOf { it.energy.inKilocalories }
        val caloriesBurned = if (totalCal > 0) totalCal else activeCal

        // Heart rate
        val hrReq = ReadRecordsRequest(HeartRateRecord::class, filter)
        val hrSamples = healthConnectClient.readRecords(hrReq).records
            .flatMap { it.samples }.map { it.beatsPerMinute }
        val maxHr = hrSamples.maxOrNull()?.toInt() ?: 0
        val avgHr = if (hrSamples.isNotEmpty()) hrSamples.average().toInt() else 0

        // Resting heart rate
        val rhrReq = ReadRecordsRequest(RestingHeartRateRecord::class, filter)
        val rhr = healthConnectClient.readRecords(rhrReq).records
            .lastOrNull()?.beatsPerMinute?.toInt() ?: 0

        // Floors
        val floorsReq = ReadRecordsRequest(FloorsClimbedRecord::class, filter)
        val floors = healthConnectClient.readRecords(floorsReq).records
            .sumOf { it.floors }.toInt()

        // Sleep (last night: yesterday 18:00 → today 12:00)
        val sleepStart = today.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val sleepEnd   = today.atTime(12, 0).atZone(zone).toInstant()
        val sleepFilter = TimeRangeFilter.between(sleepStart, sleepEnd)
        val sleepReq = ReadRecordsRequest(SleepSessionRecord::class, sleepFilter)
        val sleepSessions = healthConnectClient.readRecords(sleepReq).records
        var sleepTotalSec = 0L
        var deepSec = 0L
        var remSec  = 0L
        for (session in sleepSessions) {
            val duration = java.time.Duration.between(session.startTime, session.endTime).seconds
            sleepTotalSec += duration
            for (stage in session.stages) {
                val stageSec = java.time.Duration.between(stage.startTime, stage.endTime).seconds
                when (stage.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> deepSec += stageSec
                    SleepSessionRecord.STAGE_TYPE_REM  -> remSec  += stageSec
                }
            }
        }
        val sleepHours = (sleepTotalSec / 36.0).toLong() / 100.0
        val deepHours  = (deepSec / 36.0).toLong() / 100.0
        val remHours   = (remSec / 36.0).toLong() / 100.0

        // Exercises
        val exReq = ReadRecordsRequest(ExerciseSessionRecord::class, filter)
        val exercises = healthConnectClient.readRecords(exReq).records
        val activitiesArr = JSONArray()
        var activeMinutes = 0
        for (ex in exercises) {
            val durationMin = java.time.Duration.between(ex.startTime, ex.endTime).toMinutes().toInt()
            activeMinutes += durationMin
            val obj = JSONObject().apply {
                put("name", ex.title ?: exerciseTypeName(ex.exerciseType))
                put("type", exerciseTypeName(ex.exerciseType))
                put("duration_min", durationMin)
                put("calories", 0)
                put("distance_km", 0.0)
                put("avg_hr", avgHr)
                put("start_time", ex.startTime.atZone(zone).toLocalTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
            }
            activitiesArr.put(obj)
        }

        JSONObject().apply {
            put("date", today.toString())
            put("steps", steps)
            put("calories_burned", (caloriesBurned * 10).toLong() / 10.0)
            put("active_calories", (activeCal * 10).toLong() / 10.0)
            put("distance_km", distanceKm)
            put("active_minutes", activeMinutes)
            put("resting_heart_rate", rhr)
            put("max_heart_rate", maxHr)
            put("floors_climbed", floors)
            put("sleep_hours", sleepHours)
            put("sleep_deep_h", deepHours)
            put("sleep_rem_h", remHours)
            put("sleep_score", 0)
            put("activities", activitiesArr)
            put("source", "health_connect")
        }
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING         -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING         -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING          -> "cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL   -> "swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength_training"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA            -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING          -> "hiking"
        else                                                 -> "fitness_equipment"
    }

    private suspend fun sendToServer(url: String, data: JSONObject): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val syncUrl = url.trimEnd('/') + "/api/external/sync"
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val body = data.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(syncUrl).post(body).build()
                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
}

// ── Compose UI ────────────────────────────────────────────────────────────────

@Composable
fun HealthBridgeScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    status: String,
    isLoading: Boolean,
    lastSync: String,
    hcAvailable: Boolean,
    onSync: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "⬡ Health Bridge",
                    color = GreenColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Garmin Connect → Health Connect → PC",
                    color = SubColor,
                    fontSize = 12.sp
                )
            }

            // HC status badge
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (hcAvailable)
                    GreenColor.copy(alpha = 0.12f)
                else
                    OrangeColor.copy(alpha = 0.12f)
            ) {
                Text(
                    if (hcAvailable) "● Health Connect disponible"
                    else "● Health Connect no disponible",
                    color = if (hcAvailable) GreenColor else OrangeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // Server URL card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("URL del servidor PC", color = SubColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        placeholder = { Text("http://192.168.1.x:8000", color = SubColor, fontSize = 13.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GreenColor,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextColor,
                            unfocusedTextColor = TextColor,
                            cursorColor = GreenColor,
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Abre http://TU-IP:8000/connect en este teléfono para obtener la URL exacta",
                        color = SubColor,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }

            // Sync button
            Button(
                onClick = onSync,
                enabled = hcAvailable && !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GreenColor,
                    contentColor = Color.Black,
                    disabledContainerColor = GreenColor.copy(alpha = 0.3f),
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (isLoading) "Sincronizando…" else "Sincronizar ahora",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // Status
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SurfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Estado", color = SubColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(status, color = TextColor, fontSize = 13.sp)
                    if (lastSync.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Última sync: $lastSync", color = SubColor, fontSize = 11.sp)
                    }
                }
            }

            // What gets synced
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SurfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Datos que se sincronizan", color = SubColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    listOf(
                        "👟 Pasos y distancia",
                        "🔥 Calorías quemadas",
                        "❤️ Frecuencia cardíaca",
                        "😴 Sueño (total, profundo, REM)",
                        "🏋️ Ejercicios y actividades",
                        "🏠 Pisos subidos",
                    ).forEach {
                        Text(it, color = TextColor, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
