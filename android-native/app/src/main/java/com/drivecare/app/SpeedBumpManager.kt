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
    private val apiBaseUrlProvider: () -> String,
    private val vehicleIdProvider: () -> String = { "" }
) : SensorEventListener {

    companion object {
        private const val TAG = "SpeedBumpManager"
        private const val EARTH_RADIUS_METERS = 6371000.0
        private const val CACHE_REFRESH_DISTANCE_METERS = 5000.0 // 5 km move triggers 20km sector refresh
        private const val SPEED_GATE_MAX_KMH = 21.0              // Hard limit: > 21 km/h never records a bump
        private const val SPEED_GATE_MIN_KMH = 1.5               // Allow slow crawl (2-3 km/h) over tall speed bumps
        private const val EARLY_WARNING_METERS = 100.0
        private const val FINAL_WARNING_METERS = 20.0
        private const val PASS_CHECK_COOLDOWN_MS = 60000L        // Prevent GPS jitter from counting same pass twice
    }

    data class CachedHazard(
        var id: String,
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
    var isMuted100m: Boolean = false
    var isMuted20m: Boolean = false
    var isMuted: Boolean
        get() = isAudioMuted
        set(value) { isAudioMuted = value }

    val alertSoundPlayer: AlertSoundPlayer = AlertSoundPlayer(context)

    var currentWarningText: String? = null
        private set
    var onWarningStateChanged: ((warningText: String?, stage: String?) -> Unit)? = null
    var onBumpDetectedNotice: ((message: String) -> Unit)? = null

    // Cache & state (20 km active sector + full 4,380 bundled offline seed database)
    private val cachedHazards = CopyOnWriteArrayList<CachedHazard>()
    private val offlineSeedHazards = CopyOnWriteArrayList<CachedHazard>()
    private val deactivatedSeedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var isSeedLoaded = false
    private var lastFetchLocation: Location? = null
    private var lastSeedSectorLocation: Location? = null
    private val isFetching = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Proximity deduplication
    private var lastAlertedHazardId: String? = null
    private var lastAlertStage: String? = null
    private var lastAlertTimeMs: Long = 0L

    private data class PassTrackingState(
        val entryTimeMs: Long,
        var feltBumpDuringPass: Boolean = false
    )

    private data class MotionSample(
        val timestampMs: Long,
        val verticalAccel: Float,
        val horizontalMag: Float
    )

    private data class PendingBumpCandidate(
        val lat: Double,
        val lng: Double,
        val bearing: Double,
        val bidirectional: Boolean,
        val minSpeedAtBump: Double,
        val detectedAtMs: Long,
        val peakToPeak: Float
    )

    // Pass-check tracker: hazards currently within <= 20m, latching bump detection reliably
    private val activePassHazards = mutableMapOf<String, PassTrackingState>()
    private val lastPassSentMs = mutableMapOf<String, Long>()

    // 3D Gravity-aligned kinematic detection & rough-road chatter filter
    private var lastRecordedBumpTimeMs: Long = 0L
    private var positiveShockTimeMs: Long = 0L
    private var hasPositiveShock = false
    private var hasPitchRotation = false
    private var hasNegativeRebound = false
    private var lastBumpFeltTimeMs: Long = 0L
    private var lastGyroRotationTimeMs: Long = 0L
    private var lastHandHeldJitterMs: Long = 0L
    private var hasGravityInit = false
    private var gravityX: Float = 0f
    private var gravityY: Float = 0f
    private var gravityZ: Float = 9.81f
    private var peakPositiveShockDelta: Float = 0f
    private var minNegativeReboundDelta: Float = 0f
    private var minSpeedInBumpWindow: Double = 999.0
    private var pendingCandidate: PendingBumpCandidate? = null
    private val recentMotionSamples = ArrayDeque<MotionSample>()

    // Current vehicle telemetry
    private var latestGpsLocation: Location? = null
    private var latestGpsSpeedKmh: Double = 0.0
    private var latestObdSpeedKmh: Double? = null
    private var lastObdSpeedUpdateMs: Long = 0L
    private var lastValidBearingDegrees: Double = 0.0
    private var hasValidBearing: Boolean = false
    private var hasGyroSensor: Boolean = false
    private val recentSpeedSamples = ArrayDeque<Pair<Long, Double>>() // (timestampMs, speedKmh)

    // Audio & Sensors
    private var toneGenerator: ToneGenerator? = null
    private var sensorManager: SensorManager? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 95)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator not available: ${e.message}")
        }
        loadPersistedLocalHazards()
    }

    private fun loadPersistedLocalHazards() {
        try {
            val prefs = context.getSharedPreferences("drivecare_prefs", Context.MODE_PRIVATE)
            val savedDeactivated = prefs.getStringSet("deactivated_seed_bump_ids", null)
            if (!savedDeactivated.isNullOrEmpty()) {
                deactivatedSeedIds.addAll(savedDeactivated)
            }
            val raw = prefs.getString("persisted_speed_bumps_json", null)
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                val loaded = mutableListOf<CachedHazard>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val bumpId = obj.optString("id", "bump-$i")
                    // Ignore any old demo/seeded speed bumps so only real detected/reported bumps are kept
                    if (bumpId.startsWith("bump-seed-") || bumpId.startsWith("local-seed-")) continue
                    loaded.add(
                        CachedHazard(
                            id = bumpId,
                            lat = obj.optDouble("lat", 0.0),
                            lng = obj.optDouble("lng", 0.0),
                            directionDegrees = obj.optDouble("dir", 0.0),
                            bidirectional = obj.optBoolean("bi", true),
                            roadClass = obj.optString("road", "local"),
                            active = obj.optBoolean("active", true),
                            consecutiveNegativePasses = obj.optInt("neg", 0),
                            needsMapMatch = obj.optBoolean("needsMapMatch", false)
                        )
                    )
                }
                cachedHazards.clear()
                cachedHazards.addAll(loaded)
                savePersistedLocalHazards()
            } else {
                cachedHazards.clear()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted hazards: ${e.message}")
        }
    }

    private fun savePersistedLocalHazards() {
        try {
            val arr = JSONArray()
            // Persist user-discovered/modified bumps cleanly without bloating prefs with all 4,380 bundled seeds
            val toPersist = cachedHazards.filter { h ->
                !h.id.all { it.isDigit() } || !h.active || h.consecutiveNegativePasses > 0 || h.needsMapMatch
            }
            for (h in toPersist.take(350)) {
                val obj = JSONObject().apply {
                    put("id", h.id)
                    put("lat", h.lat)
                    put("lng", h.lng)
                    put("dir", h.directionDegrees)
                    put("bi", h.bidirectional)
                    put("road", h.roadClass)
                    put("active", h.active)
                    put("neg", h.consecutiveNegativePasses)
                    put("needsMapMatch", h.needsMapMatch)
                }
                arr.put(obj)
            }
            val prefs = context.getSharedPreferences("drivecare_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("persisted_speed_bumps_json", arr.toString())
                .putStringSet("deactivated_seed_bump_ids", HashSet(deactivatedSeedIds))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save persisted hazards: ${e.message}")
        }
    }

    fun getDiscoveredHazardsSnapshot(): List<CachedHazard> {
        return cachedHazards.toList()
    }

    fun getTotalBundledSeedCount(): Int {
        return offlineSeedHazards.size
    }

    fun ensureSectorLoadedAround(lat: Double, lng: Double, radiusKm: Double = 20.0) {
        if (!isSeedLoaded) {
            loadOfflineSeedHazardsSync()
        }
        populateSectorFromOfflineSeed(lat, lng, radiusKm)
    }

    private fun loadOfflineSeedHazardsSync() {
        if (isSeedLoaded) return
        try {
            val raw = context.assets.open("trusted_seed_hazards_v1.json").bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            val rows = json.optJSONArray("rows") ?: JSONArray()
            val parsed = ArrayList<CachedHazard>(rows.length())
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val sid = r.optString("sourceId", "${i + 1}")
                val isActive = !deactivatedSeedIds.contains(sid)
                val roadLabel = if (r.optString("type") == "UNKNOWN_ROAD_ANOMALY") "مطب مسجل" else "مطب معتمد"
                parsed.add(
                    CachedHazard(
                        id = sid,
                        lat = r.optDouble("latitudeDegrees", 0.0),
                        lng = r.optDouble("longitudeDegrees", 0.0),
                        directionDegrees = r.optDouble("directionDegrees", 0.0),
                        bidirectional = r.optBoolean("bidirectional", false),
                        roadClass = roadLabel,
                        active = isActive,
                        consecutiveNegativePasses = 0,
                        needsMapMatch = false
                    )
                )
            }
            offlineSeedHazards.clear()
            offlineSeedHazards.addAll(parsed)
            isSeedLoaded = true
            Log.i(TAG, "Loaded ${offlineSeedHazards.size} bundled offline speed bumps from assets")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bundled seed bumps: ${e.message}")
        }
    }

    private fun loadOfflineSeedHazardsAsync() {
        if (isSeedLoaded) return
        thread(name = "LoadSeedHazardsThread") {
            loadOfflineSeedHazardsSync()
            latestGpsLocation?.let { loc ->
                populateSectorFromOfflineSeed(loc.latitude, loc.longitude, 20.0)
            }
        }
    }

    private fun populateSectorFromOfflineSeed(lat: Double, lng: Double, radiusKm: Double = 20.0) {
        if (!isSeedLoaded || offlineSeedHazards.isEmpty()) return
        val lastSeed = lastSeedSectorLocation
        if (lastSeed != null && distanceMeters(lat, lng, lastSeed.latitude, lastSeed.longitude) < CACHE_REFRESH_DISTANCE_METERS && cachedHazards.isNotEmpty()) {
            return
        }
        val radiusMeters = radiusKm * 1000.0
        val existingById = HashMap<String, CachedHazard>(cachedHazards.size + 16)
        for (h in cachedHazards) {
            existingById[h.id] = h
        }
        val toAdd = ArrayList<CachedHazard>()
        for (seed in offlineSeedHazards) {
            if (deactivatedSeedIds.contains(seed.id)) continue
            if (existingById.containsKey(seed.id)) continue
            val d = distanceMeters(lat, lng, seed.lat, seed.lng)
            if (d <= radiusMeters) {
                toAdd.add(seed)
            }
        }
        if (toAdd.isNotEmpty()) {
            cachedHazards.addAll(toAdd)
        }
        lastSeedSectorLocation = Location("").apply {
            latitude = lat
            longitude = lng
        }
        Log.i(TAG, "Offline 20km sector populated: ${cachedHazards.size} active/local hazards around ($lat, $lng)")
    }

    fun registerManualOrSimulatedBump(lat: Double, lng: Double, bearing: Double = 0.0, roadLabel: String = "مطب مرصود"): CachedHazard {
        val newHazard = CachedHazard(
            id = "bump-${System.currentTimeMillis()}",
            lat = lat,
            lng = lng,
            directionDegrees = bearing,
            bidirectional = true,
            roadClass = roadLabel,
            active = true,
            consecutiveNegativePasses = 0,
            needsMapMatch = true
        )
        cachedHazards.add(0, newHazard)
        savePersistedLocalHazards()
        reportNewBumpAsync(lat, lng, bearing)
        return newHazard
    }

    fun start() {
        try {
            if (toneGenerator == null) {
                try {
                    toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 95)
                } catch (_: Exception) {}
            }
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
            loadOfflineSeedHazardsAsync()
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
        val now = System.currentTimeMillis()
        latestGpsLocation = location
        latestGpsSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        if (obdSpeedKmh != null) {
            latestObdSpeedKmh = obdSpeedKmh
            lastObdSpeedUpdateMs = now
        }
        val effectiveBearingSpeed = max(latestGpsSpeedKmh, obdSpeedKmh ?: 0.0)
        if (location.hasBearing() && effectiveBearingSpeed > 8.0) {
            lastValidBearingDegrees = location.bearing.toDouble()
            hasValidBearing = true
        }

        val carSpeed = getEffectiveSpeedKmh()
        synchronized(recentSpeedSamples) {
            recentSpeedSamples.addLast(Pair(now, carSpeed))
            while (recentSpeedSamples.isNotEmpty() && now - recentSpeedSamples.first().first > 10000L) {
                recentSpeedSamples.removeFirst()
            }
        }

        // Evaluate pending bump candidate re-acceleration confirmation
        evaluatePendingBumpCandidate(now, carSpeed)

        // 1. Dynamic 20km City Cache refresh check (offline bundled 4,380 seeds + online server sync)
        if (isSeedLoaded) {
            populateSectorFromOfflineSeed(location.latitude, location.longitude, 20.0)
        }
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
     * Returns effective vehicle speed: prefers real-time ECU OBD wheel speed when connected & active
     * (no 1-2s GPS deceleration lag), falling back to GPS speed when OBD is disconnected.
     */
    fun getEffectiveSpeedKmh(): Double {
        val obd = latestObdSpeedKmh
        val obdFresh = (System.currentTimeMillis() - lastObdSpeedUpdateMs) <= 6000L
        if (obd != null && obdFresh && (obd > 0.0 || latestGpsSpeedKmh <= 3.5)) {
            return obd
        }
        return latestGpsSpeedKmh
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
                if (!isMuted20m) {
                    alertSoundPlayer.playBump25m()
                }
            } else {
                if (!isMuted100m) {
                    alertSoundPlayer.playBump100m()
                }
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

        // 1. Detect entering <= 22m in the SAME direction of travel & latch if bump was already felt on entry
        for (hazard in cachedHazards) {
            if (!hazard.active) continue
            if (!hazard.bidirectional && hasBearingNow && angularDifference(carBearing, hazard.directionDegrees) > 55.0) {
                continue
            }
            val dist = distanceMeters(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)
            if (dist <= 22.0) {
                val state = activePassHazards.getOrPut(hazard.id) {
                    val recentlyFelt = (now - lastBumpFeltTimeMs <= 5000L)
                    PassTrackingState(entryTimeMs = now, feltBumpDuringPass = recentlyFelt)
                }
                if (now - lastBumpFeltTimeMs <= 4000L) {
                    state.feltBumpDuringPass = true
                }
            }
        }

        // 2. Detect exiting > 35m after entering (works accurately even at 5-15 km/h slow crossing)
        val iterator = activePassHazards.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val hId = entry.key
            val state = entry.value
            val hazard = cachedHazards.firstOrNull { it.id == hId }
            if (hazard != null) {
                val dist = distanceMeters(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)
                if (dist > 35.0) {
                    val lastSent = lastPassSentMs[hId] ?: 0L
                    if (now - lastSent < PASS_CHECK_COOLDOWN_MS) {
                        iterator.remove()
                        continue
                    }

                    val feltBump = state.feltBumpDuringPass || (lastBumpFeltTimeMs >= state.entryTimeMs - 3000L)
                    val isValidNegativeTransit = !feltBump && speedKmh >= 6.0 && (now - state.entryTimeMs in 1500L..45000L)

                    if (feltBump) {
                        hazard.consecutiveNegativePasses = 0
                        hazard.active = true
                        lastPassSentMs[hId] = now
                        savePersistedLocalHazards()
                        sendPassCheckAsync(hId, true, speedKmh)
                    } else if (isValidNegativeTransit) {
                        hazard.consecutiveNegativePasses += 1
                        lastPassSentMs[hId] = now
                        if (hazard.consecutiveNegativePasses >= 3) {
                            hazard.active = false
                            if (hazard.id.all { it.isDigit() }) {
                                deactivatedSeedIds.add(hazard.id)
                            }
                            Log.i(TAG, "Hazard ${hazard.id} deactivated locally after 3 passes without bump pattern")
                        }
                        savePersistedLocalHazards()
                        sendPassCheckAsync(hId, false, speedKmh)
                    }
                    iterator.remove()
                }
            } else {
                iterator.remove()
            }
        }
    }

    private fun resetShockWindow() {
        hasPositiveShock = false
        hasPitchRotation = false
        hasNegativeRebound = false
        peakPositiveShockDelta = 0f
        minNegativeReboundDelta = 0f
        minSpeedInBumpWindow = 999.0
    }

    private fun isRoughRoadChatterWindow(beforeMs: Long): Boolean {
        val samples = synchronized(recentMotionSamples) {
            recentMotionSamples.filter { it.timestampMs in (beforeMs - 1500L)..(beforeMs - 100L) }
        }
        if (samples.size < 8) return false

        var peakFlips = 0
        var lastSign = 0
        var sumAbsHoriz = 0f
        for (s in samples) {
            sumAbsHoriz += s.horizontalMag
            if (abs(s.verticalAccel) > 1.25f) {
                val sign = if (s.verticalAccel > 0f) 1 else -1
                if (lastSign != 0 && sign != lastSign) {
                    peakFlips++
                }
                lastSign = sign
            }
        }
        val meanHoriz = sumAbsHoriz / samples.size
        // Continuous rough asphalt / cobblestone produces rapid alternating chatter + lateral shake
        return peakFlips >= 5 || (peakFlips >= 4 && meanHoriz > 1.75f)
    }

    private fun evaluatePendingBumpCandidate(now: Long, currentSpeed: Double) {
        val cand = pendingCandidate ?: return
        val elapsed = now - cand.detectedAtMs

        // Phase 5: Confirm on re-acceleration ("ثم تسارع مرة أخرى")
        val reAccelerated = (currentSpeed >= cand.minSpeedAtBump + 2.2) || (currentSpeed > SPEED_GATE_MAX_KMH)
        if (elapsed >= 450L && reAccelerated) {
            pendingCandidate = null
            commitDetectedBump(cand)
            return
        }

        // Timeout fallback: in slow bumper-to-bumper traffic after an unmistakable bump (>= 5.5 m/s^2 p2p)
        if (elapsed > 5500L) {
            pendingCandidate = null
            if (cand.peakToPeak >= 5.5f && !isRoughRoadChatterWindow(now)) {
                commitDetectedBump(cand)
            }
        }
    }

    private fun commitDetectedBump(cand: PendingBumpCandidate) {
        val now = System.currentTimeMillis()
        if (now - lastRecordedBumpTimeMs <= 7000L) return
        lastRecordedBumpTimeMs = now
        reportNewBumpAsync(cand.lat, cand.lng, cand.bearing, cand.bidirectional)
    }

    /**
     * Sensor Event Listener: 5-Phase Gravity-Aligned Kinematic Speed Bump Detection
     * Pattern: Deceleration (<= 21 km/h) -> +Vertical Climb -> Gyro Pitch Rotation -> -Vertical Drop -> Re-acceleration
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val now = System.currentTimeMillis()
        val speed = getEffectiveSpeedKmh()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val totalMag = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

                // Slow 3D gravity vector estimation (updates only when near 1G so shocks don't skew vertical axis)
                if (!hasGravityInit) {
                    gravityX = ax
                    gravityY = ay
                    gravityZ = az
                    hasGravityInit = true
                } else if (abs(totalMag - 9.81f) < 1.5f) {
                    gravityX = 0.96f * gravityX + 0.04f * ax
                    gravityY = 0.96f * gravityY + 0.04f * ay
                    gravityZ = 0.96f * gravityZ + 0.04f * az
                }

                val gNorm = sqrt((gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ).toDouble()).toFloat().coerceAtLeast(1.0f)
                val ux = gravityX / gNorm
                val uy = gravityY / gNorm
                val uz = gravityZ / gNorm

                // True Earth-vertical linear acceleration regardless of phone mount tilt
                val verticalAccel = (ax * ux + ay * uy + az * uz) - gNorm
                val dx = ax - gravityX
                val dy = ay - gravityY
                val dz = az - gravityZ
                val horizSq = (dx * dx + dy * dy + dz * dz) - (verticalAccel * verticalAccel)
                val horizontalMag = sqrt(horizSq.coerceAtLeast(0f).toDouble()).toFloat()

                synchronized(recentMotionSamples) {
                    recentMotionSamples.addLast(MotionSample(now, verticalAccel, horizontalMag))
                    while (recentMotionSamples.isNotEmpty() && now - recentMotionSamples.first().timestampMs > 2200L) {
                        recentMotionSamples.removeFirst()
                    }
                }

                // Always evaluate pending re-acceleration even if car just accelerated above 21 km/h!
                evaluatePendingBumpCandidate(now, speed)

                // Check recent minimum speed (tolerates brief 1-2s GPS lag during rapid braking into bump)
                val recentMinSpeed = synchronized(recentSpeedSamples) {
                    recentSpeedSamples.filter { now - it.first <= 1800L }.minOfOrNull { it.second } ?: speed
                }
                val effectiveWindowSpeed = min(speed, recentMinSpeed)

                if (effectiveWindowSpeed > SPEED_GATE_MAX_KMH || speed < SPEED_GATE_MIN_KMH) {
                    if (!hasPositiveShock || speed > SPEED_GATE_MAX_KMH + 4.0 || speed < SPEED_GATE_MIN_KMH) {
                        resetShockWindow()
                    }
                    return
                }

                // 1. Climb Phase (+Vertical Shock >= 2.35 m/s^2)
                if (verticalAccel >= 2.35f) {
                    if (!hasPositiveShock) {
                        hasPositiveShock = true
                        positiveShockTimeMs = now
                        peakPositiveShockDelta = verticalAccel
                        minNegativeReboundDelta = 0f
                        minSpeedInBumpWindow = effectiveWindowSpeed
                        if (now - lastGyroRotationTimeMs <= 220L) {
                            hasPitchRotation = true
                        }
                    } else {
                        if (verticalAccel > peakPositiveShockDelta) peakPositiveShockDelta = verticalAccel
                        if (effectiveWindowSpeed < minSpeedInBumpWindow) minSpeedInBumpWindow = effectiveWindowSpeed
                    }
                }

                // 2. Drop / Rebound Phase (-Vertical Shock <= -1.6 m/s^2) within 80..1400ms
                if (hasPositiveShock && (now - positiveShockTimeMs in 80L..1400L)) {
                    if (effectiveWindowSpeed < minSpeedInBumpWindow) {
                        minSpeedInBumpWindow = effectiveWindowSpeed
                    }
                    if (verticalAccel <= -1.6f) {
                        hasNegativeRebound = true
                        if (verticalAccel < minNegativeReboundDelta) {
                            minNegativeReboundDelta = verticalAccel
                        }
                    }
                }

                // 3. Evaluate Climb + Pitch + Drop sequence completion
                if (hasPositiveShock && hasNegativeRebound && (now - positiveShockTimeMs >= 160L)) {
                    val peakToPeak = peakPositiveShockDelta - minNegativeReboundDelta
                    val notHandHeld = (now - lastHandHeldJitterMs > 1800L)
                    val gyroMatched = !hasGyroSensor || hasPitchRotation || peakToPeak >= 6.4f
                    val roughChatter = isRoughRoadChatterWindow(positiveShockTimeMs)
                    val speedDecelContext = synchronized(recentSpeedSamples) {
                        val maxRecent = recentSpeedSamples.maxOfOrNull { it.second } ?: minSpeedInBumpWindow
                        minSpeedInBumpWindow <= SPEED_GATE_MAX_KMH &&
                            (maxRecent >= minSpeedInBumpWindow + 1.5 || minSpeedInBumpWindow <= 18.5)
                    }

                    if (peakToPeak >= 4.4f && notHandHeld && gyroMatched && !roughChatter && speedDecelContext) {
                        lastBumpFeltTimeMs = now
                        // Latch immediately onto any hazard pass currently active
                        for (state in activePassHazards.values) {
                            state.feltBumpDuringPass = true
                        }

                        latestGpsLocation?.let { loc ->
                            val lowSpeedBearing = minSpeedInBumpWindow <= 12.0 || !hasValidBearing
                            val dir = if (loc.hasBearing() && !lowSpeedBearing) loc.bearing.toDouble() else lastValidBearingDegrees
                            pendingCandidate = PendingBumpCandidate(
                                lat = loc.latitude,
                                lng = loc.longitude,
                                bearing = dir,
                                bidirectional = lowSpeedBearing,
                                minSpeedAtBump = minSpeedInBumpWindow,
                                detectedAtMs = now,
                                peakToPeak = peakToPeak
                            )
                        }
                        resetShockWindow()
                    }
                }

                // Window expiry
                if (hasPositiveShock && (now - positiveShockTimeMs > 1500L)) {
                    resetShockWindow()
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val rotRate = sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]).toDouble()).toFloat()
                if (rotRate > 2.4f) {
                    // Rapid hand-held phone twisting/pickup: suppress false bump recording
                    lastHandHeldJitterMs = now
                } else if (rotRate >= 0.28f) {
                    lastGyroRotationTimeMs = now
                    if (hasPositiveShock) {
                        hasPitchRotation = true
                    }
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

                    // Preserve any local bumps not yet on server
                    val localPending = cachedHazards.toList()
                    cachedHazards.clear()
                    cachedHazards.addAll(newHazards)
                    for (lp in localPending) {
                        if (lp.active && cachedHazards.none { distanceMeters(it.lat, it.lng, lp.lat, lp.lng) <= 22.0 }) {
                            cachedHazards.add(lp)
                        }
                    }
                    savePersistedLocalHazards()
                    lastFetchLocation = Location("").apply {
                        latitude = lat
                        longitude = lng
                    }
                    Log.i(TAG, "Cached ${cachedHazards.size} hazards within ${radiusKm}km sector around ($lat, $lng)")
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
    private fun reportNewBumpAsync(lat: Double, lng: Double, bearing: Double, forceBidirectional: Boolean = !hasValidBearing) {
        val isBidirectional = forceBidirectional || !hasValidBearing
        // 1. Check local cache first (including deactivated bumps!)
        val existingLocal = cachedHazards.firstOrNull { h ->
            distanceMeters(lat, lng, h.lat, h.lng) <= 22.0 &&
                (h.bidirectional || isBidirectional || angularDifference(bearing, h.directionDegrees) <= 55.0)
        }
        var wasReactivated = false
        val targetHazard: CachedHazard
        if (existingLocal != null) {
            wasReactivated = !existingLocal.active
            existingLocal.active = true
            existingLocal.consecutiveNegativePasses = 0
            deactivatedSeedIds.remove(existingLocal.id)
            if (isBidirectional) existingLocal.bidirectional = true
            targetHazard = existingLocal
            lastPassSentMs[existingLocal.id] = System.currentTimeMillis()
            savePersistedLocalHazards()
        } else {
            val created = CachedHazard(
                id = "local-${System.currentTimeMillis()}",
                lat = lat,
                lng = lng,
                directionDegrees = bearing,
                bidirectional = isBidirectional,
                roadClass = "مطب مرصود",
                active = true,
                consecutiveNegativePasses = 0,
                needsMapMatch = true
            )
            cachedHazards.add(0, created)
            targetHazard = created
            lastPassSentMs[created.id] = System.currentTimeMillis()
            savePersistedLocalHazards()
        }

        // Silent background save (no disruptive ACK beep while driving)
        mainHandler.post {
            val notice = if (wasReactivated) {
                "تم إعادة تفعيل المطب بعد عودة نمط الاهتزاز وتهدئة السرعة ✅"
            } else {
                "تم اكتشاف وتوثيق مطب جديد (سرعة ≤ 21 كم/س) ✅"
            }
            onBumpDetectedNotice?.invoke(notice)
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

                val vehId = try { vehicleIdProvider() } catch (_: Exception) { "" }
                val payload = JSONObject().apply {
                    put("latitude", lat)
                    put("longitude", lng)
                    put("direction_degrees", bearing)
                    put("bidirectional", isBidirectional)
                    if (vehId.isNotBlank()) put("vehicle_id", vehId)
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }

                if (conn.responseCode in 200..201) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val newId = resp.optString("id", "")
                    val serverStatus = resp.optString("status", "created")
                    if (newId.isNotBlank()) {
                        targetHazard.id = newId
                        savePersistedLocalHazards()
                    }
                    Log.i(TAG, "Successfully reported/reactivated bump at ($lat, $lng): $newId ($serverStatus)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Offline/local bump cached safely: ${e.message}")
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

                val vehId = try { vehicleIdProvider() } catch (_: Exception) { "" }
                val payload = JSONObject().apply {
                    put("hazard_id", hazardId)
                    put("felt_bump", feltBump)
                    put("speed_kmh", speedKmh)
                    if (vehId.isNotBlank()) put("vehicle_id", vehId)
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
                        savePersistedLocalHazards()
                        Log.i(TAG, "Hazard $hazardId deactivated after 3 negative passes (awaiting future pattern to re-activate)")
                    } else if (status == "ACTIVE") {
                        cachedHazards.firstOrNull { it.id == hazardId }?.let {
                            it.active = true
                            it.consecutiveNegativePasses = 0
                        }
                        savePersistedLocalHazards()
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
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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
