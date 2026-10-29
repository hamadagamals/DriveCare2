package com.drivecare.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.*

/**
 * Lightweight, server-driven Speed Bump and Road Hazard Manager for DriveCare.
 * 
 * Key Principles:
 * 1. 20 km Dynamic City Cache: Only holds bumps for the immediate ~20 km sector.
 * 2. Strict Kinematic Speed Gate: Bump recordings strictly rejected if speed > 21 km/h.
 * 3. Pattern Recognition: Deceleration (<21 km/h) -> +Z Shock -> Pitch/Inversion -> -Z Rebound -> Accel.
 * 4. Adaptive Community Verification: New bumps activate immediately; auto-deactivate after 3 negative passes.
 * 5. Audio Proximity Warning: 100m early alert + 20m final alert, toggleable mute.
 */
class SpeedBumpManager(
    private val context: Context,
    private val apiBaseUrlProvider: () -> String
) : SensorEventListener {

    companion object {
        private const val TAG = "SpeedBumpManager"
        private const val EARTH_RADIUS_METERS = 6371000.0
        private const val CACHE_REFRESH_DISTANCE_METERS = 5000.0 // 5 km move triggers 20km sector refresh
        private const val SPEED_GATE_MAX_KMH = 21.0              // Hard limit: > 21 km/h never records a bump
        private const val SPEED_GATE_MIN_KMH = 3.5               // Car must be moving
        private const val EARLY_WARNING_METERS = 100.0
        private const val FINAL_WARNING_METERS = 25.0
        private const val PASS_CHECK_WINDOW_MS = 6000L
    }

    data class CachedHazard(
        val id: String,
        val lat: Double,
        val lng: Double,
        var directionDegrees: Double,
        var bidirectional: Boolean,
        var roadClass: String,
        var active: Boolean = true,
        var consecutiveNegativePasses: Int = 0,
        var needsMapMatch: Boolean = false
    )

    // Public state
    var isWarningEnabled: Boolean = true
    var isAudioMuted: Boolean = false
    var isMuted: Boolean
        get() = isAudioMuted
        set(value) { isAudioMuted = value }

    val alertSoundPlayer: AlertSoundPlayer = AlertSoundPlayer(context)

    var currentWarningText: String? = null
        private set
    var onWarningStateChanged: ((warningText: String?, stage: String?) -> Unit)? = null
    var onBumpDetectedNotice: ((message: String) -> Unit)? = null

    // Cache & state
    private val cachedHazards = CopyOnWriteArrayList<CachedHazard>()
    private var lastFetchLocation: Location? = null
    private val isFetching = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Proximity deduplication
    private var lastAlertedHazardId: String? = null
    private var lastAlertStage: String? = null
    private var lastAlertTimeMs: Long = 0L

    // Pass-check tracker: hazards currently within <= 25m
    private val activePassHazards = mutableMapOf<String, Long>() // id -> entryTimeMs

    // Kinematic detection window
    private var lastRecordedBumpTimeMs: Long = 0L
    private var positiveShockTimeMs: Long = 0L
    private var hasPositiveShock = false
    private var hasPitchRotation = false
    private var hasNegativeRebound = false
    private var lastBumpFeltTimeMs: Long = 0L
    private var filteredGravity: Float = 9.81f

    // Current vehicle telemetry
    private var latestGpsLocation: Location? = null
    private var latestGpsSpeedKmh: Double = 0.0
    private var latestObdSpeedKmh: Double? = null
    private var lastValidBearingDegrees: Double = 0.0
    private var hasValidBearing: Boolean = false
    private var hasGyroSensor: Boolean = false
    private var peakPositiveShockDelta: Float = 0f
    private val recentSpeedSamples = ArrayDeque<Pair<Long, Double>>() // (timestampMs, speedKmh)

    // Audio & Sensors
    private var toneGenerator: ToneGenerator? = null
    private var sensorManager: SensorManager? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator not available: ${e.message}")
        }
    }

    fun start() {
        try {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensorManager?.let { sm ->
                val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accel != null) {
                    sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
                }
                val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                if (gyro != null) {
                    hasGyroSensor = true
                    sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
                } else {
                    hasGyroSensor = false
                }
            }
            triggerDailyMapReconciliationIfNeeded()
            Log.i(TAG, "SpeedBumpManager started with sensor listeners")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sensor listeners: ${e.message}")
        }
    }

    fun stop() {
        try {
            sensorManager?.unregisterListener(this)
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SpeedBumpManager: ${e.message}")
        }
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        return isMuted
    }

    fun toggleWarning(): Boolean {
        isWarningEnabled = !isWarningEnabled
        if (!isWarningEnabled) {
            clearWarningState()
        }
        return isWarningEnabled
    }

    /**
     * Called whenever a new GPS fix arrives.
     * Evaluates 20km sector refresh, proximity warnings, and pass-checks.
     */
    fun onLocationChanged(location: Location, obdSpeedKmh: Double? = null) {
        latestGpsLocation = location
        latestGpsSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        latestObdSpeedKmh = obdSpeedKmh
        if (location.hasBearing() && (latestGpsSpeedKmh > 2.5 || (obdSpeedKmh ?: 0.0) > 2.5)) {
            lastValidBearingDegrees = location.bearing.toDouble()
            hasValidBearing = true
        }

        val carSpeed = getEffectiveSpeedKmh()
        val now = System.currentTimeMillis()
        synchronized(recentSpeedSamples) {
            recentSpeedSamples.addLast(Pair(now, carSpeed))
            while (recentSpeedSamples.isNotEmpty() && now - recentSpeedSamples.first().first > 8000L) {
                recentSpeedSamples.removeFirst()
            }
        }

        // 1. Dynamic 20km City Cache refresh check
        val lastLoc = lastFetchLocation
        if (lastLoc == null || distanceMeters(location.latitude, location.longitude, lastLoc.latitude, lastLoc.longitude) >= CACHE_REFRESH_DISTANCE_METERS) {
            fetchNearbyHazards(location.latitude, location.longitude, 20.0)
        }

        // 1.5. Once-per-day road map reconciliation for new bumps (counter-traffic fix)
        triggerDailyMapReconciliationIfNeeded()

        // 2. Proximity warning check
        checkProximityWarnings(location, carSpeed)

        // 3. Pass check validation (adaptive verification / negative pass)
        checkPassHazards(location, carSpeed)
    }

    /**
     * Returns effective speed: prefers ECU OBD wheel speed if available, otherwise GPS.
     */
    fun getEffectiveSpeedKmh(): Double {
        val obd = latestObdSpeedKmh
        return if (obd != null && obd >= 0.0) obd else latestGpsSpeedKmh
    }

    /**
     * Evaluates cached hazards ahead of the vehicle.
     */
    private fun checkProximityWarnings(currentLoc: Location, speedKmh: Double) {
        if (!isWarningEnabled || cachedHazards.isEmpty()) {
            clearWarningState()
            return
        }

        val hasBearingNow = currentLoc.hasBearing() || hasValidBearing
        val carBearing = if (currentLoc.hasBearing()) currentLoc.bearing.toDouble() else lastValidBearingDegrees
        var nearestWarning: Pair<CachedHazard, Double>? = null // Hazard to distance

        for (hazard in cachedHazards) {
            if (!hazard.active) continue
            val dist = distanceMeters(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)
            if (dist > EARLY_WARNING_METERS + 10.0) continue

            // Bearing from car to hazard
            val bearingToHazard = initialBearingDegrees(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)

            // Only warn if hazard is ahead of the car (within 45 degrees of travel direction)
            // and in the same lane travel direction (prevents warning after passing or on opposite lane)
            if (hasBearingNow) {
                if (angularDifference(carBearing, bearingToHazard) > 45.0) continue
                if (!hazard.bidirectional && angularDifference(carBearing, hazard.directionDegrees) > 55.0) continue
            } else if (speedKmh < 2.0) {
                continue
            }

            if (nearestWarning == null || dist < nearestWarning.second) {
                nearestWarning = Pair(hazard, dist)
            }
        }

        if (nearestWarning != null) {
            val (hazard, dist) = nearestWarning
            val stage = if (dist <= FINAL_WARNING_METERS) "FINAL" else "EARLY"
            val text = if (stage == "FINAL") "🛑 مطب الآن (${dist.toInt()}م)" else "⚠️ مطب بعد ${dist.toInt()}م"

            // Check if we need to emit audio alert
            val now = System.currentTimeMillis()
            val isNewStage = (hazard.id != lastAlertedHazardId || stage != lastAlertStage)
            if (isNewStage || (now - lastAlertTimeMs > 4000L)) {
                lastAlertedHazardId = hazard.id
                lastAlertStage = stage
                lastAlertTimeMs = now
                playProximityAudio(stage)
            }

            updateWarningState(text, stage)
        } else {
            clearWarningState()
        }
    }

    private fun playProximityAudio(stage: String) {
        if (!isWarningEnabled || isAudioMuted) return
        try {
            if (stage == "FINAL") {
                alertSoundPlayer.playBump25m()
            } else {
                alertSoundPlayer.playBump100m()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Natural voice alert failed: ${e.message}")
        }
    }

    private fun updateWarningState(text: String, stage: String) {
        currentWarningText = text
        mainHandler.post {
            onWarningStateChanged?.invoke(text, stage)
        }
    }

    private fun clearWarningState() {
        if (currentWarningText != null) {
            currentWarningText = null
            mainHandler.post {
                onWarningStateChanged?.invoke(null, null)
            }
        }
    }

    /**
     * Checks if the car has entered and then exited the 25m zone of a bump in the same lane direction.
     * Emits pass-check (positive or negative) to the server.
     * If 3 consecutive cars pass without slowing down (<21 km/h) and experiencing the bump pattern,
     * the bump is deactivated until the bump pattern returns on another car.
     */
    private fun checkPassHazards(currentLoc: Location, speedKmh: Double) {
        if (speedKmh < SPEED_GATE_MIN_KMH) return
        val now = System.currentTimeMillis()
        val hasBearingNow = currentLoc.hasBearing() || hasValidBearing
        val carBearing = if (currentLoc.hasBearing()) currentLoc.bearing.toDouble() else lastValidBearingDegrees

        // 1. Detect entering <= 25m in the SAME direction of travel
        for (hazard in cachedHazards) {
            if (!hazard.active) continue
            if (!hazard.bidirectional && hasBearingNow && angularDifference(carBearing, hazard.directionDegrees) > 55.0) {
                // Opposite lane traffic: do NOT count as passing over this lane's bump
                continue
            }
            val dist = distanceMeters(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)
            if (dist <= FINAL_WARNING_METERS) {
                if (!activePassHazards.containsKey(hazard.id)) {
                    activePassHazards[hazard.id] = now
                }
            }
        }

        // 2. Detect exiting > 35m after entering
        val iterator = activePassHazards.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val hId = entry.key
            val hazard = cachedHazards.firstOrNull { it.id == hId }
            if (hazard != null) {
                val dist = distanceMeters(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)
                if (dist > 35.0) {
                    // Car passed the bump! Check if the known bump pattern (<21 km/h + vibration + rebound) occurred:
                    val feltBump = (now - lastBumpFeltTimeMs <= PASS_CHECK_WINDOW_MS)
                    if (feltBump) {
                        hazard.consecutiveNegativePasses = 0
                        hazard.active = true
                    } else {
                        hazard.consecutiveNegativePasses += 1
                        if (hazard.consecutiveNegativePasses >= 3) {
                            hazard.active = false
                            Log.i(TAG, "Hazard ${hazard.id} deactivated locally after 3 passes without bump pattern")
                        }
                    }
                    sendPassCheckAsync(hId, feltBump, speedKmh)
                    iterator.remove()
                }
            } else {
                iterator.remove()
            }
        }
    }

    /**
     * Sensor Event Listener: Kinematic Speed Bump Detection
     * Strict Speed Gate: Speed MUST be <= 21 km/h ($5.83 m/s) with deceleration/low-speed + vertical shock + rebound.
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val speed = getEffectiveSpeedKmh()

        // Strict User Rule: If vehicle speed > 21 km/h, NEVER record a bump!
        if (speed > SPEED_GATE_MAX_KMH || speed < SPEED_GATE_MIN_KMH) {
            hasPositiveShock = false
            hasPitchRotation = false
            hasNegativeRebound = false
            peakPositiveShockDelta = 0f
            return
        }

        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val totalMag = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
                filteredGravity = (0.95f * filteredGravity) + (0.05f * totalMag.coerceIn(8.5f, 11.5f))
                val dynamicShock = totalMag - filteredGravity

                // 1. Positive upward shock (+Shock > 2.6 m/s^2)
                if (dynamicShock > 2.6f) {
                    if (!hasPositiveShock) {
                        hasPositiveShock = true
                        positiveShockTimeMs = now
                        peakPositiveShockDelta = dynamicShock
                    } else if (dynamicShock > peakPositiveShockDelta) {
                        peakPositiveShockDelta = dynamicShock
                    }
                }

                // 2. Downward suspension rebound (-Shock < -1.5 m/s^2) following positive shock
                if (hasPositiveShock && (now - positiveShockTimeMs in 80L..1400L)) {
                    if (dynamicShock < -1.5f) {
                        hasNegativeRebound = true
                    }
                }

                // 3. Check full sequence completion (deceleration/low speed <= 21 km/h + shock + rebound + gyro pitch if available)
                val gyroMatched = !hasGyroSensor || hasPitchRotation || peakPositiveShockDelta >= 3.4f
                val speedPatternMatched = synchronized(recentSpeedSamples) {
                    val maxRecentSpeed = recentSpeedSamples.maxOfOrNull { it.second } ?: speed
                    // Either driver decelerated toward the bump or was already traversing cautiously <= 21 km/h
                    speed <= SPEED_GATE_MAX_KMH && (maxRecentSpeed >= speed || speed <= 18.0)
                }

                if (hasPositiveShock && hasNegativeRebound && gyroMatched && speedPatternMatched) {
                    // Kinematic bump pattern confirmed!
                    lastBumpFeltTimeMs = now
                    hasPositiveShock = false
                    hasNegativeRebound = false
                    hasPitchRotation = false
                    peakPositiveShockDelta = 0f

                    // If not reported recently (8 sec cooldown), report/confirm or re-activate bump
                    if (now - lastRecordedBumpTimeMs > 8000L) {
                        lastRecordedBumpTimeMs = now
                        latestGpsLocation?.let { loc ->
                            val dir = if (loc.hasBearing()) loc.bearing.toDouble() else lastValidBearingDegrees
                            reportNewBumpAsync(loc.latitude, loc.longitude, dir)
                        }
                    }
                }

                // Window expiry
                if (hasPositiveShock && (now - positiveShockTimeMs > 1500L)) {
                    hasPositiveShock = false
                    hasNegativeRebound = false
                    hasPitchRotation = false
                    peakPositiveShockDelta = 0f
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val rotRate = sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1]).toDouble()).toFloat()
                if (hasPositiveShock && rotRate > 0.22f) {
                    hasPitchRotation = true
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Downloads bumps within 20 km of the vehicle's location from the server.
     */
    private fun fetchNearbyHazards(lat: Double, lng: Double, radiusKm: Double) {
        if (!isFetching.compareAndSet(false, true)) return

        thread(name = "FetchHazardsThread") {
            try {
                val baseUrl = apiBaseUrlProvider().trimEnd('/')
                val urlString = "$baseUrl/api/hazards/nearby?lat=$lat&lng=$lng&radius_km=$radiusKm"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(raw)
                    val arr = json.optJSONArray("hazards") ?: JSONArray()
                    val newHazards = mutableListOf<CachedHazard>()

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        newHazards.add(
                            CachedHazard(
                                id = obj.optString("id"),
                                lat = obj.optDouble("lat"),
                                lng = obj.optDouble("lng"),
                                directionDegrees = obj.optDouble("dir", 0.0),
                                bidirectional = obj.optBoolean("bi", false),
                                roadClass = obj.optString("road", "unknown"),
                                active = true,
                                consecutiveNegativePasses = 0,
                                needsMapMatch = false
                            )
                        )
                    }

                    // Preserve any local deactivated/pending bumps not yet on server
                    val localPending = cachedHazards.filter { it.needsMapMatch }
                    cachedHazards.clear()
                    cachedHazards.addAll(newHazards)
                    for (lp in localPending) {
                        if (cachedHazards.none { distanceMeters(it.lat, it.lng, lp.lat, lp.lng) <= 22.0 }) {
                            cachedHazards.add(lp)
                        }
                    }
                    lastFetchLocation = Location("").apply {
                        latitude = lat
                        longitude = lng
                    }
                    Log.i(TAG, "Cached ${newHazards.size} hazards within ${radiusKm}km sector around ($lat, $lng)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch nearby hazards: ${e.message}")
            } finally {
                isFetching.set(false)
            }
        }
    }

    /**
     * Emits a newly detected bump to the server and adds or re-activates it immediately in the local cache.
     * If a bump at this location was previously deactivated after 3 passes without vibration,
     * experiencing the bump pattern again re-activates it immediately!
     */
    private fun reportNewBumpAsync(lat: Double, lng: Double, bearing: Double) {
        // 1. Check local cache first (including deactivated bumps!)
        val existingLocal = cachedHazards.firstOrNull { h ->
            distanceMeters(lat, lng, h.lat, h.lng) <= 22.0 &&
                (h.bidirectional || angularDifference(bearing, h.directionDegrees) <= 55.0)
        }
        var wasReactivated = false
        if (existingLocal != null) {
            wasReactivated = !existingLocal.active
            existingLocal.active = true
            existingLocal.consecutiveNegativePasses = 0
        }

        thread(name = "ReportBumpThread") {
            try {
                val baseUrl = apiBaseUrlProvider().trimEnd('/')
                val url = URL("$baseUrl/api/hazards")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val payload = JSONObject().apply {
                    put("latitude", lat)
                    put("longitude", lng)
                    put("direction_degrees", bearing)
                    put("bidirectional", false)
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }

                if (conn.responseCode in 200..201) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val newId = resp.optString("id", "temp-${System.currentTimeMillis()}")
                    val serverStatus = resp.optString("status", "created")

                    if (existingLocal == null) {
                        cachedHazards.add(
                            CachedHazard(
                                id = newId,
                                lat = lat,
                                lng = lng,
                                directionDegrees = bearing,
                                bidirectional = false,
                                roadClass = "reported",
                                active = true,
                                consecutiveNegativePasses = 0,
                                needsMapMatch = true
                            )
                        )
                    }

                    mainHandler.post {
                        val notice = if (wasReactivated || serverStatus == "reactivated_existing") {
                            "تم إعادة تفعيل المطب بعد عودة نمط الاهتزاز وتهدئة السرعة ✅"
                        } else {
                            "تم رصد وتوثيق مطب جديد (سرعة ≤ 21 كم/س) ✅"
                        }
                        onBumpDetectedNotice?.invoke(notice)
                        if (!isMuted) {
                            try {
                                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                            } catch (_: Exception) {}
                        }
                    }
                    Log.i(TAG, "Successfully reported/reactivated bump at ($lat, $lng): $newId ($serverStatus)")
                }
            } catch (e: Exception) {
                if (existingLocal == null) {
                    cachedHazards.add(
                        CachedHazard(
                            id = "local-${System.currentTimeMillis()}",
                            lat = lat,
                            lng = lng,
                            directionDegrees = bearing,
                            bidirectional = false,
                            roadClass = "reported",
                            active = true,
                            consecutiveNegativePasses = 0,
                            needsMapMatch = true
                        )
                    )
                }
                Log.w(TAG, "Offline/local bump cached: ${e.message}")
            }
        }
    }

    /**
     * Reports a pass observation to the server:
     * If feltBump == false, increments negative pass.
     * (Server deactivates if >= 3 negative passes occur, until another car experiences the bump pattern).
     */
    private fun sendPassCheckAsync(hazardId: String, feltBump: Boolean, speedKmh: Double) {
        thread(name = "PassCheckThread") {
            try {
                val baseUrl = apiBaseUrlProvider().trimEnd('/')
                val url = URL("$baseUrl/api/hazards/pass-check")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val payload = JSONObject().apply {
                    put("hazard_id", hazardId)
                    put("felt_bump", feltBump)
                    put("speed_kmh", speedKmh)
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }

                if (conn.responseCode == 200) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val status = resp.optString("status")
                    if (status == "DEACTIVATED") {
                        // Mark inactive in local cache (kept so it can be re-activated if bump pattern returns!)
                        cachedHazards.firstOrNull { it.id == hazardId }?.let {
                            it.active = false
                        }
                        Log.i(TAG, "Hazard $hazardId deactivated after 3 negative passes (awaiting future pattern to re-activate)")
                    } else if (status == "ACTIVE") {
                        cachedHazards.firstOrNull { it.id == hazardId }?.let {
                            it.active = true
                            it.consecutiveNegativePasses = 0
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error sending pass check: ${e.message}")
            }
        }
    }

    /**
     * Runs once per day (every 24 hours) for newly recorded speed bumps ONLY:
     * Matches new bumps against the road map network (server + OSM road geometry)
     * to detect if the car was driving counter-traffic (مخالفاً عكس الاتجاه - e.g. heading South in a Northbound lane)
     * and automatically corrects the bump direction to the true legal direction of that lane.
     */
    fun triggerDailyMapReconciliationIfNeeded(forceNow: Boolean = false) {
        val prefs = context.getSharedPreferences("drivecare_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong("last_daily_hazard_map_reconcile_ms", 0L)
        val oneDayMs = 24L * 60L * 60L * 1000L
        if (!forceNow && (now - lastRun < oneDayMs)) return

        prefs.edit().putLong("last_daily_hazard_map_reconcile_ms", now).apply()

        thread(name = "DailyHazardMapMatchThread") {
            try {
                // 1. Trigger server-side daily reconciliation for new bumps (needs_map_match = 1)
                val baseUrl = apiBaseUrlProvider().trimEnd('/')
                val conn = URL("$baseUrl/api/hazards/reconcile-daily").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write("{}") }
                if (conn.responseCode == 200) {
                    Log.i(TAG, "Server daily road map reconciliation completed successfully")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server daily reconcile unreachable, running local OSM road check: ${e.message}")
            }

            // 2. Also reconcile any locally cached new bumps (needsMapMatch == true) against OSM Overpass road ways
            val newBumps = cachedHazards.filter { it.needsMapMatch }
            for (bump in newBumps) {
                try {
                    val query = "[out:json][timeout:4];way(around:25,${bump.lat},${bump.lng})[\"highway\"];out geom 1;"
                    val osmUrl = URL("https://overpass-api.de/api/interpreter?data=" + java.net.URLEncoder.encode(query, "UTF-8"))
                    val osmConn = osmUrl.openConnection() as HttpURLConnection
                    osmConn.connectTimeout = 4000
                    osmConn.readTimeout = 4000
                    if (osmConn.responseCode == 200) {
                        val raw = osmConn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(raw)
                        val elements = json.optJSONArray("elements")
                        if (elements != null && elements.length() > 0) {
                            val way = elements.getJSONObject(0)
                            val tags = way.optJSONObject("tags")
                            val onewayTag = tags?.optString("oneway", "no")?.lowercase() ?: "no"
                            val geom = way.optJSONArray("geometry")
                            if (geom != null && geom.length() >= 2) {
                                val p1 = geom.getJSONObject(0)
                                val p2 = geom.getJSONObject(geom.length() - 1)
                                var roadBearing = initialBearingDegrees(
                                    p1.getDouble("lat"), p1.getDouble("lon"),
                                    p2.getDouble("lat"), p2.getDouble("lon")
                                )
                                val isOneWay = onewayTag in listOf("yes", "1", "true", "-1", "reverse")
                                if (onewayTag in listOf("-1", "reverse")) {
                                    roadBearing = (roadBearing + 180.0) % 360.0
                                }
                                if (isOneWay) {
                                    bump.bidirectional = false
                                    // If car was driving counter-traffic (> 120 deg opposite to legal lane direction),
                                    // flip the bump direction to the legal lane direction so opposing traffic gets no false alert!
                                    if (angularDifference(bump.directionDegrees, roadBearing) > 120.0) {
                                        Log.i(TAG, "Counter-traffic bump detected at (${bump.lat}, ${bump.lng}): correcting direction ${bump.directionDegrees} -> $roadBearing")
                                        bump.directionDegrees = roadBearing
                                    }
                                } else {
                                    bump.bidirectional = true
                                }
                                bump.needsMapMatch = false
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Geometry Helpers
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dphi = Math.toRadians(lat2 - lat1)
        val dlambda = Math.toRadians(lon2 - lon1)
        val a = sin(dphi / 2.0).pow(2.0) + cos(phi1) * cos(phi2) * sin(dlambda / 2.0).pow(2.0)
        val aClamped = a.coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(aClamped), sqrt(1.0 - aClamped))
    }

    private fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dlambda = Math.toRadians(lon2 - lon1)
        val y = sin(dlambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dlambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angularDifference(first: Double, second: Double): Double {
        val diff = abs(first - second) % 360.0
        return min(diff, 360.0 - diff)
    }
}
