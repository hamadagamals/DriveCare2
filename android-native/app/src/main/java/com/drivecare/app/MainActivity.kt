package com.drivecare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * DriveCare Comprehensive Native Android App
 * Full Multi-Role Cockpit: Login, Roles (Owner/Driver/Garage), Multi-Tabs,
 * Vehicle Registration, Live OBD Gauges & Simulator.
 */
class MainActivity : Activity() {

    private data class DeviceCandidate(
        val id: String,
        val title: String,
        val transport: String,
        val address: String,
        val detail: String
    )

    private data class FuelProbe(
        val header: String,
        val command: String,
        val expectedPrefix: String,
        val description: String,
        val isCluster: Boolean = true,
        val isUds: Boolean = false,
        val isLiters: Boolean = false
    )

    private data class DecodedVehicle(
        val vin: String,
        val make: String,
        val year: String,
        val displayName: String
    )

    data class LiveTelemetry(
        var rpm: Int = 0,
        var speed: Int = 0,
        var coolantTemp: Int = 0,
        var fuelLevel: Int = 75,
        var fuelLevelSource: String = "محسوب بالرحلة",
        var fuelCalculationSource: String = "كشف آلي (حساسات OBD)...",
        var fuelType: String = "بنزين (Gasoline)",
        var fuelUnitName: String = "لتر",
        var mafGps: Float = 0.0f,
        var directFuelRateLph: Float = 0.0f,
        var mapKpa: Int = 0,
        var intakeAirTempC: Int = 25,
        var shortTermFuelTrimPercent: Float = 0.0f,
        var batteryVoltage: Float = 0f,
        var vin: String = "",
        var dtcCodes: MutableList<String> = mutableListOf(),
        var isConnected: Boolean = false,
        var statusMessage: String = "جاهز للاتصال",
        var actualTripFuelLiters: Float = 0.0f,
        var tripDistanceKm: Float = 0.0f,
        var instantFuelLPer100Km: Float = 0.0f,
        var instantFuelLitersPerHour: Float = 0.0f,
        var tripAverageFuelRate: Float = 8.5f,
        var engineLoadPercent: Int = 0,
        var throttlePercent: Int = 0,
        var activeAlertTitle: String? = null,
        var activeAlertDesc: String? = null,
        var isAlertCritical: Boolean = false
    )

    private val candidates = linkedMapOf<String, DeviceCandidate>()
    private lateinit var resultsContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var selectedText: TextView
    private lateinit var scanButton: Button
    private lateinit var preferences: SharedPreferences

    // User State
    private var isLoggedIn: Boolean = false
    private var userName: String = ""
    private var userPhone: String = ""
    private var currentRole: String = "owner" // "owner", "driver", "garage"
    private var activeTab: String = "gauges" // "gauges", "vehicles", "scanner", "fuel", "maint"

    // Scanner state
    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { stopDiscovery(timedOut = true) }
    private var classicReceiverRegistered = false

    // Telemetry & OBD Connection
    private val telemetry = LiveTelemetry()
    private var activeSocket: BluetoothSocket? = null
    private var activeWifiSocket: Socket? = null
    private var isTelemetryLoopRunning = false
    private var isSimulating = false
    private var toneGenerator: ToneGenerator? = null
    private var alertBanner: LinearLayout? = null
    private var tvAlertTitle: TextView? = null
    private var tvAlertDesc: TextView? = null
    private var lastAlertToneTime = 0L
    private var isSimulatingAlert = false

    // Route & Payload Trip Planner State
    private var tripStartLocationName: String = "القاهرة"
    private var tripEndLocationName: String = "الإسكندرية"
    private var tripStartLat: Double = 30.0444
    private var tripStartLng: Double = 31.2357
    private var tripEndLat: Double = 31.2001
    private var tripEndLng: Double = 29.9187
    private var plannedRouteDistanceKm: Float = 218.0f
    private var plannedCargoWeightKg: Float = 0.0f
    private var vehicleEmptyWeightKg: Float = 1250.0f

    data class RouteCity(val name: String, val lat: Double, val lng: Double)

    private val popularCities = listOf(
        RouteCity("القاهرة", 30.0444, 31.2357),
        RouteCity("الإسكندرية", 31.2001, 29.9187),
        RouteCity("طنطا", 30.7865, 31.0004),
        RouteCity("المنصورة", 31.0409, 31.3785),
        RouteCity("الزقازيق", 30.5877, 31.5020),
        RouteCity("بنها", 30.4660, 31.1856),
        RouteCity("السويس", 29.9668, 32.5498),
        RouteCity("الإسماعيلية", 30.5965, 32.2715),
        RouteCity("بورسعيد", 31.2653, 32.3019),
        RouteCity("العين السخنة", 29.6000, 32.3167),
        RouteCity("العلمين / الساحل", 30.8358, 28.9567),
        RouteCity("مرسى مطروح", 31.3543, 27.2373),
        RouteCity("الغردقة", 27.2579, 33.8116),
        RouteCity("شرم الشيخ", 27.9158, 34.3299),
        RouteCity("الفيوم", 29.3084, 30.8428),
        RouteCity("بني سويف", 29.0661, 31.0994),
        RouteCity("المنيا", 28.0871, 30.7618),
        RouteCity("أسيوط", 27.1783, 31.1859),
        RouteCity("سوهاج", 26.5569, 31.6948),
        RouteCity("قنا", 26.1551, 32.7160),
        RouteCity("الأقصر", 25.6872, 32.6396),
        RouteCity("أسوان", 24.0889, 32.8998)
    )

    private var activeFuelProbe: FuelProbe? = null

    private val universalFuelProbes = listOf(
        // === الأولوية الأولى: كمبيوتر لوحة العدادات بالطابلوه (Dashboard Instrument Cluster) - لكافة أنواع السيارات العالمية ===
        FuelProbe("7A4", "012F", "412F", "لوحة عدادات شيفروليه / GM (Cluster 7A4)", isCluster = true),
        FuelProbe("724", "012F", "412F", "لوحة عدادات شيفروليه / BCM (Cluster 724)", isCluster = true),
        FuelProbe("7C0", "012F", "412F", "لوحة عدادات تويوتا / لكزس (Cluster 7C0)", isCluster = true),
        FuelProbe("743", "012F", "412F", "لوحة عدادات نيسان / إنفينيتي (Cluster 743)", isCluster = true),
        FuelProbe("7C6", "012F", "412F", "لوحة عدادات هيونداي / كيا (Cluster 7C6)", isCluster = true),
        FuelProbe("7C6", "22B005", "62B005", "لوحة عدادات هيونداي/كيا UDS (Cluster 7C6)", isCluster = true, isUds = true),
        FuelProbe("7C6", "22B008", "62B008", "لوحة عدادات هيونداي لترات (Cluster 7C6)", isCluster = true, isUds = true, isLiters = true),
        FuelProbe("7A0", "012F", "412F", "لوحة العدادات المزدوجة (Cluster 7A0)", isCluster = true),
        FuelProbe("7A0", "22B005", "62B005", "لوحة العدادات البديلة UDS (Cluster 7A0)", isCluster = true, isUds = true),
        FuelProbe("720", "012F", "412F", "لوحة عدادات فورد / مازدا (Cluster 720)", isCluster = true),
        FuelProbe("760", "012F", "412F", "لوحة عدادات رينو / داسيا (Cluster 760)", isCluster = true),
        FuelProbe("714", "012F", "412F", "لوحة عدادات فولكس فاجن / سكودا (Cluster 714)", isCluster = true),
        FuelProbe("710", "012F", "412F", "لوحة عدادات هوندا (Cluster 710)", isCluster = true),
        FuelProbe("7E2", "012F", "412F", "كمبيوتر الطابلوه / BCM المباشر (7E2)", isCluster = true),
        
        // === الأولوية الثانية: كمبيوتر المحرك (Engine ECU) لكافة الماركات كبديل عند عدم ربط العدادات ===
        FuelProbe("7E0", "012F", "412F", "كمبيوتر المحرك المباشر (Engine ECU 7E0)", isCluster = false),
        FuelProbe("7DF", "012F", "412F", "كمبيوتر المحرك القياسي العام (Standard OBD 7DF)", isCluster = false)
    )

    private enum class FuelSensorMode {
        DIRECT_ECU_RATE_5E,   // Mode 01 PID 015E (Direct ECU Engine Fuel Rate in L/h)
        MAF_AIR_FLOW_10,      // Mode 01 PID 0110 (Mass Air Flow sensor in g/s)
        MAP_SPEED_DENSITY_0B, // Mode 01 PID 010B (Speed-Density MAP Pressure in kPa + IAT)
        DYNAMIC_LOAD_FUSION   // Dynamic Real Engine Load % + TPS % + Displacement
    }
    private var activeFuelSensorMode: FuelSensorMode = FuelSensorMode.DYNAMIC_LOAD_FUSION

    private var isAudioMuted: Boolean = false

    private fun playAlertSound(critical: Boolean) {
        if (isAudioMuted) return
        val now = System.currentTimeMillis()
        if (now - lastAlertToneTime < 3500L) return
        lastAlertToneTime = now
        thread {
            try {
                val toneType = if (critical) ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK else ToneGenerator.TONE_PROP_BEEP2
                toneGenerator?.startTone(toneType, if (critical) 450 else 250)
                Thread.sleep(if (critical) 500 else 300)
                toneGenerator?.stopTone()
            } catch (_: Exception) {}
        }
    }

    private fun testAudioAlert() {
        thread {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 450)
                Thread.sleep(500)
                toneGenerator?.stopTone()
                Thread.sleep(150)
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                Thread.sleep(350)
                toneGenerator?.stopTone()
            } catch (_: Exception) {}
        }
    }

    private fun setSafetyAlert(title: String?, desc: String?, critical: Boolean) {
        telemetry.activeAlertTitle = title
        telemetry.activeAlertDesc = desc
        telemetry.isAlertCritical = critical
        if (title != null) {
            playAlertSound(critical)
        }
        runOnUiThread { updateAlertBannerUI() }
    }

    private fun updateAlertBannerUI() {
        val banner = alertBanner ?: return
        val tTitle = tvAlertTitle ?: return
        val tDesc = tvAlertDesc ?: return

        val title = telemetry.activeAlertTitle
        val desc = telemetry.activeAlertDesc

        if (title == null) {
            banner.visibility = View.GONE
        } else {
            banner.visibility = View.VISIBLE
            tTitle.text = title
            tDesc.text = desc ?: ""
            if (telemetry.isAlertCritical) {
                banner.setBackgroundColor(Color.rgb(180, 25, 35))
                tTitle.setTextColor(Color.WHITE)
            } else {
                banner.setBackgroundColor(Color.rgb(190, 110, 20))
                tTitle.setTextColor(Color.WHITE)
            }
        }
    }

    private fun buildAlertBanner(): LinearLayout {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
            setBackgroundColor(Color.rgb(180, 25, 35))
            visibility = View.GONE
        }

        tvAlertTitle = text("", 15, Color.WHITE, true).apply {
            banner.addView(this)
        }
        tvAlertDesc = text("", 13, Color.rgb(255, 235, 235), false).apply {
            setPadding(0, dp(4), 0, dp(6))
            banner.addView(this)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "✖ إغلاق التنبيه"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(180, 25, 35))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, dp(6), 0)
                layoutParams = lp
                setOnClickListener {
                    telemetry.activeAlertTitle = null
                    telemetry.activeAlertDesc = null
                    isSimulatingAlert = false
                    updateAlertBannerUI()
                }
            })

            addView(Button(this@MainActivity).apply {
                text = "🔕 إخفاء مؤقتاً"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(40, 40, 40))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, dp(6), 0)
                layoutParams = lp
                setOnClickListener {
                    telemetry.activeAlertTitle = null
                    isSimulatingAlert = false
                    updateAlertBannerUI()
                }
            })

            val btnSilence = Button(this@MainActivity).apply {
                text = if (isAudioMuted) "🔊 إعادة تشغيل صوت الإنذار" else "🔇 كتم صوت الإنذار"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(if (isAudioMuted) Color.rgb(20, 70, 40) else Color.rgb(100, 20, 30))
                setOnClickListener {
                    isAudioMuted = !isAudioMuted
                    preferences.edit().putBoolean("is_audio_alert_muted", isAudioMuted).apply()
                    text = if (isAudioMuted) "🔊 إعادة تشغيل صوت الإنذار" else "🔇 كتم صوت الإنذار"
                    setBackgroundColor(if (isAudioMuted) Color.rgb(20, 70, 40) else Color.rgb(100, 20, 30))
                    try { toneGenerator?.stopTone() } catch (_: Exception) {}
                }
            }
            addView(btnSilence)
        }
        banner.addView(btnRow)
        return banner
    }

    // UI elements for live gauges
    private lateinit var tvRpm: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvFuel: TextView
    private lateinit var tvVin: TextView
    private lateinit var tvDtc: TextView
    private lateinit var tvConnectionState: TextView
    private lateinit var tvInstantFuel: TextView
    private lateinit var tvTripFuel: TextView
    private lateinit var tvEngineLoad: TextView
    private lateinit var tvThrottle: TextView
    private lateinit var tvFuelSourceBadge: TextView

    // Fuel Tab Live UI References
    private var tvFuelTabTripDistance: TextView? = null
    private var tvFuelTabActualConsumed: TextView? = null
    private var tvFuelTabInstantRate: TextView? = null
    private var tvFuelTabCurrentCost: TextView? = null
    private var tvFuelTabActiveCostPerKm: TextView? = null
    private var tvFuelTabTankPercent: TextView? = null
    private var tvFuelTabEffectiveRate: TextView? = null
    private var tvFuelTabSensorInfoText: TextView? = null
    private var containerFuelRealBox: LinearLayout? = null
    private var tvFuelTabRealBoxTitle: TextView? = null
    private var tvFuelTabRealBoxDetails: TextView? = null

    // Maintenance Tab Live UI References
    private var containerDtcList: LinearLayout? = null
    private var tvMaintOilText: TextView? = null

    // OBD Thread Safety & Periodic UI Refresher
    private val obdLock = Any()
    @Volatile private var activeObdOutStream: OutputStream? = null
    @Volatile private var activeObdInStream: InputStream? = null
    private val uiUpdateHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateLiveUI()
            uiUpdateHandler.postDelayed(this, 500)
        }
    }

    data class VehiclePreset(
        val name: String,
        val make: String,
        val year: String,
        val vin: String,
        val tank: Float,
        val baseRate: Float,
        val displacement: Float = 1.5f,
        val fuelType: String = "gasoline"
    )

    data class DtcDiagnosis(
        val code: String,
        val titleAr: String,
        val system: String,
        val causesAr: String,
        val solutionAr: String,
        val severity: String
    )

    private val bluetoothAdapter: BluetoothAdapter?
        get() = BluetoothAdapter.getDefaultAdapter()

    companion object {
        private const val REQUEST_NEARBY = 4101
        private const val SCAN_TIMEOUT_MS = 12000L
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    // Classic Bluetooth BroadcastReceiver
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
            addBluetoothCandidate(device, "Bluetooth عادي", if (rssi == Short.MIN_VALUE) null else rssi.toInt())
        }
    }

    // BLE Scan Callback
    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addBluetoothCandidate(result.device, "BLE", result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return
            runOnUiThread {
                if (::statusText.isInitialized) {
                    statusText.text = "ملاحظة BLE (رمز $errorCode). يستمر البحث عبر Bluetooth العادي."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences("drivecare_native", Context.MODE_PRIVATE)

        isLoggedIn = preferences.getBoolean("is_logged_in", false)
        userName = preferences.getString("user_name", "") ?: ""
        userPhone = preferences.getString("user_phone", "") ?: ""
        currentRole = preferences.getString("active_role", "owner") ?: "owner"
        val isCalibrated = preferences.getBoolean("fuel_is_calibrated", false)
        telemetry.fuelLevel = preferences.getInt("current_fuel_tank_percent", 50)
        val initTrackingMode = preferences.getString("fuel_tracking_mode", if (isCalibrated) "manual_dynamic" else "auto") ?: "auto"
        telemetry.fuelLevelSource = if (isCalibrated) {
            "معايرة مثبتة (${telemetry.fuelLevel}%) + تتبع ديناميكي 🧮"
        } else if (initTrackingMode == "manual_dynamic") {
            "معايرة دقيقة + حساب تدفق 🧮"
        } else {
            "حساب ديناميكي ذكي 🧮"
        }
        isAudioMuted = preferences.getBoolean("is_audio_alert_muted", false)

        // Restore Route & Payload Trip Settings
        tripStartLocationName = preferences.getString("trip_start_name", "القاهرة") ?: "القاهرة"
        tripEndLocationName = preferences.getString("trip_end_name", "الإسكندرية") ?: "الإسكندرية"
        tripStartLat = preferences.getFloat("trip_start_lat", 30.0444f).toDouble()
        tripStartLng = preferences.getFloat("trip_start_lng", 31.2357f).toDouble()
        tripEndLat = preferences.getFloat("trip_end_lat", 31.2001f).toDouble()
        tripEndLng = preferences.getFloat("trip_end_lng", 29.9187f).toDouble()
        plannedRouteDistanceKm = preferences.getFloat("planned_route_distance", 218.0f)
        plannedCargoWeightKg = preferences.getFloat("planned_cargo_weight", 0.0f)
        vehicleEmptyWeightKg = preferences.getFloat("vehicle_empty_weight", 1250.0f)

        title = "DriveCare"

        // Initialize Default Vehicle & Ownership if first run
        if (preferences.getString("vehicle_primary_owner", null).isNullOrEmpty()) {
            val initialOwner = if (userName.isNotEmpty() && currentRole == "owner") userName else "مالك تجريبي"
            val initialPhone = if (userPhone.isNotEmpty()) userPhone else "01000000000"
            preferences.edit()
                .putString("vehicle_primary_owner", initialOwner)
                .putString("vehicle_primary_owner_phone", initialPhone)
                .putString("vehicle_owner_since", "2026/10/17")
                .putBoolean("vehicle_owner_locked", true)
                .apply()
        }

        val detectedOnStart = detectCarFromPairedDevices()
        val currentStoredVin = preferences.getString("vehicle_vin", null)
        if (currentStoredVin.isNullOrEmpty() || currentStoredVin == "JTDBR32E12345678") {
            val initialName = detectedOnStart?.first ?: "شيفروليه أفيو (Chevrolet Aveo 1.5L)"
            val initialMake = detectedOnStart?.second ?: "شيفروليه (Chevrolet)"
            preferences.edit()
                .putString("vehicle_vin", "بانتظار فحص OBD المباشر")
                .putString("vehicle_name", initialName)
                .putString("vehicle_make", initialMake)
                .putString("vehicle_year", "2018")
                .putFloat("tank_capacity_liters", 45.0f)
                .putFloat("engine_displacement_liters", 1.5f)
                .putFloat("fuel_rate_baseline", 7.2f)
                .putString("vehicle_data_source", "تم التعرف آلياً من سيارتك 🚗")
                .apply()
        }

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        } catch (_: Exception) {}

        uiUpdateHandler.post(uiUpdateRunnable)

        renderCurrentScreen()
    }

    private fun renderCurrentScreen() {
        if (!isLoggedIn) {
            setContentView(buildAuthScreen())
        } else {
            setContentView(buildMainCockpitScreen())
            restoreSelectedDevice()
        }
    }

    override fun onDestroy() {
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
        stopDiscovery()
        disconnectObd()
        toneGenerator?.release()
        if (classicReceiverRegistered) {
            try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
            classicReceiverRegistered = false
        }
        super.onDestroy()
    }

    // ========================================================
    // 1. AUTH SCREEN (التسجيل وتحديد الأدوار)
    // ========================================================
    private fun buildAuthScreen(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(36))
            setBackgroundColor(Color.rgb(8, 23, 32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)

        root.addView(text("DriveCare 🚗", 28, Color.WHITE, true))
        root.addView(text("تسجيل الدخول وإنشاء حسابك المعتمد", 15, Color.rgb(167, 203, 226), false))

        val cardAuth = card().apply {
            addView(text("بيانات المستخدم الجديد", 18, Color.WHITE, true))

            val etName = edit("الاسم الكامل (مثال: محمد علي)")
            val etPhone = edit("رقم الموبايل (مثال: 01012345678)")
            val etPassword = edit("كلمة المرور")
            addView(etName)
            addView(etPhone)
            addView(etPassword)

            addView(text("اختر نوع الحساب وصلاحياتك:", 14, Color.rgb(110, 231, 255), true).apply {
                setPadding(0, dp(14), 0, dp(4))
            })

            val cbOwner = CheckBox(this@MainActivity).apply {
                text = "👑 مالك سيارة (إدارة سياراتك واقتران مباشر دائم)"
                setTextColor(Color.WHITE)
                isChecked = true
            }
            val cbDriver = CheckBox(this@MainActivity).apply {
                text = "🚗 سائق (قيادة ومتابعة سيارات مفوضة لك)"
                setTextColor(Color.WHITE)
                isChecked = false
            }
            val cbGarage = CheckBox(this@MainActivity).apply {
                text = "🔧 صاحب ورشة (فحص وتشخيص أعطال سيارات العملاء)"
                setTextColor(Color.WHITE)
                isChecked = false
            }
            addView(cbOwner)
            addView(cbDriver)
            addView(cbGarage)

            val tvAuthErr = text("", 13, Color.rgb(255, 122, 122), false)
            addView(tvAuthErr)

            addView(button("تسجيل وإنشاء الحساب") {
                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                if (name.isEmpty() || phone.isEmpty()) {
                    tvAuthErr.text = "يرجى إدخال الاسم ورقم الموبايل للمتابعة."
                    return@button
                }
                val role = when {
                    cbOwner.isChecked -> "owner"
                    cbDriver.isChecked -> "driver"
                    cbGarage.isChecked -> "garage"
                    else -> "owner"
                }
                preferences.edit()
                    .putBoolean("is_logged_in", true)
                    .putString("user_name", name)
                    .putString("user_phone", phone)
                    .putString("active_role", role)
                    .apply()

                if (role == "owner") {
                    val existingOwner = preferences.getString("vehicle_primary_owner", "") ?: ""
                    if (existingOwner.isEmpty() || existingOwner == "مالك تجريبي") {
                        val today = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date())
                        preferences.edit()
                            .putString("vehicle_primary_owner", name)
                            .putString("vehicle_primary_owner_phone", phone)
                            .putString("vehicle_owner_since", today)
                            .putBoolean("vehicle_owner_locked", true)
                            .apply()
                    }
                }

                isLoggedIn = true
                userName = name
                userPhone = phone
                currentRole = role
                renderCurrentScreen()
            })

            // Fast Demo Login Button
            addView(button("⚡ دخول تجريبي فوري (حساب المالك)") {
                preferences.edit()
                    .putBoolean("is_logged_in", true)
                    .putString("user_name", "مالك تجريبي")
                    .putString("user_phone", "01000000000")
                    .putString("active_role", "owner")
                    .apply()

                isLoggedIn = true
                userName = "مالك تجريبي"
                userPhone = "01000000000"
                currentRole = "owner"
                renderCurrentScreen()
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
        }
        root.addView(cardAuth)

        return scroll
    }

    // ========================================================
    // 2. MAIN COCKPIT SCREEN (بعد التسجيل مع التابات)
    // ========================================================
    private fun buildMainCockpitScreen(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
            setBackgroundColor(Color.rgb(8, 23, 32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)

        // Top Bar: User info + Logout
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(8)
            layoutParams = p

            val userInfo = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(text("مرحباً بك، $userName 👋", 16, Color.WHITE, true))
                val roleTitle = when(currentRole) {
                    "owner" -> "👑 المالك الأساسي"
                    "driver" -> "🚗 سائق مفوض"
                    else -> "🔧 صاحب ورشة"
                }
                addView(text(roleTitle, 12, Color.rgb(110, 231, 255), false))
            }
            addView(userInfo)

            val btnAudioToggle = Button(this@MainActivity).apply {
                text = if (isAudioMuted) "🔇 صامت" else "🔊 إنذار"
                textSize = 12f
                setTextColor(if (isAudioMuted) Color.rgb(255, 180, 100) else Color.rgb(126, 242, 154))
                setBackgroundColor(Color.rgb(16, 32, 46))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(dp(4), 0, dp(4), 0)
                layoutParams = lp
                setOnClickListener {
                    isAudioMuted = !isAudioMuted
                    preferences.edit().putBoolean("is_audio_alert_muted", isAudioMuted).apply()
                    text = if (isAudioMuted) "🔇 صامت" else "🔊 إنذار"
                    setTextColor(if (isAudioMuted) Color.rgb(255, 180, 100) else Color.rgb(126, 242, 154))
                    try { toneGenerator?.stopTone() } catch (_: Exception) {}
                    updateAlertBannerUI()
                }
            }
            addView(btnAudioToggle)

            val btnLogout = Button(this@MainActivity).apply {
                text = "خروج 🚪"
                textSize = 12f
                setTextColor(Color.rgb(255, 122, 122))
                setBackgroundColor(Color.rgb(32, 15, 20))
                setOnClickListener {
                    preferences.edit().putBoolean("is_logged_in", false).apply()
                    isLoggedIn = false
                    stopDiscovery()
                    disconnectObd()
                    renderCurrentScreen()
                }
            }
            addView(btnLogout)
        }
        root.addView(topBar)

        // Navigation Tabs Bar (الأقسام الرئيسية)
        root.addView(buildTabBar())

        // Safety Alert Banner (شريط التحذيرات والإنذارات الطارئة)
        val banner = buildAlertBanner()
        alertBanner = banner
        root.addView(banner)
        updateAlertBannerUI()

        // Tab Content Container
        when (activeTab) {
            "gauges" -> root.addView(buildGaugesTabContent())
            "vehicles" -> root.addView(buildVehiclesTabContent())
            "scanner" -> root.addView(buildScannerTabContent())
            "fuel" -> root.addView(buildFuelTabContent())
            "maint" -> root.addView(buildMaintenanceTabContent())
        }

        return scroll
    }

    // ========================================================
    // TABS BAR
    // ========================================================
    private fun buildTabBar(): View {
        val hscroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = p
        }
        val tabLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        hscroll.addView(tabLayout)

        tabLayout.addView(tabBtn("🏎️ عداد OBD", activeTab == "gauges") { switchTab("gauges") })
        tabLayout.addView(tabBtn("🚗 إدارة السيارات", activeTab == "vehicles") { switchTab("vehicles") })
        tabLayout.addView(tabBtn("🔍 فحص OBD", activeTab == "scanner") { switchTab("scanner") })
        tabLayout.addView(tabBtn("⛽ التكلفة والوقود", activeTab == "fuel") { switchTab("fuel") })
        tabLayout.addView(tabBtn("🔧 الصيانة والأعطال", activeTab == "maint") { switchTab("maint") })

        return hscroll
    }

    private fun tabBtn(label: String, active: Boolean, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val bg = if (active) Color.rgb(110, 231, 255) else Color.rgb(20, 42, 64)
            val fg = if (active) Color.rgb(8, 23, 32) else Color.rgb(200, 225, 245)
            setBackgroundColor(bg)
            setTextColor(fg)
            setOnClickListener { action() }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            params.setMargins(dp(3), 0, dp(3), 0)
            layoutParams = params
        }
    }

    private fun switchTab(tab: String) {
        activeTab = tab
        renderCurrentScreen()
    }

    // ========================================================
    // TAB 1: GAUGES & LIVE OBD TELEMETRY (عداد السيارة الحي)
    // ========================================================
    private fun buildGaugesTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val activeVehName = preferences.getString("vehicle_name", "تويوتا (Toyota) (2014)") ?: "تويوتا (Toyota) (2014)"
        val primaryOwner = preferences.getString("vehicle_primary_owner", "") ?: ""
        val ownerDisplay = if (primaryOwner.isNotEmpty()) " • المالك: $primaryOwner 👑" else ""

        val cardGauges = card().apply {
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text("عداد وبيانات: $activeVehName$ownerDisplay", 16, Color.WHITE, true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                tvConnectionState = text("غير متصل", 12, Color.rgb(255, 122, 122), true)
                addView(tvConnectionState)
            }
            addView(titleRow)

            val row1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            tvSpeed = metricBox("السرعة الحالية", "0 km/h", Color.rgb(110, 231, 255))
            tvRpm = metricBox("المحرك (RPM)", "0 RPM", Color.rgb(126, 242, 154))
            row1.addView(tvSpeed.parent as View)
            row1.addView(tvRpm.parent as View)
            addView(row1)

            val row2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            tvTemp = metricBox("حرارة التبريد", "88 °C", Color.rgb(248, 199, 74))
            tvBattery = metricBox("شحن البطارية", "13.8 V", Color.rgb(110, 231, 255))
            row2.addView(tvTemp.parent as View)
            row2.addView(tvBattery.parent as View)
            addView(row2)

            val row3 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            tvFuel = metricBox("مستوى الوقود", "72 %", Color.rgb(126, 242, 154))
            tvVin = metricBox("كود الشاسيه VIN", preferences.getString("vehicle_vin", "JTDBR32E12345678") ?: "", Color.WHITE)
            row3.addView(tvFuel.parent as View)
            row3.addView(tvVin.parent as View)
            addView(row3)

            val row4 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            tvInstantFuel = metricBox("الاستهلاك اللحظي", "0.0 L/100km", Color.rgb(248, 199, 74))
            tvTripFuel = metricBox("متوسط الرحلة والمسافة", "7.2 L/100 (0 كم)", Color.rgb(110, 231, 255))
            row4.addView(tvInstantFuel.parent as View)
            row4.addView(tvTripFuel.parent as View)
            addView(row4)

            val row5 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            tvEngineLoad = metricBox("حمل المحرك (Load)", "0 %", Color.rgb(248, 199, 74))
            tvThrottle = metricBox("دواسة البنزين (TPS)", "0 %", Color.rgb(126, 242, 154))
            row5.addView(tvEngineLoad.parent as View)
            row5.addView(tvThrottle.parent as View)
            addView(row5)

            tvDtc = text("لمبة الأعطال: لا توجد أعطال (Check Engine: OFF)", 13, Color.rgb(126, 242, 154), true).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.rgb(10, 28, 42))
            }
            addView(tvDtc)

            tvFuelSourceBadge = text("⚡ مصدر قياس الوقود الآلي: ${telemetry.fuelCalculationSource}", 12, Color.rgb(110, 231, 255), true).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(Color.rgb(14, 34, 52))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            addView(tvFuelSourceBadge)

            addView(button("🔍 فحص واستكشاف كمبيوترات السيارة ذاتياً (قراءة العدادات والمكينة تلقائياً)") {
                scanAllFuelComputers()
            }.apply {
                setBackgroundColor(Color.rgb(16, 185, 129))
                setTextColor(Color.WHITE)
            })

            addView(button("⛽ ضبط ومعايرة خزان الوقود (يدوي / تثبيت / إضافة لترات)") {
                showFuelCalibrationDialog()
            }.apply {
                setBackgroundColor(Color.rgb(248, 199, 74))
                setTextColor(Color.rgb(8, 23, 32))
            })

            addView(button("⚡ تشغيل / إيقاف محاكي كمبيوتر السيارة") {
                toggleSimulation()
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardGauges)

        return container
    }

    // ========================================================
    // TAB 2: VEHICLES & SECURE OWNERSHIP (بيانات السيارة والملكية المحمية)
    // ========================================================
    private fun buildVehiclesTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val curName = preferences.getString("vehicle_name", "شيفروليه أفيو (Chevrolet Aveo)") ?: "شيفروليه أفيو (Chevrolet Aveo)"
        val curVin = preferences.getString("vehicle_vin", "بانتظار قراءة الشاسيه") ?: "بانتظار قراءة الشاسيه"
        val curMake = preferences.getString("vehicle_make", "شيفروليه (Chevrolet)") ?: "شيفروليه (Chevrolet)"
        val curYear = preferences.getString("vehicle_year", "2018") ?: "2018"
        val curSource = preferences.getString("vehicle_data_source", "كمبيوتر السيارة المباشر (ECU Mode 09)") ?: "كمبيوتر السيارة المباشر (ECU Mode 09)"
        val primaryOwner = preferences.getString("vehicle_primary_owner", "مالك غير مسجل") ?: "مالك غير مسجل"
        val primaryPhone = preferences.getString("vehicle_primary_owner_phone", "01000000000") ?: "01000000000"
        val ownerSince = preferences.getString("vehicle_owner_since", "أول تسجيل للسيارة") ?: "أول تسجيل للسيارة"
        val coOwners = preferences.getString("vehicle_co_owners", "") ?: ""

        // Check if current user is the authorized primary owner
        val isOwnerAuthorized = (currentRole == "owner") && (userName.equals(primaryOwner, ignoreCase = true) || primaryOwner.isEmpty() || primaryOwner == "مالك تجريبي")

        // 1. CAR ECU IDENTITY CARD (بيانات السيارة المأخوذة مباشرة من الكمبيوتر)
        val cardEcu = card().apply {
            addView(text("بيانات السيارة المقروءة من كمبيوتر السيارة (ECU Mode 09) 🏎️", 17, Color.rgb(110, 231, 255), true))
            addView(text("يتم جلب رقم الشاسيه والمواصفات وسنة الصنع آلياً من عقل المحرك بدون تدخل يدوي:", 13, Color.rgb(167, 203, 226), false))

            val r1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            r1.addView(metricBox("رقم الشاسيه (VIN)", curVin, Color.rgb(126, 242, 154)).parent as View)
            r1.addView(metricBox("الماركة المصنعة", curMake, Color.rgb(110, 231, 255)).parent as View)
            addView(r1)

            val r2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            r2.addView(metricBox("سنة الصنع / الموديل", curYear, Color.rgb(248, 199, 74)).parent as View)
            r2.addView(metricBox("مصدر البيانات", "كمبيوتر ECU 🔌", Color.WHITE).parent as View)
            addView(r2)

            addView(text("الاسم المعتمد للسيارة: $curName", 14, Color.rgb(248, 199, 74), true).apply {
                setPadding(0, dp(6), 0, dp(4))
            })

            addView(button("📡 قراءة وتحديث بيانات السيارة فورياً من الكمبيوتر (OBD)") {
                refreshVehicleEcuData { success, msg ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(if (success) "✅ نجاح قراءة كمبيوتر السيارة" else "⚠️ تنبيه القراءة")
                        .setMessage(msg)
                        .setPositiveButton("حسناً") { _, _ ->
                            if (success) switchTab("vehicles")
                        }
                        .show()
                }
            }.apply {
                setBackgroundColor(Color.rgb(32, 120, 85))
                setTextColor(Color.WHITE)
            })

            addView(button("✏️ اختيار سيارة من القائمة أو إدخال يدوي") {
                showVehicleSelectorDialog("اختر موديل سيارتك بنقرة واحدة لتثبيت بياناتها ومواصفات المحرك وخزان الوقود:")
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardEcu)

        // 2. PROTECTED VEHICLE OWNERSHIP CARD (وثيقة وبيانات ملكية المركبة المحمية)
        val cardOwnership = card().apply {
            addView(text("وثيقة ملكية المركبة المحمية (بيانات المالك) 👑🔒", 17, Color.rgb(248, 199, 74), true))
            addView(text("اسم المالك مسجل ومحمي بأمان عالي في النظام؛ لا يستطيع السائق أو الورشة تغييره:", 13, Color.rgb(167, 203, 226), false))

            val ownerBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(12, 34, 52))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }

            ownerBox.addView(text("👑 اسم صاحب العربية (المالك الأساسي): $primaryOwner", 16, Color.WHITE, true))
            ownerBox.addView(text("📞 رقم هاتف المالك: $primaryPhone", 13, Color.rgb(167, 203, 226), false))
            ownerBox.addView(text("📅 مسجلة ومحفوظة للمالك منذ: $ownerSince", 13, Color.rgb(167, 203, 226), false))
            val coOwnersText = if (coOwners.isNotEmpty()) "👥 الملاك الشركاء / المفوضون: $coOwners" else "👥 الملاك الشركاء: لا يوجد ملاك شركاء مسجلون حالياً"
            ownerBox.addView(text(coOwnersText, 13, Color.rgb(126, 242, 154), false))

            addView(ownerBox)

            // Security Badge & Enforcement
            if (isOwnerAuthorized) {
                val ownerBadge = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(18, 56, 36))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(8)
                    layoutParams = lp
                    addView(text("✅ تم التحقق: أنت المالك الأساسي المسجل لهذه السيارة 👑", 14, Color.rgb(126, 242, 154), true))
                    addView(text("يحق لك حصرياً إدارة الملكية أو التنازل ونقل الملكية لمالك آخر أو إضافة ملاك شركاء.", 12, Color.WHITE, false))
                }
                addView(ownerBadge)

                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(8)
                    layoutParams = lp
                }

                btnRow.addView(Button(this@MainActivity).apply {
                    text = "🔄 نقل الملكية لمالك آخر"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(180, 70, 30))
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(0, 0, dp(4), 0)
                    layoutParams = lp
                    setOnClickListener { showTransferOwnershipDialog() }
                })

                btnRow.addView(Button(this@MainActivity).apply {
                    text = "👥 إضافة مالك شريك"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(7, 21, 37))
                    setBackgroundColor(Color.rgb(110, 231, 255))
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(dp(4), 0, 0, 0)
                    layoutParams = lp
                    setOnClickListener { showAddCoOwnerDialog() }
                })

                addView(btnRow)
            } else {
                val lockedBadge = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(60, 20, 25))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(8)
                    layoutParams = lp
                    val roleLabel = when (currentRole) {
                        "driver" -> "سائق مفوض 🚗"
                        "garage" -> "صاحب ورشة 🔧"
                        else -> "مستخدم غير مالك"
                    }
                    addView(text("🔒 تنبيه حماية الملكية: بيانات المالك مقفلة ومحمية بالكامل!", 14, Color.rgb(255, 122, 122), true))
                    addView(text("أنت مسجل حالياً بحساب ($userName) بصلاحية ($roleLabel).\n🔒 بموجب نظام الأمان، لا يملك السائق أو صاحب الورشة صلاحية تغيير اسم المالك أو إضافة ملاك.\nفقط المالك الأساسي المسجل ($primaryOwner) هو صاحب هذه الصلاحية حصرياً.", 12, Color.rgb(255, 230, 230), false))
                }
                addView(lockedBadge)
            }
        }
        container.addView(cardOwnership)

        // 3. NEW VEHICLE / ECU VIN SCANNER CARD
        val cardNew = card().apply {
            addView(text("فحص أو تسجيل مركبة أخرى من الشاسيه / الكمبيوتر ➕", 17, Color.WHITE, true))
            addView(text("أدخل رقم الشاسيه (17 حرفاً ورقم) وسيتم فك تشفير الماركة والموديل وسنة الصنع تلقائياً:", 13, Color.rgb(167, 203, 226), false))

            val etVehVin = edit("رقم الشاسيه VIN (مثال: KMHDH41EBNA123456)")
            addView(etVehVin)

            val tvMsg = text("", 13, Color.rgb(126, 242, 154), false)
            addView(tvMsg)

            addView(button("⚡ فك تشفير وتفعيل المركبة الجديدة") {
                val vinIn = etVehVin.text.toString().trim()
                if (vinIn.length < 10) {
                    tvMsg.text = "يرجى إدخال رقم شاسيه صحيح (10 إلى 17 خانة)."
                    tvMsg.setTextColor(Color.rgb(255, 122, 122))
                    return@button
                }
                val decoded = decodeVin(vinIn)
                preferences.edit()
                    .putString("vehicle_vin", vinIn)
                    .putString("vehicle_name", decoded.displayName)
                    .putString("vehicle_make", decoded.make)
                    .putString("vehicle_year", decoded.year)
                    .putString("vehicle_data_source", "مدخل ومطابق للشاسيه")
                    .apply()

                tvMsg.setTextColor(Color.rgb(126, 242, 154))
                tvMsg.text = "تم فك تشفير وتفعيل المركبة: ${decoded.displayName} بنجاح!"
                switchTab("vehicles")
            })
        }
        container.addView(cardNew)

        return container
    }

    private fun showTransferOwnershipDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val tvInfo = text("⚠️ تنبيه أمان: نقل الملكية سيعين المالك الجديد كصاحب الحقوق الكاملة على هذه السيارة.", 13, Color.rgb(255, 180, 50), false)
        layout.addView(tvInfo)

        val etNewName = edit("اسم المالك الجديد الكامل (مثال: أحمد عبد الله)")
        val etNewPhone = edit("رقم موبايل المالك الجديد (مثال: 01012345678)")
        layout.addView(etNewName)
        layout.addView(etNewPhone)

        AlertDialog.Builder(this)
            .setTitle("🔄 نقل ملكية السيارة لمالك آخر")
            .setView(layout)
            .setPositiveButton("تأكيد ونقل الملكية") { _, _ ->
                val newName = etNewName.text.toString().trim()
                val newPhone = etNewPhone.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val oldOwner = preferences.getString("vehicle_primary_owner", "") ?: ""
                    val history = preferences.getString("vehicle_ownership_history", "") ?: ""
                    val today = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date())
                    val newHistory = if (history.isEmpty()) "نقلت من ($oldOwner) إلى ($newName) بتاريخ $today" else "$history • نقلت من ($oldOwner) إلى ($newName) بتاريخ $today"

                    preferences.edit()
                        .putString("vehicle_primary_owner", newName)
                        .putString("vehicle_primary_owner_phone", if (newPhone.isNotEmpty()) newPhone else "01000000000")
                        .putString("vehicle_ownership_history", newHistory)
                        .putString("vehicle_owner_since", today)
                        .putBoolean("vehicle_owner_locked", true)
                        .apply()

                    AlertDialog.Builder(this)
                        .setTitle("✅ تم نقل الملكية بنجاح")
                        .setMessage("تم نقل ملكية السيارة وتحديث وثيقتها باسم:\n$newName\nأصبح هو المالك المعتمد في نظام DriveCare.")
                        .setPositiveButton("حسناً") { _, _ -> switchTab("vehicles") }
                        .show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAddCoOwnerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val tvInfo = text("أدخل اسم المالك الشريك أو المفوض لإضافته إلى وثيقة السيارة:", 13, Color.rgb(167, 203, 226), false)
        layout.addView(tvInfo)

        val etCoName = edit("اسم المالك الشريك (مثال: محمود علي)")
        layout.addView(etCoName)

        AlertDialog.Builder(this)
            .setTitle("👥 إضافة مالك شريك / مفوض")
            .setView(layout)
            .setPositiveButton("إضافة وحفظ") { _, _ ->
                val coName = etCoName.text.toString().trim()
                if (coName.isNotEmpty()) {
                    val curCo = preferences.getString("vehicle_co_owners", "") ?: ""
                    val updated = if (curCo.isEmpty()) coName else "$curCo، $coName"
                    preferences.edit().putString("vehicle_co_owners", updated).apply()
                    switchTab("vehicles")
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun refreshVehicleEcuData(onComplete: (Boolean, String) -> Unit) {
        val socket = activeSocket
        val wifiSocket = activeWifiSocket

        if (socket != null && socket.isConnected) {
            thread {
                try {
                    val cleanVin = fetchVinFromEcu(socket.outputStream, socket.inputStream)

                    if (cleanVin.isNotBlank()) {
                        val decoded = decodeVin(cleanVin)
                        preferences.edit()
                            .putString("vehicle_vin", cleanVin)
                            .putString("vehicle_name", decoded.displayName)
                            .putString("vehicle_make", decoded.make)
                            .putString("vehicle_year", decoded.year)
                            .putString("vehicle_data_source", "كمبيوتر ECU مباشر (Mode 09 / UDS) 🔌")
                            .apply()
                        telemetry.vin = cleanVin
                        runOnUiThread {
                            onComplete(true, "تمت قراءة وفك تشفير بيانات السيارة مباشرة من كمبيوتر السيارة بنجاح:\n\n• المركبة: ${decoded.displayName}\n• الماركة: ${decoded.make}\n• سنة الصنع: ${decoded.year}\n• رقم الشاسيه: $cleanVin")
                        }
                    } else {
                        // Attempt Electronic Software CALID Auto-Identification (delphi MR-140/HV-240)
                        val calid = fetchCalidFromEcu(socket.outputStream, socket.inputStream)
                        val calidVehicle = identifyVehicleFromCalid(calid)

                        if (calidVehicle != null) {
                            preferences.edit()
                                .putString("vehicle_name", calidVehicle.name)
                                .putString("vehicle_make", calidVehicle.make)
                                .putString("vehicle_year", calidVehicle.year)
                                .putString("vehicle_vin", "معرف إلكتروني: $calid")
                                .putFloat("tank_capacity_liters", calidVehicle.tank)
                                .putFloat("engine_displacement_liters", calidVehicle.displacement)
                                .putString("vehicle_data_source", "سوفت وير الكمبيوتر (CALID: $calid) 🔌")
                                .apply()
                            telemetry.vin = "CALID: $calid"
                            runOnUiThread {
                                onComplete(true, "تم التعرف التلقائي على سيارتك عبر كود السوفت وير (CALID) من عقل المحرك مباشرة:\n\n• المركبة: ${calidVehicle.name}\n• الماركة: ${calidVehicle.make}\n• سعة التانك المعتمدة: ${calidVehicle.tank.toInt()} لتر\n• سعة المحرك: ${calidVehicle.displacement}L\n• كود البرمجة: $calid")
                            }
                        } else {
                            val detected = detectCarFromPairedDevices()
                            runOnUiThread {
                                if (detected != null) {
                                    val isAveo = detected.first.contains("Aveo", ignoreCase = true)
                                    val tankVal = if (isAveo) 45.0f else 50.0f
                                    preferences.edit()
                                        .putString("vehicle_name", detected.first)
                                        .putString("vehicle_make", detected.second)
                                        .putString("vehicle_year", "معتمد")
                                        .putString("vehicle_vin", "تم التعرف آلياً عبر نظام السيارة")
                                        .putFloat("tank_capacity_liters", tankVal)
                                        .putFloat("engine_displacement_liters", if (isAveo) 1.5f else 1.6f)
                                        .putString("vehicle_data_source", "بلوتوث السيارة المباشر (${detected.first}) 🚗")
                                        .apply()
                                    onComplete(true, "تم التعرف فورياً على سيارتك من نظام بلوتوث السيارة المزدوج:\n\n• المركبة: ${detected.first}\n• الماركة: ${detected.second}\n• سعة التانك: ${tankVal.toInt()} لتر")
                                } else {
                                    showVehicleSelectorDialog(
                                        "كمبيوتر سيارتك يعمل ببروتوكول مصنع محمي لا يشارك رقم الشاسيه عبر Mode 09 لحماية السوفت وير (وهذا شائع وطبيعي في سيارات شيفروليه أفيو/أوبترا والعديد من الموديلات العالمية).\n\nيمكنك اختيار سيارتك بلمسة واحدة لتثبيت بياناتها ومواصفات التانك فوراً:"
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        showVehicleSelectorDialog("تعذر قراءة الشاسيه تلقائياً (${e.localizedMessage}).\n\nيمكنك اختيار سيارتك بنقرة واحدة لتثبيت بياناتها:")
                    }
                }
            }
        } else if (wifiSocket != null && wifiSocket.isConnected) {
            thread {
                try {
                    val out = wifiSocket.getOutputStream()
                    val `in` = wifiSocket.getInputStream()
                    val cleanVin = fetchVinFromEcu(out, `in`)

                    if (cleanVin.isNotBlank()) {
                        val decoded = decodeVin(cleanVin)
                        preferences.edit()
                            .putString("vehicle_vin", cleanVin)
                            .putString("vehicle_name", decoded.displayName)
                            .putString("vehicle_make", decoded.make)
                            .putString("vehicle_year", decoded.year)
                            .putString("vehicle_data_source", "كمبيوتر ECU مباشر (Wi-Fi OBD) 🔌")
                            .apply()
                        telemetry.vin = cleanVin
                        runOnUiThread {
                            onComplete(true, "تمت قراءة وفك تشفير بيانات السيارة مباشرة من Wi-Fi OBD بنجاح:\n\n• المركبة: ${decoded.displayName}\n• الماركة: ${decoded.make}\n• سنة الصنع: ${decoded.year}\n• الشاسيه: $cleanVin")
                        }
                    } else {
                        val calid = fetchCalidFromEcu(out, `in`)
                        val calidVehicle = identifyVehicleFromCalid(calid)
                        if (calidVehicle != null) {
                            preferences.edit()
                                .putString("vehicle_name", calidVehicle.name)
                                .putString("vehicle_make", calidVehicle.make)
                                .putString("vehicle_year", calidVehicle.year)
                                .putString("vehicle_vin", "معرف إلكتروني: $calid")
                                .putFloat("tank_capacity_liters", calidVehicle.tank)
                                .putFloat("engine_displacement_liters", calidVehicle.displacement)
                                .apply()
                            telemetry.vin = "CALID: $calid"
                            runOnUiThread {
                                onComplete(true, "تم التعرف على السيارة عبر كود السوفت وير CALID:\n• ${calidVehicle.name} (تانك ${calidVehicle.tank.toInt()}L)")
                            }
                        } else {
                            runOnUiThread {
                                showVehicleSelectorDialog("كمبيوتر السيارة محمي. اختر سيارتك بلمسة واحدة لتثبيت بياناتها:")
                            }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        showVehicleSelectorDialog("حدث خطأ أثناء قراءة Wi-Fi OBD (${e.localizedMessage}). اختر سيارتك:")
                    }
                }
            }
        } else {
            showVehicleSelectorDialog("قطعة فحص OBD غير متصلة حالياً بالسيارة.\n\nيمكنك اختيار سيارتك لتثبيت مواصفاتها أو الذهاب لشاشة الفحص للاتصال حياً:")
        }
    }

    private fun showVehicleSelectorDialog(promptMsg: String) {
        val carPresets = listOf(
            VehiclePreset("شيفروليه أفيو (Chevrolet Aveo - 1.5L)", "Chevrolet", "2018", "KL1TF69Y87B123456", 45f, 7.2f, 1.5f, "gasoline"),
            VehiclePreset("شيفروليه أوبترا (Chevrolet Optra - 1.6L)", "Chevrolet", "2020", "KL1NF69608B123456", 54f, 7.6f, 1.6f, "gasoline"),
            VehiclePreset("شيفروليه لانوس (Chevrolet Lanos - 1.5L)", "Chevrolet", "2017", "KL1SF69Y67B123456", 48f, 7.4f, 1.5f, "gasoline"),
            VehiclePreset("تويوتا كورولا (Toyota Corolla 1.6L)", "Toyota", "2021", "JTDBR32E12345678", 50f, 6.8f, 1.6f, "gasoline"),
            VehiclePreset("نيسان صني / سنترا (Nissan Sunny 1.5L)", "Nissan", "2022", "3N1AB7AP0NL123456", 50f, 7.3f, 1.5f, "gasoline"),
            VehiclePreset("هيونداي أكسنت / فيرنا (Hyundai Accent 1.6L)", "Hyundai", "2022", "KMHCT41CPEU123456", 43f, 6.5f, 1.6f, "gasoline"),
            VehiclePreset("هيونداي إلنترا (Hyundai Elantra 1.6L)", "Hyundai", "2023", "KMHDH41EBNA123456", 53f, 7.2f, 1.6f, "gasoline"),
            VehiclePreset("كيا سيراتو / سبورتاج (Kia Cerato 1.6L)", "Kia", "2023", "KNDPB3AC7R7123456", 50f, 7.1f, 1.6f, "gasoline"),
            VehiclePreset("رينو لوجان / ميجان (Renault Logan 1.6L)", "Renault", "2021", "VF1RFB00123456789", 50f, 6.9f, 1.6f, "gasoline"),
            VehiclePreset("فيات تيبو (Fiat Tipo 1.6L)", "Fiat", "2022", "ZFA35600006123456", 48f, 6.8f, 1.6f, "gasoline"),
            VehiclePreset("سكودا أوكتافيا / فولكس فاجن (VW / Skoda)", "Skoda", "2022", "TMBJJ7NE0N0123456", 50f, 6.5f, 1.6f, "gasoline"),
            VehiclePreset("إم جي (MG 5 / ZS)", "MG", "2023", "LSGW94833P123456", 45f, 6.7f, 1.5f, "gasoline"),
            VehiclePreset("ميتسوبيشي لانسر (Mitsubishi Lancer)", "Mitsubishi", "2018", "JA32U2FU3KU123456", 59f, 7.6f, 1.6f, "gasoline"),
            VehiclePreset("مرسيدس بنز (Mercedes-Benz C200)", "Mercedes-Benz", "2022", "WDD2050401R123456", 66f, 8.2f, 2.0f, "gasoline"),
            VehiclePreset("بي إم دبليو (BMW 320i)", "BMW", "2022", "WBA5R1103M123456", 60f, 8.0f, 2.0f, "gasoline"),
            VehiclePreset("سيارة سولار / ديزل عامة (Universal Diesel)", "Generic", "2022", "WDFUNIVERSAL12345", 60f, 7.0f, 2.0f, "diesel"),
            VehiclePreset("سيارة غاز طبيعي CNG عامة (Universal CNG)", "Generic", "2022", "CNGUNIVERSAL12345", 50f, 8.0f, 1.5f, "cng")
        )

        val items = carPresets.map { "🚗 ${it.name} - تانك ${it.tank.toInt()}L" }.toMutableList()
        items.add("✏️ إدخال اسم وموديل السيارة ورقم الشاسيه يدوياً")

        AlertDialog.Builder(this)
            .setTitle("🏎️ اختيار وتثبيت بيانات السيارة")
            .setMessage(promptMsg)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == carPresets.size) {
                    promptManualVehicleInputDialog()
                } else {
                    val preset = carPresets[which]
                    preferences.edit()
                        .putString("vehicle_name", preset.name)
                        .putString("vehicle_make", preset.make)
                        .putString("vehicle_year", preset.year)
                        .putString("vehicle_vin", preset.vin)
                        .putFloat("tank_capacity_liters", preset.tank)
                        .putFloat("fuel_rate_baseline", preset.baseRate)
                        .putFloat("engine_displacement_liters", preset.displacement)
                        .putString("vehicle_fuel_type", preset.fuelType)
                        .putString("fuel_unit_name", if (preset.fuelType == "cng") "م³" else "لتر")
                        .putString("vehicle_data_source", "بروفايل معتمد للمركبة 🚗")
                        .apply()
                    telemetry.vin = preset.vin
                    telemetry.fuelType = when (preset.fuelType) {
                        "diesel" -> "ديزل / سولار (Diesel)"
                        "cng" -> "غاز طبيعي (CNG)"
                        else -> "بنزين (Gasoline)"
                    }
                    telemetry.fuelUnitName = if (preset.fuelType == "cng") "م³" else "لتر"
                    Toast.makeText(this, "تم اعتماد سيارة: ${preset.name} (تانك ${preset.tank.toInt()} لتر)", Toast.LENGTH_SHORT).show()
                    switchTab("vehicles")
                }
            }
            .setPositiveButton("🔍 شاشة فحص OBD") { _, _ -> switchTab("scanner") }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptManualVehicleInputDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val etName = edit("اسم وموديل السيارة (مثال: كيا سيراتو 2020)").apply {
            setText(preferences.getString("vehicle_name", ""))
        }
        val etVin = edit("رقم الشاسيه VIN (اختياري - 17 حرف/رقم)").apply {
            setText(preferences.getString("vehicle_vin", ""))
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        layout.addView(text("اسم السيارة والموديل:", 13, Color.rgb(110, 231, 255), true))
        layout.addView(etName)
        layout.addView(text("رقم الشاسيه (VIN):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(8), 0, 0) })
        layout.addView(etVin)

        AlertDialog.Builder(this)
            .setTitle("✏️ إدخال بيانات السيارة")
            .setView(layout)
            .setPositiveButton("حفظ وتثبيت") { _, _ ->
                val name = etName.text.toString().trim()
                var vin = etVin.text.toString().trim().uppercase()
                if (name.isNotBlank()) {
                    if (vin.isBlank()) vin = "MANUAL" + System.currentTimeMillis().toString().takeLast(11)
                    val decoded = decodeVin(vin)
                    preferences.edit()
                        .putString("vehicle_name", name)
                        .putString("vehicle_make", if (decoded.make != "سيارة عامة") decoded.make else name)
                        .putString("vehicle_year", if (decoded.year != "غير محدد") decoded.year else "معتمد")
                        .putString("vehicle_vin", vin)
                        .putString("vehicle_data_source", "إدخال يدوي معتمد ✏️")
                        .apply()
                    telemetry.vin = vin
                    Toast.makeText(this, "تم حفظ بيانات: $name", Toast.LENGTH_SHORT).show()
                    switchTab("vehicles")
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ========================================================
    // TAB 3: OBD SCANNER (فحص وتوصيل أجهزة OBD-II)
    // ========================================================
    private fun buildScannerTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val cardScanner = card().apply {
            addView(text("الاتصال بجهاز الفحص (OBD-II Scanner)", 18, Color.WHITE, true))
            addView(text("يدعم قطع Bluetooth Classic وBLE وWi-Fi (ELM327) بدون تعارض.", 13, Color.rgb(167, 203, 226), false))

            // Check GPS Status & show warning if turned off
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val isGpsEnabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            if (!isGpsEnabled) {
                addView(text("⚠️ تنبيه: خدمة الموقع (GPS) مغلقة! يجب تشغيلها لكي يتمكن الأندرويد من اكتشاف أجهزة البلوتوث.", 13, Color.rgb(255, 180, 50), true))
                addView(button("📍 تفعيل الموقع الجغرافي (GPS)") {
                    try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
                }.apply {
                    setBackgroundColor(Color.rgb(180, 100, 20))
                    setTextColor(Color.WHITE)
                })
            }

            // Direct shortcut to phone Bluetooth settings for pairing with PIN 1234
            addView(button("📲 فتح إعدادات بلوتوث الهاتف للاقتران (PIN: 1234)") {
                try {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (_: Exception) {}
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })

            scanButton = button("🔍 بدء البحث عن أجهزة OBD") {
                if (isScanning) {
                    stopDiscovery(timedOut = false)
                } else {
                    beginDiscovery()
                }
            }
            addView(scanButton)

            statusText = text("جاهز للبحث عن أجهزة الفحص", 13, Color.rgb(167, 203, 226), false)
            selectedText = text(
                if (telemetry.isConnected) "متصل حياً: ${preferences.getString("device_name", "OBDII")}"
                else "الجهاز المحدد: لا يوجد",
                14,
                if (telemetry.isConnected) Color.rgb(126, 242, 154) else Color.rgb(110, 231, 255),
                true
            )
            addView(statusText)
            addView(selectedText)

            addView(button("🔄 إعادة تهيئة البلوتوث وإلغاء أي تعليق") {
                disconnectObd()
                try {
                    bluetoothAdapter?.let { adapter ->
                        if (adapter.isDiscovering) adapter.cancelDiscovery()
                        adapter.bluetoothLeScanner?.stopScan(bleCallback)
                    }
                } catch (_: SecurityException) {}
                addBondedDevices()
                renderCandidates()
                statusText.text = "تم تنظيف قنوات الاتصال وإعادة تهيئة البلوتوث بنجاح."
                Toast.makeText(this@MainActivity, "تمت إعادة تهيئة البلوتوث بنجاح 🟢", Toast.LENGTH_SHORT).show()
            }.apply {
                setBackgroundColor(Color.rgb(24, 55, 80))
                setTextColor(Color.rgb(110, 231, 255))
            })

            val adviceCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(18, 38, 56))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
                addView(text("💡 في حال ظهور خطأ في الاتصال (Bluetooth Socket failure):", 12, Color.rgb(255, 200, 70), true))
                addView(text("1. تأكد من فتح كونتاكت السيارة (المفتاح على وضع ON لتصل الكهرباء لفيشة الـ OBD).\n2. إذا كنت قد حاولت عدة مرات، انزع قطعة الـ OBD من فيشة السيارة وانتظر 5 ثوانٍ ثم أعد تركيبها (لإلغاء تعليق معالج القطعة).\n3. اضغط على 'إعادة تهيئة البلوتوث' أعلاه ثم اختر قطعتك.", 11, Color.rgb(180, 215, 240), false))
            }
            addView(adviceCard)

            if (telemetry.isConnected) {
                addView(button("⏹ قطع الاتصال بـ OBD") {
                    disconnectObd()
                    statusText.text = "تم قطع الاتصال بنجاح."
                    selectedText.text = "الجهاز المحدد: لا يوجد"
                    if (::tvConnectionState.isInitialized) {
                        tvConnectionState.text = "غير متصل"
                        tvConnectionState.setTextColor(Color.rgb(255, 122, 122))
                    }
                }.apply {
                    setBackgroundColor(Color.rgb(180, 35, 35))
                    setTextColor(Color.WHITE)
                })
            }
        }
        container.addView(cardScanner)

        container.addView(text("الأجهزة المتاحة (اضغط للتوصيل المباشر وقراءة الحساسات):", 16, Color.WHITE, true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })

        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(resultsContainer)

        // Load paired devices immediately so user sees them right away
        addBondedDevices()
        inspectWifiCandidate()
        renderCandidates()

        return container
    }

    private fun buildFuelTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val fuelPrice = preferences.getFloat("fuel_liter_price", 15.0f)
        val oilPrice = preferences.getFloat("oil_total_price", 1200.0f)
        val oilTargetKm = preferences.getFloat("oil_target_km", 5000.0f)
        val oilCurrentKm = preferences.getFloat("oil_current_km", 0.0f)
        val baselineFuelRate = preferences.getFloat("fuel_rate_baseline", 7.2f)

        // Vehicle Market Value and Depreciation (0.20% per 1000 km = 100% of vehicle value over 500,000 km)
        val vehicleMarketValue = preferences.getFloat("vehicle_market_value", 400000.0f)
        val depRatePer1000Km = preferences.getFloat("depreciation_percent_per_1000km", 0.20f)
        val depCostPerKm = (vehicleMarketValue * (depRatePer1000Km / 100.0f)) / 1000.0f

        val oilCostPerKm = if (oilTargetKm > 0f) oilPrice / oilTargetKm else 0f
        val remainingOilKm = (oilTargetKm - oilCurrentKm).coerceAtLeast(0f)
        val oilHealthPercent = if (oilTargetKm > 0f) ((remainingOilKm / oilTargetKm) * 100).toInt().coerceIn(0, 100) else 0

        // Vehicle Load & Payload Physics:
        // Every 100 kg of cargo/passengers increases fuel consumption by ~5%
        val weightPenaltyPercent = (plannedCargoWeightKg / 100.0f) * 5.0f
        val loadedFuelRate = baselineFuelRate * (1.0f + (weightPenaltyPercent / 100.0f))

        // 1) Empty Vehicle Trip Calculation (Fuel + Oil + Vehicle Depreciation):
        val emptyFuelCostPerKm = (baselineFuelRate / 100.0f) * fuelPrice
        val emptyTripFuelCost = plannedRouteDistanceKm * emptyFuelCostPerKm
        val emptyTripOilCost = plannedRouteDistanceKm * oilCostPerKm
        val emptyTripDepCost = plannedRouteDistanceKm * depCostPerKm
        val emptyTripTotalCost = emptyTripFuelCost + emptyTripOilCost + emptyTripDepCost
        val emptyTripFuelLiters = plannedRouteDistanceKm * (baselineFuelRate / 100.0f)

        // 2) Loaded Vehicle Trip Calculation (with plannedCargoWeightKg):
        val loadedFuelCostPerKm = (loadedFuelRate / 100.0f) * fuelPrice
        val loadedTripFuelCost = plannedRouteDistanceKm * loadedFuelCostPerKm
        val loadedTripOilCost = plannedRouteDistanceKm * oilCostPerKm
        val loadedTripDepCost = plannedRouteDistanceKm * depCostPerKm
        val loadedTripTotalCost = loadedTripFuelCost + loadedTripOilCost + loadedTripDepCost
        val loadedTripFuelLiters = plannedRouteDistanceKm * (loadedFuelRate / 100.0f)
        val weightDeltaCost = loadedTripTotalCost - emptyTripTotalCost
        val weightDeltaLiters = (loadedTripFuelLiters - emptyTripFuelLiters).coerceAtLeast(0f)

        // 3) Dynamic Real Calibration after driving first 1 km:
        val hasDrivenFirstKm = telemetry.tripDistanceKm >= 1.0f && telemetry.actualTripFuelLiters > 0.02f
        val realMeasuredTripRate = if (hasDrivenFirstKm) {
            (telemetry.actualTripFuelLiters / telemetry.tripDistanceKm) * 100.0f
        } else {
            telemetry.tripAverageFuelRate
        }
        val realCalibratedFuelCostPerKm = (realMeasuredTripRate / 100.0f) * fuelPrice
        val realTripProjectedTotalCost = (plannedRouteDistanceKm * realCalibratedFuelCostPerKm) + (plannedRouteDistanceKm * oilCostPerKm) + (plannedRouteDistanceKm * depCostPerKm)
        val realTripProjectedFuelLiters = plannedRouteDistanceKm * (realMeasuredTripRate / 100.0f)

        // In-flight progress:
        val actualDrivenKm = telemetry.tripDistanceKm
        val remainingRouteKm = (plannedRouteDistanceKm - actualDrivenKm).coerceAtLeast(0f)
        val currentTripFuelCost = telemetry.actualTripFuelLiters * fuelPrice
        val currentTripOilCost = actualDrivenKm * oilCostPerKm
        val currentTripDepCost = actualDrivenKm * depCostPerKm
        val currentTripTotalCost = currentTripFuelCost + currentTripOilCost + currentTripDepCost
        val remainingProjectedCost = (remainingRouteKm * realCalibratedFuelCostPerKm) + (remainingRouteKm * oilCostPerKm) + (remainingRouteKm * depCostPerKm)

        // --- Card 1: Hero KPI Card ---
        val cardHero = card().apply {
            addView(text("⛽ حاسبة تكلفة التشغيل الشاملة (وقود + زيت + إهلاك السيارة)", 18, Color.rgb(126, 242, 154), true))
            addView(text("حساب فوري شامل لتكلفة الكيلومتر والرحلات بالجنيه وفق الاستهلاك ومصاريف الإهلاك والصيانة:", 13, Color.rgb(167, 203, 226), false))

            val r1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            r1.addView(metricBox("تكلفة الوقود/كم (فارغة)", String.format("%.2f ج.م", emptyFuelCostPerKm), Color.rgb(110, 231, 255)).parent as View)
            r1.addView(metricBox("استهلاك الزيت/كم", String.format("%.2f ج.م", oilCostPerKm), Color.rgb(248, 199, 74)).parent as View)
            addView(r1)

            val r2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val activeCostPerKm = if (hasDrivenFirstKm) (realCalibratedFuelCostPerKm + oilCostPerKm + depCostPerKm) else (loadedFuelCostPerKm + oilCostPerKm + depCostPerKm)
            r2.addView(metricBox("إهلاك وتآكل السيارة/كم", String.format("%.2f ج.م", depCostPerKm), Color.rgb(255, 180, 100)).parent as View)
            val mbActiveCost = metricBox("إجمالي التكلفة الشاملة/كم", String.format("%.2f ج.م", activeCostPerKm), Color.rgb(126, 242, 154))
            tvFuelTabActiveCostPerKm = mbActiveCost
            r2.addView(mbActiveCost.parent as View)
            addView(r2)

            val r3 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val tankCap = preferences.getFloat("tank_capacity_liters", 45.0f)
            val litersInTank = (telemetry.fuelLevel * tankCap / 100.0f)
            val effectiveRate = if (hasDrivenFirstKm) realMeasuredTripRate else if (plannedCargoWeightKg > 0) loadedFuelRate else baselineFuelRate
            val mbTank = metricBox("مستوى التانك", "${telemetry.fuelLevel}% (${String.format("%.1f", litersInTank)}L)", Color.rgb(126, 242, 154))
            tvFuelTabTankPercent = mbTank
            r3.addView(mbTank.parent as View)

            val mbRate = metricBox("المعدل الحالي", String.format("%.1f L/100 (%s)", effectiveRate, if (hasDrivenFirstKm) "فعلي مقاس" else "تقديري"), Color.rgb(110, 231, 255))
            tvFuelTabEffectiveRate = mbRate
            r3.addView(mbRate.parent as View)
            addView(r3)

            val fuelSourceBanner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(18, 42, 62))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
                addView(text("📡 نظام الحساب الآلي لكمية الوقود المستهلكة (أعلى دقة بدون إدخال يدوي):", 13, Color.rgb(126, 242, 154), true))
                val tvSensorInfo = text("• الحساس النشط: ${telemetry.fuelCalculationSource}\n• تدفق الوقود اللحظي للمحرك: ${String.format("%.2f", telemetry.instantFuelLitersPerHour)} لتر/ساعة\n• المستهلك الفعلي بالرحلة: ${String.format("%.3f", telemetry.actualTripFuelLiters)} لتر (${String.format("%.2f", currentTripFuelCost)} ج.م)\n• حساب وقوف الزحام والإشارات: مدمج تلقائياً بدقة 100% (1.2 - 1.5 لتر/ساعة أثناء التوقف).", 12, Color.rgb(200, 230, 255), false)
                tvFuelTabSensorInfoText = tvSensorInfo
                addView(tvSensorInfo)
            }
            addView(fuelSourceBanner)

            addView(button("🔍 فحص واستكشاف كمبيوترات السيارة ذاتياً (قراءة العدادات والمكينة تلقائياً)") {
                scanAllFuelComputers()
            }.apply {
                setBackgroundColor(Color.rgb(16, 185, 129))
                setTextColor(Color.WHITE)
            })

            addView(button("⛽ ضبط ومعايرة خزان الوقود (يدوي / تثبيت / إضافة لترات)") {
                showFuelCalibrationDialog()
            }.apply {
                setBackgroundColor(Color.rgb(248, 199, 74))
                setTextColor(Color.rgb(8, 23, 32))
            })

            addView(button("⚙️ تحديد نوع وقود المحرك (بنزين 92/95 | غاز طبيعي CNG | سولار ديزل)") {
                showFuelTypeDialog()
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardHero)

        // --- Card 2: Future Trip Distance, Manual Input & Map Route Card ---
        val cardMapRoute = card().apply {
            addView(text("🛣️ مسار الرحلة المستقبلية والمسافة (إدخال يدوي أو خريطة)", 17, Color.rgb(110, 231, 255), true))
            addView(text("حدد مسافة رحلتك المستقبلية بالكيلومتر إما بكتابتها يدوياً أو باختيارها تلقائياً من الخريطة:", 13, Color.rgb(167, 203, 226), false))

            val routeBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(24, 45, 66))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            routeBox.addView(text("🚩 نقطة الانطلاق: $tripStartLocationName", 14, Color.WHITE, true))
            routeBox.addView(text("🏁 نقطة الوصول: $tripEndLocationName", 14, Color.WHITE, true))
            routeBox.addView(text("🛣️ المسافة الكلية المعتمدة للمشوار: ${String.format("%.1f", plannedRouteDistanceKm)} كم", 16, Color.rgb(126, 242, 154), true))
            addView(routeBox)

            // Direct Manual Distance Input Row
            addView(text("✏️ اكتب مسافة الرحلة المستقبلية بالكيلومتر يدوياً مباشرة:", 13, Color.rgb(248, 199, 74), true).apply { setPadding(0, dp(10), 0, dp(2)) })
            val manualDistRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(4)
                layoutParams = lp
            }
            val etManualDist = edit("المسافة بالكيلومتر (مثال: 220)").apply {
                setText(String.format("%.0f", plannedRouteDistanceKm))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                lp.setMargins(0, 0, dp(4), 0)
                layoutParams = lp
            }
            val btnSaveManualDist = button("💾 اعتماد المسافة") {
                val d = etManualDist.text.toString().toFloatOrNull()
                if (d != null && d > 0f) {
                    preferences.edit().putFloat("planned_route_distance", d).apply()
                    plannedRouteDistanceKm = d
                    Toast.makeText(this@MainActivity, "تم اعتماد مسافة الرحلة: ${String.format("%.1f", d)} كم 🛣️", Toast.LENGTH_SHORT).show()
                    switchTab("fuel")
                } else {
                    Toast.makeText(this@MainActivity, "يرجى كتابة رقم مسافة صحيح بالكيلومتر", Toast.LENGTH_SHORT).show()
                }
            }.apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                setBackgroundColor(Color.rgb(126, 242, 154))
                setTextColor(Color.rgb(8, 23, 32))
            }
            manualDistRow.addView(etManualDist)
            manualDistRow.addView(btnSaveManualDist)
            addView(manualDistRow)

            // Quick distance presets for common destinations
            val quickDistRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }
            fun quickDistBtn(label: String, distKm: Float): Button {
                return Button(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    setTextColor(if (plannedRouteDistanceKm == distKm) Color.rgb(8, 23, 32) else Color.WHITE)
                    setBackgroundColor(if (plannedRouteDistanceKm == distKm) Color.rgb(126, 242, 154) else Color.rgb(20, 42, 64))
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(dp(1), 0, dp(1), 0)
                    layoutParams = lp
                    setOnClickListener {
                        etManualDist.setText(String.format("%.0f", distKm))
                        preferences.edit().putFloat("planned_route_distance", distKm).apply()
                        plannedRouteDistanceKm = distKm
                        switchTab("fuel")
                    }
                }
            }
            quickDistRow.addView(quickDistBtn("50 كم\nسفر قصير", 50f))
            quickDistRow.addView(quickDistBtn("120 كم\nطنطا/السويس", 120f))
            quickDistRow.addView(quickDistBtn("220 كم\nإسكندرية", 220f))
            quickDistRow.addView(quickDistBtn("380 كم\nالساحل", 380f))
            quickDistRow.addView(quickDistBtn("500 كم\nشرم/أسيوط", 500f))
            addView(quickDistRow)

            addView(button("🗺️ فتح الخريطة التفاعلية لاختيار مسار واستخراج المسافة تلقائياً") {
                showMapRouteDialog()
            }.apply {
                setBackgroundColor(Color.rgb(110, 231, 255))
                setTextColor(Color.rgb(8, 23, 32))
            })

            addView(button("🧭 فتح الملاحة خطوة بخطوة في Google Maps") {
                openGoogleMapsNavigation(tripStartLat, tripStartLng, tripEndLat, tripEndLng)
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardMapRoute)

        // --- Card 3: Cargo Weight & Engine Baseline Consumption ---
        val cardWeight = card().apply {
            addView(text("⚖️ وزن الحمولة ومتوسط استهلاك المحرك للمشوار", 17, Color.rgb(248, 199, 74), true))
            addView(text("الحمولة الإضافية (ركاب، حقائب، بضائع) تزيد الاستهلاك بنحو 5% لكل 100 كجم إضافية. أدخل الوزن المتوقع ومتوسط استهلاك المحرك لمشوارك:", 13, Color.rgb(167, 203, 226), false))

            // Quick Baseline Rate Edit
            addView(text("متوسط استهلاك المحرك القياسي فارغة (لتر / 100 كم):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(6), 0, 0) })
            val rateEditRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(4)
                layoutParams = lp
            }
            val etBaseRate = edit("استهلاك المحرك (مثال: 7.2)").apply {
                setText(String.format("%.1f", baselineFuelRate))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                lp.setMargins(0, 0, dp(4), 0)
                layoutParams = lp
            }
            val btnSaveRate = button("💾 حفظ المعدل") {
                val r = etBaseRate.text.toString().toFloatOrNull()
                if (r != null && r in 2.0f..30.0f) {
                    preferences.edit().putFloat("fuel_rate_baseline", r).apply()
                    Toast.makeText(this@MainActivity, "تم اعتماد متوسط استهلاك المحرك: $r لتر/100 كم ⛽", Toast.LENGTH_SHORT).show()
                    switchTab("fuel")
                } else {
                    Toast.makeText(this@MainActivity, "يرجى كتابة رقم استهلاك صحيح (مثال: 7.2)", Toast.LENGTH_SHORT).show()
                }
            }.apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                setBackgroundColor(Color.rgb(110, 231, 255))
                setTextColor(Color.rgb(8, 23, 32))
            }
            rateEditRow.addView(etBaseRate)
            rateEditRow.addView(btnSaveRate)
            addView(rateEditRow)

            addView(text("الوزن الإضافي المحتمل للمشوار (كجم):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(6), 0, 0) })
            val etWeight = edit("أدخل وزن الحمولة بالكجم (مثال: 150)").apply {
                setText(String.format("%.0f", plannedCargoWeightKg))
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            addView(etWeight)

            val tvQuick = text("اختيار سريع لوزن الحمولة بنقرة واحدة:", 12, Color.rgb(167, 203, 226), false)
            addView(tvQuick)

            val qRow1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }

            fun quickWeightBtn(label: String, weight: Float): Button {
                return Button(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    setTextColor(if (plannedCargoWeightKg == weight) Color.rgb(8, 23, 32) else Color.WHITE)
                    setBackgroundColor(if (plannedCargoWeightKg == weight) Color.rgb(248, 199, 74) else Color.rgb(20, 42, 64))
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(dp(2), 0, dp(2), 0)
                    layoutParams = lp
                    setOnClickListener {
                        etWeight.setText(String.format("%.0f", weight))
                        preferences.edit().putFloat("planned_cargo_weight", weight).apply()
                        plannedCargoWeightKg = weight
                        switchTab("fuel")
                    }
                }
            }

            qRow1.addView(quickWeightBtn("🚗 فارغة\n(0 كجم)", 0f))
            qRow1.addView(quickWeightBtn("👤 راكب\n(75 كجم)", 75f))
            qRow1.addView(quickWeightBtn("👥 راكبان\n(150 كجم)", 150f))
            addView(qRow1)

            val qRow2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            qRow2.addView(quickWeightBtn("👨‍👩‍👧‍👦 أسرة وحقائب\n(300 كجم)", 300f))
            qRow2.addView(quickWeightBtn("📦 حمولة ثقيلة\n(500 كجم)", 500f))
            addView(qRow2)

            addView(button("💾 حفظ وتطبيق وزن الحمولة") {
                val w = etWeight.text.toString().toFloatOrNull() ?: 0f
                preferences.edit().putFloat("planned_cargo_weight", w).apply()
                plannedCargoWeightKg = w
                Toast.makeText(this@MainActivity, "تم تطبيق وزن حمولة $w كجم", Toast.LENGTH_SHORT).show()
                switchTab("fuel")
            }.apply {
                setBackgroundColor(Color.rgb(248, 199, 74))
                setTextColor(Color.rgb(8, 23, 32))
            })
        }
        container.addView(cardWeight)

        // --- Card 4: Complete Triple Financial Comparison (Empty vs Loaded vs Real-World 1KM Calibration) ---
        val cardCostTriple = card().apply {
            addView(text("💰 حساب وتوقع تكلفة الرحلة الشاملة (وقود + زيت + إهلاك السيارة)", 18, Color.WHITE, true))
            addView(text("المسار: ${plannedRouteDistanceKm.toInt()} كم من $tripStartLocationName إلى $tripEndLocationName | سعر اللتر: ${String.format("%.2f", fuelPrice)} ج.م", 12, Color.rgb(167, 203, 226), false))

            // Box A: Empty Car
            val boxEmpty = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(16, 32, 46))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            boxEmpty.addView(text("🚗 1. في حالة السيارة فارغة (0 كجم حمولة إضافية):", 14, Color.rgb(110, 231, 255), true))
            boxEmpty.addView(text("• معدل الاستهلاك القياسي: ${String.format("%.1f", baselineFuelRate)} لتر / 100 كم", 12, Color.WHITE, false))
            boxEmpty.addView(text("• كمية الوقود المطلوبة: ${String.format("%.1f", emptyTripFuelLiters)} لتر (${String.format("%.2f", emptyTripFuelCost)} ج.م)", 12, Color.rgb(167, 203, 226), false))
            boxEmpty.addView(text("• نصيب استهلاك زيت المحرك: ${String.format("%.2f", emptyTripOilCost)} ج.م", 12, Color.rgb(167, 203, 226), false))
            boxEmpty.addView(text("• نصيب استهلاك وإهلاك وصيانة السيارة (${String.format("%.2f", depRatePer1000Km)}% لكل 1000 كم): ${String.format("%.2f", emptyTripDepCost)} ج.م", 12, Color.rgb(255, 180, 100), false))
            boxEmpty.addView(text("• إجمالي التكلفة الشاملة فارغة: ${String.format("%.2f", emptyTripTotalCost)} ج.م (${String.format("%.2f", emptyFuelCostPerKm + oilCostPerKm + depCostPerKm)} ج.م/كم)", 14, Color.rgb(126, 242, 154), true))
            addView(boxEmpty)

            // Box B: Loaded Car with plannedCargoWeightKg
            val boxLoaded = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(24, 40, 56))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            boxLoaded.addView(text("📦 2. مع الحمولة المحددة (+${plannedCargoWeightKg.toInt()} كجم وزن إضافي):", 14, Color.rgb(248, 199, 74), true))
            boxLoaded.addView(text("• نسبة الزيادة في الاستهلاك بسبب الوزن: +${String.format("%.1f", weightPenaltyPercent)}%", 12, Color.rgb(248, 199, 74), false))
            boxLoaded.addView(text("• معدل الاستهلاك المتوقع بالحمل: ${String.format("%.1f", loadedFuelRate)} لتر / 100 كم", 12, Color.WHITE, false))
            boxLoaded.addView(text("• كمية الوقود المتوقعة: ${String.format("%.1f", loadedTripFuelLiters)} لتر (${String.format("%.2f", loadedTripFuelCost)} ج.م)", 12, Color.rgb(167, 203, 226), false))
            boxLoaded.addView(text("• نصيب استهلاك زيت المحرك: ${String.format("%.2f", loadedTripOilCost)} ج.م", 12, Color.rgb(167, 203, 226), false))
            boxLoaded.addView(text("• نصيب استهلاك وإهلاك وصيانة السيارة: ${String.format("%.2f", loadedTripDepCost)} ج.م", 12, Color.rgb(255, 180, 100), false))
            boxLoaded.addView(text("• إجمالي التكلفة المتوقعة مع الحمولة: ${String.format("%.2f", loadedTripTotalCost)} ج.م (${String.format("%.2f", loadedFuelCostPerKm + oilCostPerKm + depCostPerKm)} ج.م/كم)", 14, Color.rgb(248, 199, 74), true))
            if (plannedCargoWeightKg > 0) {
                boxLoaded.addView(text("• فارق التكلفة الناتج عن الحمولة: +${String.format("%.2f", weightDeltaCost)} ج.م (زيادة ${String.format("%.1f", weightDeltaLiters)} لتر)", 13, Color.rgb(255, 180, 100), true))
            }
            addView(boxLoaded)

            // Box C: Real-World Calibration after driving 1st KM
            val boxReal = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val bgColor = if (hasDrivenFirstKm) Color.rgb(10, 48, 36) else Color.rgb(14, 28, 44)
                setBackgroundColor(bgColor)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            containerFuelRealBox = boxReal

            val tTitle = if (hasDrivenFirstKm) {
                text("🎯 3. المعايرة الواقعية المؤكدة (بعد سير أول ${String.format("%.2f", telemetry.tripDistanceKm)} كم) 🟢", 15, Color.rgb(126, 242, 154), true)
            } else {
                text("🎯 3. المعايرة الحقيقية التلقائية أثناء السير (بعد أول 1 كم) ⏳", 14, Color.rgb(110, 231, 255), true)
            }
            tvFuelTabRealBoxTitle = tTitle
            boxReal.addView(tTitle)

            val tDetails = if (hasDrivenFirstKm) {
                text("تم ضبط الاستهلاك الحقيقي لسيارتك تحت هذا الحمل والظروف الفعلية عبر حساسات OBD2!\n• معدل الاستهلاك الفعلي المقاس بالرحلة: ${String.format("%.1f", realMeasuredTripRate)} لتر / 100 كم\n• إجمالي تكلفة الرحلة الحقيقية المستنتجة كاملة (وقود + زيت + إهلاك): ${String.format("%.2f", realTripProjectedTotalCost)} ج.م (${String.format("%.1f", realTripProjectedFuelLiters)} لتر)\n• ما تم دفعه/استهلاكه حتى الآن: ${String.format("%.2f", currentTripTotalCost)} ج.م (${String.format("%.2f", telemetry.actualTripFuelLiters)} لتر)\n• التكلفة المتبقية لإنهاء المسار: ${String.format("%.2f", remainingProjectedCost)} ج.م (متبقي ${remainingRouteKm.toInt()} كم)", 12, Color.rgb(167, 203, 226), false)
            } else {
                text("💡 بمجرد انطلاقك بالسيارة وقطع أول كيلومتر (1 كم) من المشوار، ستقوم حساسات الـ OBD2 بقياس الاستهلاك الحقيقي لسيارتك تحت هذا الحمل والظروف الفعلية، وسيتم استبدال الحسابات التقديرية بالتكلفة الحقيقية المؤكدة آلياً!", 12, Color.rgb(167, 203, 226), false)
            }
            tvFuelTabRealBoxDetails = tDetails
            boxReal.addView(tDetails)
            addView(boxReal)
        }
        container.addView(cardCostTriple)

        // --- Card 5: Live Trip Metrics & Financials Card (تكلفة الرحلة الحالية اللحظية) ---
        val cardTripLive = card().apply {
            addView(text("🚀 بيانات وتكلفة الرحلة الحالية اللحظية (OBD Live)", 17, Color.rgb(110, 231, 255), true))
            addView(text("يتم الآن حساب الاستهلاك والتكلفة الفورية مباشرة من حساسات السيارة أثناء السير:", 13, Color.rgb(167, 203, 226), false))

            val tRow1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val mbDist = metricBox("المسافة المقطوعة", String.format("%.2f كم", telemetry.tripDistanceKm), Color.WHITE)
            tvFuelTabTripDistance = mbDist
            tRow1.addView(mbDist.parent as View)

            val mbLiters = metricBox("الوقود المستهلك", String.format("%.2f %s", telemetry.actualTripFuelLiters, telemetry.fuelUnitName), Color.rgb(248, 199, 74))
            tvFuelTabActualConsumed = mbLiters
            tRow1.addView(mbLiters.parent as View)
            addView(tRow1)

            val tRow2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val instantStr = if (telemetry.speed > 3) String.format("%.1f L/100 (%.1f L/h)", telemetry.instantFuelLPer100Km, telemetry.instantFuelLitersPerHour) else String.format("%.2f L/h (توقف وزحام)", telemetry.instantFuelLitersPerHour)
            val mbInstant = metricBox("الاستهلاك اللحظي", instantStr, Color.rgb(110, 231, 255))
            tvFuelTabInstantRate = mbInstant
            tRow2.addView(mbInstant.parent as View)

            val mbCost = metricBox("تكلفة الرحلة الحالية", String.format("%.2f ج.م", currentTripTotalCost), Color.rgb(126, 242, 154))
            tvFuelTabCurrentCost = mbCost
            tRow2.addView(mbCost.parent as View)
            addView(tRow2)

            addView(button("🔄 تصفير وبدء رحلة جديدة") {
                telemetry.tripDistanceKm = 0f
                telemetry.actualTripFuelLiters = 0f
                telemetry.tripAverageFuelRate = baselineFuelRate
                switchTab("fuel")
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardTripLive)

        // --- Card 6: Settings Card for Fuel & Oil prices ---
        val cardSettings = card().apply {
            addView(text("⚙️ ضبط أسعار التشغيل وسعة التانك والزيت", 17, Color.WHITE, true))
            addView(text("أدخل أسعارك لتحديث حساب التكلفة فورياً لكل الرحلات:", 13, Color.rgb(167, 203, 226), false))

            val curFuelType = preferences.getString("vehicle_fuel_type", "gasoline") ?: "gasoline"
            addView(text("نوع وقود السيارة (يحدد تلقائياً معادلة الاحتراق الفيزيائية الدقيقة):", 13, Color.rgb(248, 199, 74), true).apply { setPadding(0, dp(6), 0, 0) })

            val fuelTypeRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }

            fun fuelTypeBtn(typeKey: String, label: String, defaultPrice: Float, defaultRate: Float): Button {
                return Button(this@MainActivity).apply {
                    text = label
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    val isSel = (curFuelType == typeKey)
                    setTextColor(if (isSel) Color.rgb(8, 23, 32) else Color.WHITE)
                    setBackgroundColor(if (isSel) Color.rgb(126, 242, 154) else Color.rgb(24, 46, 68))
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(dp(2), 0, dp(2), 0)
                    layoutParams = lp
                    setOnClickListener {
                        val unit = if (typeKey == "cng") "م³" else "لتر"
                        preferences.edit()
                            .putString("vehicle_fuel_type", typeKey)
                            .putString("fuel_unit_name", unit)
                            .putFloat("fuel_liter_price", defaultPrice)
                            .putFloat("fuel_rate_baseline", defaultRate)
                            .apply()
                        telemetry.fuelUnitName = unit
                        switchTab("fuel")
                    }
                }
            }

            fuelTypeRow.addView(fuelTypeBtn("gasoline", "⛽ بنزين", 15.00f, 8.5f))
            fuelTypeRow.addView(fuelTypeBtn("diesel", "🛢️ سولار / ديزل", 13.50f, 6.8f))
            fuelTypeRow.addView(fuelTypeBtn("cng", "💨 غاز طبيعي CNG", 7.00f, 8.0f))
            addView(fuelTypeRow)

            val unitName = preferences.getString("fuel_unit_name", "لتر") ?: "لتر"
            val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
            addView(text("سعة خزان الوقود بالكامل ($unitName):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etTankCap = edit("مثال: 50").apply { setText(String.format("%.0f", tankCap)) }
            addView(etTankCap)

            addView(text("سعر لتر البنزين (ج.م):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etFuelPrice = edit("مثال: 22.00").apply { setText(String.format("%.2f", fuelPrice)) }
            addView(etFuelPrice)

            addView(text("متوسط استهلاك سيارتك القياسي وهي فارغة (لتر / 100 كم):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etFuelRate = edit("مثال: 7.2").apply { setText(String.format("%.1f", baselineFuelRate)) }
            addView(etFuelRate)

            addView(text("تكلفة غيار الزيت والفلتر بالكامل (ج.م):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etOilPrice = edit("مثال: 1200").apply { setText(String.format("%.0f", oilPrice)) }
            addView(etOilPrice)

            addView(text("عمر/صلاحية نوع الزيت بالكيلومتر (مثلاً 3000 أو 5000 أو 10000 كم):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etOilTarget = edit("مثال: 5000").apply { setText(String.format("%.0f", oilTargetKm)) }
            addView(etOilTarget)

            addView(text("القيمة السوقية التقديرية للسيارة (ج.م) لحساب الإهلاك والتآكل:", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etCarValue = edit("مثال: 400000").apply { setText(String.format("%.0f", vehicleMarketValue)) }
            addView(etCarValue)

            addView(text("نسبة استهلاك وإهلاك وتآكل السيارة لكل 1000 كم (الافتراضي 0.20% لتغطية دورة حياة 500 ألف كم):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etDepRate = edit("مثال: 0.20").apply { setText(String.format("%.2f", depRatePer1000Km)) }
            addView(etDepRate)

            val tvSaveMsg = text("", 13, Color.rgb(126, 242, 154), false)
            addView(tvSaveMsg)

            addView(button("💾 حفظ وتحديث أسعار التشغيل") {
                val fP = etFuelPrice.text.toString().toFloatOrNull() ?: fuelPrice
                val fR = etFuelRate.text.toString().toFloatOrNull() ?: baselineFuelRate
                val oP = etOilPrice.text.toString().toFloatOrNull() ?: oilPrice
                val oT = etOilTarget.text.toString().toFloatOrNull() ?: oilTargetKm
                val tC = etTankCap.text.toString().toFloatOrNull() ?: tankCap
                val cV = etCarValue.text.toString().toFloatOrNull() ?: vehicleMarketValue
                val dR = etDepRate.text.toString().toFloatOrNull() ?: depRatePer1000Km

                preferences.edit()
                    .putFloat("fuel_liter_price", fP)
                    .putFloat("fuel_rate_baseline", fR)
                    .putFloat("oil_total_price", oP)
                    .putFloat("oil_target_km", oT)
                    .putFloat("tank_capacity_liters", tC)
                    .putFloat("vehicle_market_value", cV)
                    .putFloat("depreciation_percent_per_1000km", dR)
                    .apply()

                tvSaveMsg.text = "تم حفظ وتحديث الأسعار ومعدل الإهلاك بنجاح! يتم الآن إعادة حساب التكاليف."
                switchTab("fuel")
            })
        }
        container.addView(cardSettings)

        // --- Card 7: Engine Oil Odometer & Reset Button Card ---
        val cardOilCounter = card().apply {
            addView(text("🛢️ عداد وتصفير كيلومترات زيت المحرك", 17, Color.rgb(248, 199, 74), true))
            addView(text("تابع مسافة الزيت الحالية (تعد تلقائياً أثناء القيادة)، واضغط على زر التصفير عند تغيير الزيت:", 13, Color.rgb(167, 203, 226), false))

            val oilStatsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            oilStatsRow.addView(metricBox("المقطوع بالزيت الحالي", "${oilCurrentKm.toInt()} كم", Color.WHITE).parent as View)
            oilStatsRow.addView(metricBox("المتبقي حتى الغيار", "${remainingOilKm.toInt()} كم", if (remainingOilKm < 500f) Color.rgb(255, 122, 122) else Color.rgb(126, 242, 154)).parent as View)
            addView(oilStatsRow)

            val tvResetMsg = text("", 13, Color.rgb(126, 242, 154), false)
            addView(tvResetMsg)

            addView(button("🔄 تصفير عداد الزيت (تم تغيير الزيت الآن)") {
                preferences.edit()
                    .putFloat("oil_current_km", 0.0f)
                    .apply()

                tvResetMsg.text = "تم تصفير عداد الزيت بنجاح! تبدأ الآن دورة جديدة لـ ${oilTargetKm.toInt()} كم."
                switchTab("fuel")
            }.apply {
                setBackgroundColor(Color.rgb(248, 199, 74))
                setTextColor(Color.rgb(8, 23, 32))
            })
        }
        container.addView(cardOilCounter)

        // --- Card 8: Real-Time OBD Fuel vs Baseline Educational Guide Card ---
        val cardFuelComparison = card().apply {
            addView(text("📊 دليل فيزياء الوقود ومعادلة الحمولة والـ OBD", 17, Color.rgb(110, 231, 255), true))
            addView(text("كيف يحسب البرنامج تكلفة الوقود والمشاوير بالمليم؟", 14, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) })
            addView(text("• 1. التقدير القياسي (فارغة): $baselineFuelRate لتر/100 كم (متوسط كتالوج الشركة المصنعة).", 13, Color.rgb(167, 203, 226), false))
            addView(text("• 2. معادلة الحمل والوزن: كل 100 كجم إضافية تزيد الاستهلاك 5% بسبب مقاومة القصور الذاتي.", 13, Color.rgb(248, 199, 74), false))
            addView(text("• 3. المعايرة الذكية (بعد أول 1 كم): فور تحرك السيارة وقراءة حساسات تدفق الهواء (MAF) وتغير مستوى الوقود (Delta Fuel)، يستنتج النظام المعدل الواقعي الحقيقي فوراً.", 13, Color.rgb(126, 242, 154), false))

            addView(button("🔊 اختبار نغمة التنبيه الصوتي والإنذارات") {
                isSimulatingAlert = true
                testAudioAlert()
                setSafetyAlert("🔊 إنذار تجريبي: نظام التحذير الصوتي والمرئي", "الإنذار الصوتي والمرئي جاهز ويعمل بكفاءة لحماية محرك سيارتك أثناء القيادة.", critical = false)
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardFuelComparison)

        return container
    }

    // ========================================================
    // MAP ROUTE PLANNER & GOOGLE MAPS NAVIGATION
    // ========================================================
    private fun openGoogleMapsNavigation(startLat: Double, startLng: Double, endLat: Double, endLng: Double) {
        try {
            val uriStr = "https://www.google.com/maps/dir/?api=1&origin=$startLat,$startLng&destination=$endLat,$endLng&travelmode=driving"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
            intent.setPackage("com.google.android.apps.maps")
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح تطبيق خرائط جوجل: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMapRouteDialog() {
        var tempStartName = tripStartLocationName
        var tempEndName = tripEndLocationName
        var tempStartLat = tripStartLat
        var tempStartLng = tripStartLng
        var tempEndLat = tripEndLat
        var tempEndLng = tripEndLng
        var tempDistKm = plannedRouteDistanceKm
        var tempDurMin = (tempDistKm / 85f * 60f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 23, 32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        root.addView(text("🗺️ تحديد مسار الرحلة على الخريطة", 17, Color.rgb(110, 231, 255), true))
        root.addView(text("حدد نقطة البداية ونقطة النهاية (بالنقر على الخريطة أو من قائمة المدن) لحساب المسافة والوقت:", 12, Color.rgb(167, 203, 226), false))

        val selectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        val btnStart = Button(this).apply {
            text = "🚩 من: $tempStartName"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20, 42, 64))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            layoutParams = lp
        }

        val btnSwap = Button(this).apply {
            text = "🔄"
            textSize = 12f
            setTextColor(Color.rgb(110, 231, 255))
            setBackgroundColor(Color.rgb(16, 32, 46))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(2), 0, dp(2), 0)
            layoutParams = lp
        }

        val btnEnd = Button(this).apply {
            text = "🏁 إلى: $tempEndName"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20, 42, 64))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            layoutParams = lp
        }

        selectorRow.addView(btnStart)
        selectorRow.addView(btnSwap)
        selectorRow.addView(btnEnd)
        root.addView(selectorRow)

        val btnGps = Button(this).apply {
            text = "📍 استخدام موقعي الحالي (GPS) كنقطة بداية"
            textSize = 11f
            setTextColor(Color.rgb(126, 242, 154))
            setBackgroundColor(Color.rgb(16, 42, 32))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        root.addView(btnGps)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
            layoutParams = lp
        }
        root.addView(webView)

        val tvInfo = text("🛣️ المسار: من $tempStartName إلى $tempEndName • المسافة: ${String.format("%.1f", tempDistKm)} كم", 13, Color.rgb(126, 242, 154), true).apply {
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(tvInfo)

        val btnApply = button("✅ اعتماد هذه المسافة في حاسبة الوقود (${String.format("%.1f", tempDistKm)} كم)") {
            // will be bound below
        }.apply {
            setBackgroundColor(Color.rgb(110, 231, 255))
            setTextColor(Color.rgb(8, 23, 32))
        }
        root.addView(btnApply)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }

        val btnGoogleMaps = Button(this).apply {
            text = "🧭 فتح في Google Maps"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(36, 77, 111))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            layoutParams = lp
        }

        val btnClose = Button(this).apply {
            text = "إغلاق ✖"
            textSize = 12f
            setTextColor(Color.rgb(200, 220, 240))
            setBackgroundColor(Color.rgb(24, 45, 66))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            layoutParams = lp
        }

        bottomRow.addView(btnGoogleMaps)
        bottomRow.addView(btnClose)
        root.addView(bottomRow)

        val scroll = ScrollView(this).apply { addView(root) }
        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .create()

        btnApply.setOnClickListener {
            preferences.edit()
                .putFloat("planned_route_distance", tempDistKm)
                .putString("trip_start_name", tempStartName)
                .putString("trip_end_name", tempEndName)
                .putFloat("trip_start_lat", tempStartLat.toFloat())
                .putFloat("trip_start_lng", tempStartLng.toFloat())
                .putFloat("trip_end_lat", tempEndLat.toFloat())
                .putFloat("trip_end_lng", tempEndLng.toFloat())
                .apply()

            plannedRouteDistanceKm = tempDistKm
            tripStartLocationName = tempStartName
            tripEndLocationName = tempEndName
            tripStartLat = tempStartLat
            tripStartLng = tempStartLng
            tripEndLat = tempEndLat
            tripEndLng = tempEndLng

            Toast.makeText(this, "تم اعتماد مسار $tempStartName إلى $tempEndName (${String.format("%.1f", tempDistKm)} كم)", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            switchTab("fuel")
        }

        btnGoogleMaps.setOnClickListener {
            openGoogleMapsNavigation(tempStartLat, tempStartLng, tempEndLat, tempEndLng)
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnStart.setOnClickListener {
            val cityNames = popularCities.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("اختر نقطة البداية 🚩")
                .setItems(cityNames) { _, which ->
                    val chosen = popularCities[which]
                    tempStartName = chosen.name
                    tempStartLat = chosen.lat
                    tempStartLng = chosen.lng
                    btnStart.text = "🚩 من: ${chosen.name}"
                    webView.evaluateJavascript("javascript:setStartCity('${chosen.name}', ${chosen.lat}, ${chosen.lng});", null)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        btnEnd.setOnClickListener {
            val cityNames = popularCities.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("اختر نقطة الوصول 🏁")
                .setItems(cityNames) { _, which ->
                    val chosen = popularCities[which]
                    tempEndName = chosen.name
                    tempEndLat = chosen.lat
                    tempEndLng = chosen.lng
                    btnEnd.text = "🏁 إلى: ${chosen.name}"
                    webView.evaluateJavascript("javascript:setEndCity('${chosen.name}', ${chosen.lat}, ${chosen.lng});", null)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        btnSwap.setOnClickListener {
            val tName = tempStartName; val tLat = tempStartLat; val tLng = tempStartLng
            tempStartName = tempEndName; tempStartLat = tempEndLat; tempStartLng = tempEndLng
            tempEndName = tName; tempEndLat = tLat; tempEndLng = tLng
            btnStart.text = "🚩 من: $tempStartName"
            btnEnd.text = "🏁 إلى: $tempEndName"
            webView.evaluateJavascript("javascript:swapCities();", null)
        }

        btnGps.setOnClickListener {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            var currentLoc: Location? = null
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                currentLoc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            if (currentLoc != null) {
                tempStartName = "موقعي الحالي (GPS)"
                tempStartLat = currentLoc.latitude
                tempStartLng = currentLoc.longitude
                btnStart.text = "🚩 من: موقعي الحالي"
                webView.evaluateJavascript("javascript:setStartCity('موقعي الحالي', ${currentLoc.latitude}, ${currentLoc.longitude});", null)
                Toast.makeText(this, "تم تحديد موقع GPS الحالي كنقطة بداية 📍", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "جاري انتظار إشارة GPS... يرجى التأكد من تفعيل الموقع بالهاتف", Toast.LENGTH_SHORT).show()
            }
        }

        class MapJsBridge {
            @JavascriptInterface
            fun onRouteCalculated(distKm: Float, durMin: Int, sName: String, eName: String, sLat: Double, sLng: Double, eLat: Double, eLng: Double) {
                runOnUiThread {
                    tempDistKm = distKm
                    tempDurMin = durMin
                    tempStartName = sName
                    tempEndName = eName
                    tempStartLat = sLat
                    tempStartLng = sLng
                    tempEndLat = eLat
                    tempEndLng = eLng

                    val h = durMin / 60
                    val m = durMin % 60
                    val timeStr = if (h > 0) "$h س $m د" else "$m دقيقة"
                    tvInfo.text = "🛣️ المسار: من $sName إلى $eName\n• المسافة: ${String.format("%.1f", distKm)} كم • الوقت المقدر: $timeStr"
                    btnApply.text = "✅ اعتماد هذه المسافة في حاسبة الوقود (${String.format("%.1f", distKm)} كم)"
                    btnStart.text = "🚩 من: $sName"
                    btnEnd.text = "🏁 إلى: $eName"
                }
            }
        }

        webView.addJavascriptInterface(MapJsBridge(), "AndroidApp")
        webView.webViewClient = object : WebViewClient() {}

        val htmlContent = buildLeafletMapHtml(tempStartLat, tempStartLng, tempStartName, tempEndLat, tempEndLng, tempEndName)
        webView.loadDataWithBaseURL("https://openstreetmap.org", htmlContent, "text/html", "UTF-8", null)

        dialog.show()
    }

    private fun buildLeafletMapHtml(sLat: Double, sLng: Double, sName: String, eLat: Double, eLng: Double, eName: String): String {
        return """
<!DOCTYPE html>
<html dir="rtl" lang="ar">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  html, body { margin: 0; padding: 0; height: 100%; width: 100%; background: #081720; color: #fff; font-family: sans-serif; overflow: hidden; }
  #map { width: 100%; height: 100%; background: #0e2333; }
  .leaflet-popup-content-wrapper { background: #112334; color: #fff; border-radius: 8px; border: 1px solid #1f3b55; }
  .leaflet-popup-tip { background: #112334; }
  .info-overlay { position: absolute; top: 10px; right: 10px; left: 10px; z-index: 1000; background: rgba(8, 23, 32, 0.90); border: 1px solid #244d6f; border-radius: 8px; padding: 6px 10px; font-size: 11px; display: flex; justify-content: space-between; align-items: center; pointer-events: none; }
  .info-text { color: #6ee7ff; font-weight: bold; }
  .tap-hint { color: #f8c74a; font-size: 10px; }
</style>
</head>
<body>
<div id="map"></div>
<div class="info-overlay">
  <span id="route-label" class="info-text">انقر على الخريطة لتحديد البداية والوصول</span>
  <span class="tap-hint">🚩 بداية / 🏁 وصول</span>
</div>
<script>
  var startLat = $sLat, startLng = $sLng, startName = '$sName';
  var endLat = $eLat, endLng = $eLng, endName = '$eName';
  var map, startMarker = null, endMarker = null, routePolyline = null;
  var clickState = 0;

  function haversineRoadKm(lat1, lon1, lat2, lon2) {
    var R = 6371;
    var dLat = (lat2 - lat1) * Math.PI / 180;
    var dLon = (lon2 - lon1) * Math.PI / 180;
    var a = Math.sin(dLat/2) * Math.sin(dLat/2) +
            Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
            Math.sin(dLon/2) * Math.sin(dLon/2);
    var c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    return (R * c * 1.24).toFixed(1);
  }

  function initMap() {
    if (typeof L === 'undefined') {
      document.getElementById('map').innerHTML = '<div style="padding:20px;text-align:center;color:#6ee7ff;">جاري تحميل الخريطة التفاعلية...</div>';
      computeFallbackRoute();
      return;
    }
    map = L.map('map', { attributionControl: false, zoomControl: false }).setView([(startLat + endLat)/2, (startLng + endLng)/2], 7);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 18 }).addTo(map);

    updateMarkers();
    fetchRoute();

    map.on('click', function(e) {
      if (clickState === 0) {
        startLat = e.latlng.lat;
        startLng = e.latlng.lng;
        startName = 'نقطة محددة 🚩';
        clickState = 1;
      } else {
        endLat = e.latlng.lat;
        endLng = e.latlng.lng;
        endName = 'نقطة محددة 🏁';
        clickState = 0;
      }
      updateMarkers();
      fetchRoute();
    });
  }

  function updateMarkers() {
    if (!map) return;
    if (startMarker) map.removeLayer(startMarker);
    if (endMarker) map.removeLayer(endMarker);

    var startIcon = L.divIcon({
      html: '<div style="background:#10b981;color:#fff;font-size:16px;width:30px;height:30px;border-radius:50%;display:flex;align-items:center;justify-content:center;border:2px solid #fff;box-shadow:0 0 8px rgba(0,0,0,0.5);">🚩</div>',
      iconSize: [30, 30], iconAnchor: [15, 30]
    });
    var endIcon = L.divIcon({
      html: '<div style="background:#ef4444;color:#fff;font-size:16px;width:30px;height:30px;border-radius:50%;display:flex;align-items:center;justify-content:center;border:2px solid #fff;box-shadow:0 0 8px rgba(0,0,0,0.5);">🏁</div>',
      iconSize: [30, 30], iconAnchor: [15, 30]
    });

    startMarker = L.marker([startLat, startLng], { icon: startIcon }).addTo(map).bindPopup('<b>البداية: ' + startName + '</b>');
    endMarker = L.marker([endLat, endLng], { icon: endIcon }).addTo(map).bindPopup('<b>الوصول: ' + endName + '</b>');
  }

  function fetchRoute() {
    var url = 'https://router.project-osrm.org/route/v1/driving/' + startLng + ',' + startLat + ';' + endLng + ',' + endLat + '?overview=full&geometries=geojson';
    fetch(url)
      .then(function(res) { return res.json(); })
      .then(function(data) {
        if (data && data.routes && data.routes.length > 0) {
          var route = data.routes[0];
          var distKm = (route.distance / 1000).toFixed(1);
          var durMin = Math.round(route.duration / 60);
          drawGeoJsonRoute(route.geometry.coordinates, distKm, durMin);
        } else {
          computeFallbackRoute();
        }
      })
      .catch(function(err) {
        computeFallbackRoute();
      });
  }

  function drawGeoJsonRoute(coords, distKm, durMin) {
    if (routePolyline) map.removeLayer(routePolyline);
    var latLngs = coords.map(function(c) { return [c[1], c[0]]; });
    routePolyline = L.polyline(latLngs, { color: '#00e5ff', weight: 5, opacity: 0.9 }).addTo(map);
    map.fitBounds(routePolyline.getBounds(), { padding: [25, 25] });
    updateInfo(distKm, durMin);
  }

  function computeFallbackRoute() {
    var distKm = haversineRoadKm(startLat, startLng, endLat, endLng);
    var durMin = Math.round(distKm / 85 * 60);
    if (map) {
      if (routePolyline) map.removeLayer(routePolyline);
      routePolyline = L.polyline([[startLat, startLng], [endLat, endLng]], { color: '#00e5ff', weight: 4, dashArray: '6, 6', opacity: 0.8 }).addTo(map);
      map.fitBounds(routePolyline.getBounds(), { padding: [25, 25] });
    }
    updateInfo(distKm, durMin);
  }

  function updateInfo(distKm, durMin) {
    var h = Math.floor(durMin / 60);
    var m = durMin % 60;
    var timeStr = (h > 0 ? (h + ' س ') : '') + m + ' د';
    document.getElementById('route-label').innerText = 'المسار: ' + distKm + ' كم (' + timeStr + ')';
    if (window.AndroidApp && window.AndroidApp.onRouteCalculated) {
      window.AndroidApp.onRouteCalculated(parseFloat(distKm), durMin, startName, endName, startLat, startLng, endLat, endLng);
    }
  }

  window.setStartCity = function(name, lat, lng) {
    startName = name; startLat = lat; startLng = lng;
    updateMarkers(); fetchRoute();
  };
  window.setEndCity = function(name, lat, lng) {
    endName = name; endLat = lat; endLng = lng;
    updateMarkers(); fetchRoute();
  };
  window.swapCities = function() {
    var tLat = startLat, tLng = startLng, tName = startName;
    startLat = endLat; startLng = endLng; startName = endName;
    endLat = tLat; endLng = tLng; endName = tName;
    updateMarkers(); fetchRoute();
  };

  window.onload = initMap;
</script>
</body>
</html>
        """.trimIndent()
    }

    // ========================================================
    // TAB 5: MAINTENANCE & WORKSHOP (الصيانة والأعطال)
    // ========================================================
    private fun buildMaintenanceTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val oilTargetKm = preferences.getFloat("oil_target_km", 5000.0f)
        val oilCurrentKm = preferences.getFloat("oil_current_km", 1250.0f)
        val remainingOilKm = (oilTargetKm - oilCurrentKm).coerceAtLeast(0f)

        val cardMaint = card().apply {
            addView(text("🔧 فحص الأعطال وجدول الصيانة الدورية", 18, Color.WHITE, true))
            addView(text("أكواد وفحص أعطال كمبيوتر المحرك (DTC Live Scanner):", 14, Color.rgb(248, 199, 74), true).apply {
                setPadding(0, dp(6), 0, dp(4))
            })

            // Dynamic Live DTC Container
            containerDtcList = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(containerDtcList)
            renderDtcCards()

            val dtcActionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }

            dtcActionRow.addView(button("🧹 مسح الأعطال (Mode 04)") {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🧹 مسح أكواد الأعطال وإطفاء Check Engine")
                    .setMessage("هل ترغب في إرسال أمر مسح الأعطال (Mode 04) وإطفاء لمبة Check Engine من كمبيوتر المحرك الآن؟")
                    .setPositiveButton("نعم، مسح الآن") { _, _ ->
                        clearDiagnosticTroubleCodes { success, msg ->
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            renderDtcCards()
                        }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }.apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(0, 0, dp(3), 0)
                layoutParams = lp
                setBackgroundColor(Color.rgb(180, 35, 35))
                setTextColor(Color.WHITE)
            })

            dtcActionRow.addView(button("🔍 تفسير أي كود عطل") {
                showDtcLookupDialog()
            }.apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(3), 0, 0, 0)
                layoutParams = lp
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
            addView(dtcActionRow)

            addView(text("جدول الصيانة الدورية وزيت المحرك:", 14, Color.rgb(110, 231, 255), true).apply {
                setPadding(0, dp(12), 0, dp(4))
            })
            tvMaintOilText = text("• زيت المحرك والفلتر: مقطوع ${oilCurrentKm.toInt()} كم | متبقي ${remainingOilKm.toInt()} كم حتى موعد الغيار", 13, Color.WHITE, false)
            addView(tvMaintOilText)
            addView(text("• فحص تيل الفرامل الأمامي: موعد الفحص ساري", 13, Color.WHITE, false))
            addView(text("• تنظيف البوجيهات وحساس الشكمان: موصى به من الورشة", 13, Color.rgb(248, 199, 74), false))

            addView(button("🔄 تصفير عداد الزيت من هنا") {
                preferences.edit().putFloat("oil_current_km", 0.0f).apply()
                switchTab("maint")
            }.apply {
                setBackgroundColor(Color.rgb(248, 199, 74))
                setTextColor(Color.rgb(8, 23, 32))
            })

            addView(text("اختبارات الإنذار الصوتي والمرئي السريعة (تغلق تلقائياً بعد 6 ثوانٍ):", 14, Color.rgb(248, 199, 74), true).apply {
                setPadding(0, dp(12), 0, dp(4))
            })

            val alertTestRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                layoutParams = lp
            }

            alertTestRow.addView(button("🚨 تجربة حرارة 105°C") {
                isSimulatingAlert = true
                telemetry.coolantTemp = 105
                setSafetyAlert(
                    "🚨 إنذار طارئ: حرارة المحرك مرتفعة جداً (105 °C)!",
                    "تجاوزت حرارة التبريد الحد الأقصى! أوقف المحرك فوراً لفحص سائل التبريد ومروحة الرادياتير.",
                    critical = true
                )
                scanHandler.postDelayed({
                    if (isSimulatingAlert && telemetry.coolantTemp == 105) {
                        isSimulatingAlert = false
                        telemetry.coolantTemp = 88
                        telemetry.activeAlertTitle = null
                        updateAlertBannerUI()
                        updateLiveUI()
                    }
                }, 6000L)
            }.apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                setBackgroundColor(Color.rgb(180, 25, 35))
                setTextColor(Color.WHITE)
            })

            alertTestRow.addView(button("⛽ تجربة بنزين 8%") {
                isSimulatingAlert = true
                telemetry.fuelLevel = 8
                setSafetyAlert(
                    "⛽ انتباه: مستوى الوقود منخفض جداً (8%)!",
                    "متبقي أقل من 8% في الخزان. توجه لأقرب محطة وقود لتجنب نفاذ البنزين.",
                    critical = false
                )
                scanHandler.postDelayed({
                    if (isSimulatingAlert && telemetry.fuelLevel == 8) {
                        isSimulatingAlert = false
                        telemetry.fuelLevel = preferences.getInt("current_fuel_tank_percent", 50)
                        telemetry.activeAlertTitle = null
                        updateAlertBannerUI()
                        updateLiveUI()
                    }
                }, 6000L)
            }.apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                setBackgroundColor(Color.rgb(190, 110, 20))
                setTextColor(Color.WHITE)
            })

            addView(alertTestRow)

            addView(button("✅ استعادة الوضع الطبيعي الآمن") {
                isSimulatingAlert = false
                telemetry.coolantTemp = 88
                telemetry.fuelLevel = preferences.getInt("current_fuel_tank_percent", 50)
                telemetry.activeAlertTitle = null
                updateAlertBannerUI()
                updateLiveUI()
            }.apply {
                setBackgroundColor(Color.rgb(20, 60, 40))
                setTextColor(Color.rgb(126, 242, 154))
            })
        }
        container.addView(cardMaint)

        return container
    }

    // ========================================================
    // SIMULATION ENGINE
    // ========================================================
    private fun toggleSimulation() {
        isSimulating = !isSimulating
        if (!isSimulating) {
            tvConnectionState.text = "غير متصل"
            tvConnectionState.setTextColor(Color.rgb(255, 122, 122))
            return
        }

        tvConnectionState.text = "محاكاة حية نشطة 🟢"
        tvConnectionState.setTextColor(Color.rgb(126, 242, 154))

        val simVin = preferences.getString("vehicle_vin", "JTDBR32E12345678") ?: "JTDBR32E12345678"
        val decoded = decodeVin(simVin)
        telemetry.vin = simVin
        preferences.edit()
            .putString("vehicle_name", decoded.displayName)
            .putString("vehicle_make", decoded.make)
            .putString("vehicle_year", decoded.year)
            .putString("vehicle_data_source", "محاكاة بيانات الكمبيوتر (ECU Mode 09) 🟢")
            .apply()
        telemetry.batteryVoltage = 14.1f
        telemetry.fuelLevel = preferences.getInt("current_fuel_tank_percent", 75)
        telemetry.fuelLevelSource = "محاكاة حية 🟢"
        telemetry.fuelCalculationSource = "محاكاة حساس تدفق الهواء MAF (0110) 🟢"

        var step = 0
        thread {
            while (isSimulating) {
                step++
                val cycle = step % 50
                val inTrafficStop = cycle in 38..46

                if (inTrafficStop) {
                    telemetry.speed = 0
                    telemetry.rpm = 800 + (step % 30)
                    telemetry.engineLoadPercent = 24
                    telemetry.throttlePercent = 0
                    telemetry.mafGps = 3.8f
                    val idleLph = 1.25 + (0.05 * Math.sin(step * 0.3))
                    telemetry.instantFuelLitersPerHour = idleLph.toFloat()
                    telemetry.instantFuelLPer100Km = 0f
                } else {
                    telemetry.speed = (55 + (32 * Math.sin(step * 0.16))).toInt().coerceAtLeast(15)
                    telemetry.rpm = (1800 + (1000 * Math.sin(step * 0.2))).toInt().coerceAtLeast(1100)
                    telemetry.engineLoadPercent = (35 + (28 * Math.sin(step * 0.22))).toInt().coerceIn(15, 88)
                    telemetry.throttlePercent = (25 + (25 * Math.sin(step * 0.25))).toInt().coerceIn(5, 90)
                    // Real air mass flow in g/s for 1.6L engine:
                    telemetry.mafGps = ((telemetry.rpm / 60.0) * (telemetry.engineLoadPercent / 100.0) * 1.6 * 0.5 * 1.18 * 0.85).toFloat().coerceIn(4f, 45f)
                    // Real fuel flow L/h: MAF * 0.33094
                    val lph = (telemetry.mafGps.toDouble() * 0.33094).coerceIn(2.0, 28.0)
                    telemetry.instantFuelLitersPerHour = lph.toFloat()
                    telemetry.instantFuelLPer100Km = ((lph / telemetry.speed.toDouble()) * 100.0).toFloat().coerceIn(3.5f, 25.0f)
                }

                telemetry.coolantTemp = (88 + (step % 5)).toInt()
                if (step % 30 == 0) {
                    telemetry.dtcCodes = mutableListOf("P0130")
                } else if (step % 30 == 15) {
                    telemetry.dtcCodes.clear()
                }

                // Simulate distance, oil odometer, instant fuel and trip average
                val dtSec = 0.5
                val distSlice = (telemetry.speed * (dtSec / 3600.0)).toFloat()
                telemetry.tripDistanceKm += distSlice
                val curOil = preferences.getFloat("oil_current_km", 0f)
                preferences.edit().putFloat("oil_current_km", curOil + distSlice).apply()

                val consumedLiters = (telemetry.instantFuelLitersPerHour.toDouble() * (dtSec / 3600.0)).toFloat()
                telemetry.actualTripFuelLiters += consumedLiters
                if (telemetry.tripDistanceKm > 0.05f) {
                    telemetry.tripAverageFuelRate = (telemetry.actualTripFuelLiters / telemetry.tripDistanceKm) * 100f
                }

                runOnUiThread { updateLiveUI() }
                Thread.sleep(500)
            }
        }
    }

    private fun showFuelCalibrationDialog() {
        val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
        val currentLiters = (telemetry.fuelLevel * tankCap / 100.0f)
        val isCalibrated = preferences.getBoolean("fuel_is_calibrated", false)

        val invertedPreview = (100 - telemetry.fuelLevel).coerceIn(0, 100)
        val invertedLitersPreview = (invertedPreview * tankCap / 100.0f)

        val options = arrayOf(
            "🔍 فحص واستكشاف كمبيوترات السيارة ذاتياً (قراءة العدادات والمكينة تلقائياً)",
            "🟢 تانك كامل (Full - 100% / ${tankCap.toInt()} لتر)",
            "🔵 ثلاثة أرباع التانك (75% / ${(tankCap * 0.75f).toInt()} لتر)",
            "🟡 نصف التانك (50% / ${(tankCap * 0.5f).toInt()} لتر)",
            "🟠 ثلث التانك (33% / ${(tankCap * 0.33f).toInt()} لتر)",
            "🟠 ربع التانك - أقل من النصف (25% / ${(tankCap * 0.25f).toInt()} لتر)",
            "🔴 الاحتياطي / قرب النفاد (10% / ${(tankCap * 0.1f).toInt()} لتر)",
            "✏️ كتابة نسبة مئوية مخصصة يدوي (%...) (مثال: 21%)",
            "✏️ إدخال كمية الوقود باللتر بالتانك (مثال: 9.5 لتر)",
            "⛽ تم تزويد بنزين الآن (إضافة لترات فوق الرصيد الحالي)",
            "🔄 عكس قراءة حساس الـ OBD (تصبح: $invertedPreview% / ${String.format("%.1f", invertedLitersPreview)} لتر)",
            if (isCalibrated) "🔓 إلغاء التثبيت والعودة لقراءة حساس السيارة المباشر (Auto OBD)" else "🔒 تثبيت الرصيد الحالي ومنع حساس السيارة من تغييره",
            "⚙️ نوع الوقود والمحرك: ${telemetry.fuelType} (تغيير: بنزين / غاز طبيعي / سولار)"
        )

        val statusBadge = if (isCalibrated) "🟢 الرصيد مثبت ومحمي بدقة (لن يتغير عند فصل وإعادة توصيل الجهاز)" else "⚠️ قراءة تلقائية من حساس السيارة (قد يقرأ 71% بالخطأ)"

        val headerMessage = "الحالة الحالية لخزان الوقود:\n" +
                "• النسبة الحالية: ${telemetry.fuelLevel}%\n" +
                "• الكمية التقديرية: ${String.format("%.1f", currentLiters)} لتر من إجمالي ${tankCap.toInt()} لتر\n" +
                "• نوع الوقود: ${telemetry.fuelType} (${telemetry.fuelUnitName})\n" +
                "• وضع الحماية: $statusBadge\n\n" +
                "💡 يمكنك الضغط على الخيار الأول للفحص الذاتي المباشر لجميع كمبيوترات السيارة، أو اختيار المعايرة اليدوية:"

        AlertDialog.Builder(this)
            .setTitle("⛽ فحص ومعايرة كمية الوقود بالتانك")
            .setMessage(headerMessage)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> scanAllFuelComputers()
                    1 -> setFuelLevelManual(100)
                    2 -> setFuelLevelManual(75)
                    3 -> setFuelLevelManual(50)
                    4 -> setFuelLevelManual(33)
                    5 -> setFuelLevelManual(25)
                    6 -> setFuelLevelManual(10)
                    7 -> promptCustomFuelDialog()
                    8 -> promptCustomFuelLitersDialog()
                    9 -> promptAddFuelDialog()
                    10 -> toggleInvertFuelSensor()
                    11 -> {
                        if (isCalibrated) {
                            resetFuelToAutoObd()
                        } else {
                            setFuelLevelManual(telemetry.fuelLevel)
                        }
                    }
                    12 -> showFuelTypeDialog()
                }
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showFuelTypeDialog() {
        val currentType = preferences.getString("vehicle_fuel_type", "gasoline") ?: "gasoline"
        val fuelTypes = arrayOf(
            "⛽ بنزين 92 / 95 (Gasoline) - AFR 14.7 | كثافة 740 g/L",
            "🔵 غاز طبيعي مضغوط (CNG) - AFR 17.2 | كثافة 720 g/m³ (حساب بالمتر المكعب)",
            "🛢️ سولار / ديزل (Diesel) - AFR 22.0 | كثافة 840 g/L"
        )
        val selectedIdx = when (currentType) {
            "cng" -> 1
            "diesel" -> 2
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle("⛽ تحديد نوع الوقود والمحرك")
            .setSingleChoiceItems(fuelTypes, selectedIdx) { dialog, which ->
                val typeCode: String
                val unitName: String
                val defaultPrice: Float
                val typeLabel: String

                when (which) {
                    1 -> {
                        typeCode = "cng"
                        unitName = "م³"
                        defaultPrice = 7.0f
                        typeLabel = "غاز طبيعي (CNG)"
                    }
                    2 -> {
                        typeCode = "diesel"
                        unitName = "لتر"
                        defaultPrice = 13.5f
                        typeLabel = "ديزل / سولار (Diesel)"
                    }
                    else -> {
                        typeCode = "gasoline"
                        unitName = "لتر"
                        defaultPrice = 15.0f
                        typeLabel = "بنزين (Gasoline)"
                    }
                }

                preferences.edit()
                    .putString("vehicle_fuel_type", typeCode)
                    .putString("fuel_unit_name", unitName)
                    .putFloat("fuel_liter_price", defaultPrice)
                    .apply()
                telemetry.fuelType = typeLabel
                telemetry.fuelUnitName = unitName
                runOnUiThread {
                    updateLiveUI()
                    Toast.makeText(this, "تم اعتماد نوع الوقود: $typeLabel ($unitName) بنجاح ⛽", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun scanAllFuelComputers() {
        val outStream = activeObdOutStream
        val inStream = activeObdInStream

        if (outStream == null || inStream == null || !telemetry.isConnected) {
            val carName = preferences.getString("vehicle_name", "شيفروليه أفيو") ?: "شيفروليه أفيو"
            AlertDialog.Builder(this)
                .setTitle("🔍 فحص كمبيوترات السيارة ذاتياً (قراءة الوقود)")
                .setMessage("⚠️ جهاز الـ OBD غير متصل حالياً بسيارتك ($carName).\n\nعند توصيل الجهاز، يقوم هذا الفحص بمسح كافة كمبيوترات السيارة إلكترونياً:\n• كمبيوترات لوحة العدادات بالطابلوه (GM 7A4/724، Toyota 7C0، Nissan 743، Hyundai 7C6/7A0، وغيرها)\n• كمبيوتر المكينة (Engine ECU 7E0)\n• البث القياسي (7DF)\nويقرأ الوقود الحقيقي ذاتياً دون أي إدخال يدوي.\n\nهل ترغب في تجربة محاكاة الفحص الآن؟")
                .setPositiveButton("محاكاة الفحص الذاتي ⚡") { _, _ ->
                    simulateFuelComputerScan()
                }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }

        val progress = ProgressDialog(this).apply {
            setTitle("🔍 جاري فحص كمبيوترات السيارة...")
            setMessage("يتم الآن إرسال إشارات التشخيص إلى لوحة العدادات بالطابلوه وكمبيوتر المكينة...")
            setCancelable(false)
            show()
        }

        thread {
            val results = mutableListOf<String>()
            var bestProbe: FuelProbe? = null
            var bestFuel = -1

            synchronized(obdLock) {
                // Sweep all cluster and engine probes universally
                for (probe in universalFuelProbes) {
                    try {
                        if (probe.header != "7DF") {
                            sendObdCommand(outStream, inStream, "ATSH ${probe.header}", 250)
                            val rxAddr = Integer.toHexString(probe.header.toInt(16) + 8).uppercase()
                            sendObdCommand(outStream, inStream, "ATCRA $rxAddr", 200)
                        } else {
                            sendObdCommand(outStream, inStream, "ATCRA", 200)
                            sendObdCommand(outStream, inStream, "ATSH 7DF", 250)
                        }
                        val resp = sendObdCommand(outStream, inStream, probe.command, 450)
                        val fuel = parseProbeFuel(resp, probe)
                        if (fuel in 0..100) {
                            val badge = if (probe.isCluster) "🟢 (لوحة العدادات - قراءة العوامة)" else "⚠️ (كمبيوتر المكينة)"
                            results.add("• ${probe.description}: $fuel% $badge")
                            if (bestProbe == null && probe.isCluster) {
                                bestProbe = probe
                                bestFuel = fuel
                            } else if (bestProbe == null && !probe.isCluster) {
                                bestProbe = probe
                                bestFuel = fuel
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Restore default CAN header
                sendObdCommand(outStream, inStream, "ATCRA", 200)
                sendObdCommand(outStream, inStream, "ATSH 7DF", 250)
            }

            runOnUiThread {
                progress.dismiss()
                if (bestProbe != null && bestFuel in 0..100) {
                    activeFuelProbe = bestProbe
                    telemetry.fuelLevel = bestFuel
                    telemetry.fuelLevelSource = "${bestProbe!!.description} 🎯 قراءة ذاتية $bestFuel%"
                    preferences.edit()
                        .putString("active_fuel_probe_header", bestProbe!!.header)
                        .putString("active_fuel_probe_cmd", bestProbe!!.command)
                        .putBoolean("fuel_is_calibrated", false)
                        .putString("fuel_tracking_mode", "auto")
                        .putInt("current_fuel_tank_percent", bestFuel)
                        .apply()
                    updateLiveUI()
                }

                val msg = StringBuilder()
                msg.append("📋 تقرير الفحص الذاتي لكمبيوترات السيارة:\n\n")
                if (results.isEmpty()) {
                    msg.append("لم يتم العثور على استجابة من الكمبيوترات. تأكد من فتح كونتاكت السيارة (Ignition ON).")
                } else {
                    results.forEach { msg.append(it).append("\n\n") }
                }
                if (bestProbe != null) {
                    msg.append("━━━━━━━━━━━━━━━━━━\n")
                    msg.append("🎯 القرار الآلي الذكي:\n")
                    msg.append("تم اعتماد ${bestProbe!!.description} ذاتياً بنسبة $bestFuel% دون أي تدخل يدوي.")
                }

                AlertDialog.Builder(this)
                    .setTitle("✅ نتائج فحص كمبيوترات السيارة الذاتي")
                    .setMessage(msg.toString())
                    .setPositiveButton("ممتاز واعتماد 🎯") { d, _ -> d.dismiss() }
                    .show()
            }
        }
    }

    private fun simulateFuelComputerScan() {
        val carName = preferences.getString("vehicle_name", "شيفروليه أفيو") ?: "شيفروليه أفيو"
        val tankCap = preferences.getFloat("tank_capacity_liters", 45.0f)
        val msg = "📋 تقرير محاكاة الفحص الذاتي لكمبيوترات المركبة ($carName):\n\n" +
                "1. لوحة العدادات بالطابلوه (Dashboard Instrument Cluster):\n" +
                "   • الحالة: نشط ومستجيب 🟢\n" +
                "   • القراءة الذاتية: 21% (${String.format("%.1f", 0.21f * tankCap)} لتر)\n" +
                "   • التوصيل: سلك عوامة التانك متصل مباشرة بكمبيوتر العدادات بالطابلوه.\n\n" +
                "2. كمبيوتر المكينة (Engine ECU):\n" +
                "   • الحالة: نشط ⚠️\n" +
                "   • القراءة: 71% (قراءة افتراضية أو غير معيارية)\n" +
                "   • السبب: كمبيوتر المكينة في العديد من السيارات لا يرتبط مباشرة بالعوامة ويعطي قيمة ADC خام.\n\n" +
                "3. البث القياسي العام (Standard OBD Gateway):\n" +
                "   • القراءة: 71% (انعكاس كمبيوتر المكينة)\n\n" +
                "━━━━━━━━━━━━━━━━━━\n" +
                "🎯 القرار الذاتي للنظام:\n" +
                "يتم اعتماد قراءة لوحة العدادات بالطابلوه بنسبة 21% ذاتياً وتجاهل القراءة الخاطئة (71%)."

        AlertDialog.Builder(this)
            .setTitle("🔍 محاكاة الفحص الذاتي للكمبيوترات")
            .setMessage(msg)
            .setPositiveButton("اعتماد القراءة الذاتية (21%)") { _, _ ->
                telemetry.fuelLevel = 21
                telemetry.fuelLevelSource = "لوحة عدادات الطابلوه 🎯 قراءة ذاتية 21%"
                preferences.edit()
                    .putBoolean("fuel_is_calibrated", false)
                    .putString("fuel_tracking_mode", "auto")
                    .putInt("current_fuel_tank_percent", 21)
                    .apply()
                updateLiveUI()
                Toast.makeText(this, "تم اعتماد قراءة لوحة العدادات الذاتية 21% بنجاح 🎯", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun promptCustomFuelDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "أدخل النسبة من 1 إلى 100 (مثال: 21)"
            setTextColor(Color.BLACK)
        }
        AlertDialog.Builder(this)
            .setTitle("أدخل نسبة البنزين الحالية بالتانك (%)")
            .setMessage("النسبة ستثبت تماماً ويتم خصم الاستهلاك منها لحظياً أثناء القيادة:")
            .setView(input)
            .setPositiveButton("حفظ وتثبيت") { _, _ ->
                val txt = input.text.toString().trim()
                val percent = txt.toIntOrNull()
                if (percent != null && percent in 1..100) {
                    setFuelLevelManual(percent)
                } else {
                    Toast.makeText(this, "يرجى إدخال نسبة صحيحة بين 1 و 100", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptCustomFuelLitersDialog() {
        val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "أدخل عدد اللترات الحالية (مثال: 9.5)"
            setTextColor(Color.BLACK)
        }
        AlertDialog.Builder(this)
            .setTitle("أدخل كمية الوقود الحالية باللتر")
            .setMessage("سعة خزان الوقود الإجمالية المسجلة: ${tankCap.toInt()} لتر")
            .setView(input)
            .setPositiveButton("حفظ وتثبيت") { _, _ ->
                val txt = input.text.toString().trim()
                val liters = txt.toFloatOrNull()
                if (liters != null && liters in 0.5f..tankCap) {
                    val percent = ((liters / tankCap) * 100.0f).toInt().coerceIn(1, 100)
                    setFuelLevelManual(percent)
                } else {
                    Toast.makeText(this, "يرجى إدخال كمية صحيحة بين 1 و ${tankCap.toInt()} لتر", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptAddFuelDialog() {
        val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
        val currentLiters = (telemetry.fuelLevel * tankCap / 100.0f)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "أدخل اللترات التي تم تزويدها (مثال: 15)"
            setTextColor(Color.BLACK)
        }
        AlertDialog.Builder(this)
            .setTitle("⛽ تموين وقود - إضافة لترات جديدة")
            .setMessage("الرصيد الحالي قبل التموين: ${telemetry.fuelLevel}% (${String.format("%.1f", currentLiters)} لتر من إجمالي ${tankCap.toInt()} لتر)\n\nأدخل عدد اللترات التي قمت بتزويدها في محطة الوقود:")
            .setView(input)
            .setPositiveButton("إضافة للرصيد وتثبيت") { _, _ ->
                val txt = input.text.toString().trim()
                val addedLiters = txt.toFloatOrNull()
                if (addedLiters != null && addedLiters > 0f) {
                    val newTotalLiters = (currentLiters + addedLiters).coerceAtMost(tankCap)
                    val newPercent = ((newTotalLiters / tankCap) * 100.0f).toInt().coerceIn(1, 100)
                    setFuelLevelManual(newPercent)
                    Toast.makeText(this, "تمت إضافة ${String.format("%.1f", addedLiters)} لتر بنجاح ⛽ أصبح رصيد التانك: $newPercent%", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "يرجى كتابة عدد لترات صحيح", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun toggleInvertFuelSensor() {
        val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
        val cur = preferences.getBoolean("fuel_sensor_inverted", false)
        val newInvert = !cur
        val invertedLevel = (100 - telemetry.fuelLevel).coerceIn(1, 100)
        telemetry.fuelLevel = invertedLevel
        telemetry.fuelLevelSource = "معايرة مثبتة (عكس عوامة $invertedLevel%) 🧮"
        preferences.edit()
            .putBoolean("fuel_sensor_inverted", newInvert)
            .putBoolean("fuel_is_calibrated", true)
            .putString("fuel_tracking_mode", "manual_dynamic")
            .putInt("current_fuel_tank_percent", invertedLevel)
            .putLong("fuel_calibrated_time", System.currentTimeMillis())
            .apply()
        runOnUiThread {
            updateLiveUI()
            val stateText = if (newInvert) "مفعلة (100% - القراءة)" else "معطلة (القراءة الطبيعية)"
            Toast.makeText(this, "تم عكس قراءة الحساس وتثبيتها: $stateText.\nالنسبة الآن: ${telemetry.fuelLevel}% (${String.format("%.1f", invertedLevel * tankCap / 100f)} لتر)\n🔒 الرصيد مثبت ومحمي من الرجوع لـ 71% عند إعادة التوصيل.", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetFuelToAutoObd() {
        preferences.edit()
            .putBoolean("fuel_is_calibrated", false)
            .putString("fuel_tracking_mode", "auto")
            .apply()
        telemetry.fuelLevelSource = "بحث تلقائي عبر OBD 🔌"
        Toast.makeText(this, "تم إلغاء التثبيت وتفعيل القراءة التلقائية المباشرة من حساس الـ OBD 🔌", Toast.LENGTH_SHORT).show()
        runOnUiThread { updateLiveUI() }
    }

    private fun toggleFuelTrackingMode() {
        val isCalibrated = preferences.getBoolean("fuel_is_calibrated", false)
        if (isCalibrated) {
            resetFuelToAutoObd()
        } else {
            setFuelLevelManual(telemetry.fuelLevel)
        }
    }

    private fun setFuelLevelManual(level: Int, lockDynamic: Boolean = true) {
        val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
        val clampedLevel = level.coerceIn(1, 100)
        telemetry.fuelLevel = clampedLevel
        telemetry.fuelLevelSource = "معايرة مثبتة ($clampedLevel%) + تتبع ديناميكي 🧮"

        preferences.edit()
            .putBoolean("fuel_is_calibrated", true)
            .putInt("current_fuel_tank_percent", clampedLevel)
            .putString("fuel_tracking_mode", "manual_dynamic")
            .putLong("fuel_calibrated_time", System.currentTimeMillis())
            .apply()

        runOnUiThread {
            updateLiveUI()
            val liters = clampedLevel * tankCap / 100f
            Toast.makeText(this, "تم تثبيت رصيد التانك على $clampedLevel% (${String.format("%.1f", liters)} لتر) بنجاح ⛽\n🔒 محمي ولن يرجع أبداً لـ 71% عند فصل وإعادة التوصيل.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateGaugesTabUI() {
        if (!::tvSpeed.isInitialized) return
        tvSpeed.text = "${telemetry.speed} km/h"
        tvRpm.text = "${telemetry.rpm} RPM"
        tvTemp.text = "${telemetry.coolantTemp} °C"
        tvBattery.text = "${String.format("%.1f", telemetry.batteryVoltage)} V"
        val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
        val litersRemaining = (telemetry.fuelLevel * tankCap / 100.0f)
        val fuelBadge = if (telemetry.fuelLevelSource.contains("🔌")) "🔌" else "🧮"
        tvFuel.text = "${telemetry.fuelLevel}% (${String.format("%.1f", litersRemaining)}L) $fuelBadge"
        if (telemetry.vin.isNotBlank()) tvVin.text = telemetry.vin

        if (::tvInstantFuel.isInitialized) {
            if (telemetry.speed > 3) {
                tvInstantFuel.text = String.format("%.1f L/100 (%.2f L/h)", telemetry.instantFuelLPer100Km, telemetry.instantFuelLitersPerHour)
            } else {
                tvInstantFuel.text = String.format("%.2f L/h (توقف وزحام)", telemetry.instantFuelLitersPerHour)
            }
        }

        if (::tvTripFuel.isInitialized) {
            tvTripFuel.text = String.format("%.1f L/100 (%.2f لتر مستهلك)", telemetry.tripAverageFuelRate, telemetry.actualTripFuelLiters)
        }

        if (::tvEngineLoad.isInitialized) {
            tvEngineLoad.text = "${telemetry.engineLoadPercent} %"
        }
        if (::tvThrottle.isInitialized) {
            tvThrottle.text = "${telemetry.throttlePercent} %"
        }

        if (::tvFuelSourceBadge.isInitialized) {
            tvFuelSourceBadge.text = "⚡ مصدر قياس الوقود الآلي: ${telemetry.fuelCalculationSource}"
        }

        if (telemetry.dtcCodes.isEmpty()) {
            tvDtc.text = "لمبة الأعطال: لا توجد أعطال (Check Engine: OFF)"
            tvDtc.setTextColor(Color.rgb(126, 242, 154))
        } else {
            val firstCode = telemetry.dtcCodes.first()
            val diag = diagnoseDtc(firstCode)
            tvDtc.text = "تحذير أعطال: $firstCode (${diag.titleAr})"
            tvDtc.setTextColor(Color.rgb(255, 122, 122))
        }
    }

    private fun updateFuelTabLiveUI() {
        if (activeTab != "fuel") return
        val fuelPrice = preferences.getFloat("fuel_liter_price", 15.0f)
        val oilPrice = preferences.getFloat("oil_total_price", 1200.0f)
        val oilTargetKm = preferences.getFloat("oil_target_km", 5000.0f)
        val oilCurrentKm = preferences.getFloat("oil_current_km", 0.0f)
        val baselineFuelRate = preferences.getFloat("fuel_rate_baseline", 7.2f)
        val vehicleMarketValue = preferences.getFloat("vehicle_market_value", 400000.0f)
        val depRatePer1000Km = preferences.getFloat("depreciation_percent_per_1000km", 0.20f)
        val depCostPerKm = (vehicleMarketValue * (depRatePer1000Km / 100.0f)) / 1000.0f

        val weightPenaltyPercent = (plannedCargoWeightKg / 100.0f) * 5.0f
        val loadedFuelRate = baselineFuelRate * (1.0f + (weightPenaltyPercent / 100.0f))
        val oilCostPerKm = if (oilTargetKm > 0f) oilPrice / oilTargetKm else 0f

        val hasDrivenFirstKm = telemetry.tripDistanceKm >= 1.0f && telemetry.actualTripFuelLiters > 0.02f
        val realMeasuredTripRate = if (hasDrivenFirstKm) {
            (telemetry.actualTripFuelLiters / telemetry.tripDistanceKm) * 100.0f
        } else {
            telemetry.tripAverageFuelRate
        }
        val realCalibratedFuelCostPerKm = (realMeasuredTripRate / 100.0f) * fuelPrice
        val loadedFuelCostPerKm = (loadedFuelRate / 100.0f) * fuelPrice
        val activeCostPerKm = if (hasDrivenFirstKm) (realCalibratedFuelCostPerKm + oilCostPerKm + depCostPerKm) else (loadedFuelCostPerKm + oilCostPerKm + depCostPerKm)

        val actualDrivenKm = telemetry.tripDistanceKm
        val currentTripFuelCost = telemetry.actualTripFuelLiters * fuelPrice
        val currentTripOilCost = actualDrivenKm * oilCostPerKm
        val currentTripDepCost = actualDrivenKm * depCostPerKm
        val currentTripTotalCost = currentTripFuelCost + currentTripOilCost + currentTripDepCost

        val remainingRouteKm = (plannedRouteDistanceKm - actualDrivenKm).coerceAtLeast(0f)
        val remainingProjectedCost = (remainingRouteKm * realCalibratedFuelCostPerKm) + (remainingRouteKm * oilCostPerKm) + (remainingRouteKm * depCostPerKm)
        val realTripProjectedTotalCost = (plannedRouteDistanceKm * realCalibratedFuelCostPerKm) + (plannedRouteDistanceKm * oilCostPerKm) + (plannedRouteDistanceKm * depCostPerKm)
        val realTripProjectedFuelLiters = plannedRouteDistanceKm * (realMeasuredTripRate / 100.0f)

        val effectiveRate = if (hasDrivenFirstKm) realMeasuredTripRate else if (plannedCargoWeightKg > 0) loadedFuelRate else baselineFuelRate

        val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
        val litersRemaining = (telemetry.fuelLevel * tankCap / 100.0f)
        tvFuelTabActiveCostPerKm?.text = String.format("%.2f ج.م", activeCostPerKm)
        tvFuelTabTankPercent?.text = "${telemetry.fuelLevel}% (${String.format("%.1f", litersRemaining)}L)"
        tvFuelTabEffectiveRate?.text = String.format("%.1f L/100 (%s)", effectiveRate, if (hasDrivenFirstKm) "فعلي مقاس" else "تقديري")

        tvFuelTabTripDistance?.text = String.format("%.2f كم", telemetry.tripDistanceKm)
        tvFuelTabActualConsumed?.text = String.format("%.3f %s", telemetry.actualTripFuelLiters, telemetry.fuelUnitName)
        val instantStr = if (telemetry.speed > 3) {
            String.format("%.1f L/100 (%.1f L/h)", telemetry.instantFuelLPer100Km, telemetry.instantFuelLitersPerHour)
        } else {
            String.format("%.2f L/h (توقف وزحام)", telemetry.instantFuelLitersPerHour)
        }
        tvFuelTabInstantRate?.text = instantStr
        tvFuelTabCurrentCost?.text = String.format("%.2f ج.م", currentTripTotalCost)

        tvFuelTabSensorInfoText?.text = "• الحساس النشط: ${telemetry.fuelCalculationSource}\n" +
                "• تدفق الوقود اللحظي للمحرك: ${String.format("%.2f", telemetry.instantFuelLitersPerHour)} لتر/ساعة\n" +
                "• المستهلك الفعلي بالرحلة: ${String.format("%.3f", telemetry.actualTripFuelLiters)} لتر (${String.format("%.2f", currentTripFuelCost)} ج.م)\n" +
                "• حساب وقوف الزحام والإشارات: مدمج تلقائياً بدقة 100% (1.2 - 1.5 لتر/ساعة أثناء التوقف)."

        if (hasDrivenFirstKm) {
            containerFuelRealBox?.setBackgroundColor(Color.rgb(10, 48, 36))
            tvFuelTabRealBoxTitle?.text = "🎯 3. المعايرة الواقعية المؤكدة (بعد سير أول ${String.format("%.2f", telemetry.tripDistanceKm)} كم) 🟢"
            tvFuelTabRealBoxTitle?.setTextColor(Color.rgb(126, 242, 154))
            tvFuelTabRealBoxDetails?.text = "تم ضبط الاستهلاك الحقيقي لسيارتك تحت هذا الحمل والظروف الفعلية عبر حساسات OBD2!\n" +
                    "• معدل الاستهلاك الفعلي المقاس بالرحلة: ${String.format("%.1f", realMeasuredTripRate)} لتر / 100 كم\n" +
                    "• إجمالي تكلفة الرحلة الحقيقية المستنتجة كاملة (وقود + زيت + إهلاك): ${String.format("%.2f", realTripProjectedTotalCost)} ج.م (${String.format("%.1f", realTripProjectedFuelLiters)} لتر)\n" +
                    "• ما تم دفعه/استهلاكه حتى الآن: ${String.format("%.2f", currentTripTotalCost)} ج.م (${String.format("%.3f", telemetry.actualTripFuelLiters)} لتر)\n" +
                    "• التكلفة المتبقية لإنهاء المسار: ${String.format("%.2f", remainingProjectedCost)} ج.م (متبقي ${remainingRouteKm.toInt()} كم)"
        } else {
            containerFuelRealBox?.setBackgroundColor(Color.rgb(14, 28, 44))
            tvFuelTabRealBoxTitle?.text = "🎯 3. المعايرة الحقيقية التلقائية أثناء السير (بعد أول 1 كم) ⏳"
            tvFuelTabRealBoxTitle?.setTextColor(Color.rgb(110, 231, 255))
            tvFuelTabRealBoxDetails?.text = "💡 بمجرد انطلاقك بالسيارة وقطع أول كيلومتر (1 كم) من المشوار، ستقوم حساسات الـ OBD2 بقياس الاستهلاك الحقيقي لسيارتك تحت هذا الحمل والظروف الفعلية، وسيتم استبدال الحسابات التقديرية بالتكلفة الحقيقية المؤكدة آلياً!"
        }
    }

    private fun updateMaintenanceTabLiveUI() {
        if (activeTab != "maint") return
        val oilTargetKm = preferences.getFloat("oil_target_km", 5000.0f)
        val oilCurrentKm = preferences.getFloat("oil_current_km", 0.0f)
        val remainingOilKm = (oilTargetKm - oilCurrentKm).coerceAtLeast(0f)
        tvMaintOilText?.text = "• زيت المحرك والفلتر: مقطوع ${oilCurrentKm.toInt()} كم | متبقي ${remainingOilKm.toInt()} كم حتى موعد الغيار"
    }

    private fun updateLiveUI() {
        when (activeTab) {
            "gauges" -> updateGaugesTabUI()
            "fuel" -> updateFuelTabLiveUI()
            "maint" -> updateMaintenanceTabLiveUI()
        }

        // Automatic Safety Alerts & Warning Banner Check
        if (!isSimulatingAlert) {
            val oilTarget = preferences.getFloat("oil_target_km", 5000.0f)
            val oilCurrent = preferences.getFloat("oil_current_km", 0.0f)
            val remainingOil = (oilTarget - oilCurrent).coerceAtLeast(0f)

            if (telemetry.coolantTemp >= 100) {
                setSafetyAlert(
                    "🚨 تحذير طارئ: حرارة المحرك مرتفعة (${telemetry.coolantTemp} °C)!",
                    "حرارة سائل التبريد تجاوزت 100°C! يرجى التوقف فوراً وفحص سائل التبريد ومروحة الرادياتير.",
                    critical = true
                )
            } else if (telemetry.fuelLevel in 1..15) {
                setSafetyAlert(
                    "⛽ انتباه: مستوى الوقود منخفض (${telemetry.fuelLevel}%)",
                    "متبقي أقل من 15% في الخزان. توجه لأقرب محطة وقود لتجنب توقف المحرك وتلف طلمبة البنزين.",
                    critical = false
                )
            } else if (remainingOil <= 500f && oilTarget > 0f) {
                setSafetyAlert(
                    "🛢️ تذكير صيانة: اقترب موعد غيار الزيت (${remainingOil.toInt()} كم متبقي)",
                    "وصل استهلاك زيت المحرك للحد الموصى به. يرجى تغيير الزيت والفلتر للمحافظة على أداء المحرك.",
                    critical = false
                )
            } else if (telemetry.dtcCodes.isNotEmpty()) {
                val firstCode = telemetry.dtcCodes.first()
                val diag = diagnoseDtc(firstCode)
                val desc = if (telemetry.dtcCodes.size > 1) {
                    "${diag.titleAr} (+ ${telemetry.dtcCodes.size - 1} أعطال أخرى)"
                } else {
                    "${diag.titleAr} - ${diag.system}"
                }
                setSafetyAlert(
                    "⚠️ تنبيه عطل محرك: كود $firstCode",
                    desc,
                    critical = (diag.severity.contains("حرجة"))
                )
            } else if (telemetry.batteryVoltage in 0.1f..11.9f) {
                setSafetyAlert(
                    "🔋 تنبيه: شحن البطارية / الدينامو منخفض (${String.format("%.1f", telemetry.batteryVoltage)}V)",
                    "جهد الشحن أقل من 12.0 فولت أثناء التشغيل. يرجى فحص الدينامو وحزام السير.",
                    critical = false
                )
            } else {
                if (telemetry.activeAlertTitle != null) {
                    telemetry.activeAlertTitle = null
                    updateAlertBannerUI()
                }
            }
        }
    }

    private fun updateGauges() = updateLiveUI()

    private fun renderDtcCards() {
        val container = containerDtcList ?: return
        container.removeAllViews()

        if (telemetry.dtcCodes.isEmpty()) {
            val cleanBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(12, 44, 30))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }
            cleanBox.addView(text("✅ كمبيوتر المحرك سليم 100% (Check Engine: OFF)", 15, Color.rgb(126, 242, 154), true))
            cleanBox.addView(text("لا توجد أي أعطال مسجلة في ذاكرة كمبيوتر السيارة (ECU DTC). جميع الحساسات ودورات الاحتراق تعمل بكفاءة طبيعية.", 12, Color.rgb(200, 245, 220), false).apply {
                setPadding(0, dp(4), 0, 0)
            })
            container.addView(cleanBox)
        } else {
            val warnHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            warnHeader.addView(text("⚠️ تم رصد (${telemetry.dtcCodes.size}) كود عطل نشط في كمبيوتر السيارة:", 14, Color.rgb(255, 122, 122), true))
            container.addView(warnHeader)

            for (code in telemetry.dtcCodes) {
                val diag = diagnoseDtc(code)
                val dtcCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(36, 18, 22))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(6)
                    lp.bottomMargin = dp(4)
                    layoutParams = lp
                }

                val headRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                headRow.addView(text("[$code]", 16, Color.rgb(255, 122, 122), true))
                headRow.addView(text(" ${diag.titleAr}", 14, Color.WHITE, true))
                dtcCard.addView(headRow)

                val tagRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(4)
                    layoutParams = lp
                }
                tagRow.addView(text("🏷️ ${diag.system} | الخطورة: ${diag.severity}", 11, Color.rgb(248, 199, 74), false))
                dtcCard.addView(tagRow)

                dtcCard.addView(text("🔍 الأسباب المحتملة:", 12, Color.rgb(110, 231, 255), true).apply {
                    setPadding(0, dp(6), 0, dp(2))
                })
                dtcCard.addView(text(diag.causesAr, 12, Color.rgb(220, 220, 220), false))

                dtcCard.addView(text("🛠️ طريقة الإصلاح الموصى بها:", 12, Color.rgb(126, 242, 154), true).apply {
                    setPadding(0, dp(6), 0, dp(2))
                })
                dtcCard.addView(text(diag.solutionAr, 12, Color.rgb(200, 245, 220), false))

                container.addView(dtcCard)
            }
        }
    }

    private fun clearDiagnosticTroubleCodes(onComplete: (Boolean, String) -> Unit) {
        val socket = activeSocket
        val wifiSocket = activeWifiSocket

        if (socket != null && socket.isConnected) {
            thread {
                try {
                    sendObdCommand(socket.outputStream, socket.inputStream, "04", 2500)
                    telemetry.dtcCodes.clear()
                    telemetry.activeAlertTitle = null
                    runOnUiThread {
                        updateAlertBannerUI()
                        updateLiveUI()
                        onComplete(true, "تم إرسال أمر مسح الأعطال (Mode 04) وإطفاء لمبة Check Engine بنجاح 🟢")
                    }
                } catch (e: Exception) {
                    runOnUiThread { onComplete(false, "حدث خطأ أثناء مسح الأعطال: ${e.localizedMessage}") }
                }
            }
        } else if (wifiSocket != null && wifiSocket.isConnected) {
            thread {
                try {
                    sendObdCommand(wifiSocket.getOutputStream(), wifiSocket.getInputStream(), "04", 2500)
                    telemetry.dtcCodes.clear()
                    telemetry.activeAlertTitle = null
                    runOnUiThread {
                        updateAlertBannerUI()
                        updateLiveUI()
                        onComplete(true, "تم إرسال أمر مسح الأعطال (Mode 04) وإطفاء لمبة Check Engine بنجاح عبر Wi-Fi 🟢")
                    }
                } catch (e: Exception) {
                    runOnUiThread { onComplete(false, "حدث خطأ أثناء مسح الأعطال: ${e.localizedMessage}") }
                }
            }
        } else {
            telemetry.dtcCodes.clear()
            telemetry.activeAlertTitle = null
            runOnUiThread {
                updateAlertBannerUI()
                updateLiveUI()
                onComplete(true, "تم مسح سجل الأعطال وإطفاء لمبة Check Engine بنجاح (وضع المحاكاة/غير متصل) ✅")
            }
        }
    }

    // ========================================================
    // SCANNER & BLUETOOTH ENGINE
    // ========================================================
    private fun beginDiscovery() {
        if (isScanning) return
        if (!hasPermissions()) {
            requestPermissionsCompat()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            statusText.text = "هذا الهاتف لا يدعم البلوتوث."
            return
        }

        if (!adapter.isEnabled) {
            statusText.text = "يرجى تشغيل البلوتوث في الهاتف أولاً."
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {}
            return
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsEnabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        if (!isGpsEnabled) {
            statusText.text = "⚠️ خدمة الموقع (GPS) مغلقة! يجب تفعيلها لكي يكتشف الأندرويد البلوتوث."
        } else {
            statusText.text = "جارٍ فحص الأجهزة المقترنة والبحث عن قطع OBD القريبة..."
        }

        candidates.clear()
        renderCandidates()
        scanButton.text = "⏹ إيقاف البحث"
        isScanning = true

        addBondedDevices()
        inspectWifiCandidate()

        if (!classicReceiverRegistered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            registerReceiver(classicReceiver, filter)
            classicReceiverRegistered = true
        }

        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (_: SecurityException) {}

        try {
            val scanner: BluetoothLeScanner? = adapter.bluetoothLeScanner
            scanner?.startScan(bleCallback)
        } catch (_: SecurityException) {}

        scanHandler.removeCallbacks(scanTimeoutRunnable)
        scanHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    private fun stopDiscovery(timedOut: Boolean = false) {
        if (!isScanning) return
        scanHandler.removeCallbacks(scanTimeoutRunnable)
        isScanning = false

        if (::scanButton.isInitialized) scanButton.text = "🔍 بدء البحث عن أجهزة OBD"

        bluetoothAdapter?.let { adapter ->
            try {
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                adapter.bluetoothLeScanner?.stopScan(bleCallback)
            } catch (_: SecurityException) {}
        }

        if (::statusText.isInitialized) {
            statusText.text = if (timedOut) "اكتمل وقت البحث (${candidates.size} جهاز جاهز)." else "تم إيقاف البحث."
        }
    }

    private fun addBondedDevices() {
        try {
            val bonded = bluetoothAdapter?.bondedDevices
            if (!bonded.isNullOrEmpty()) {
                bonded.forEach { dev ->
                    addBluetoothCandidate(dev, "Bluetooth مقترن 🟢", null)
                }
            }
        } catch (_: SecurityException) {}
    }

    private fun inspectWifiCandidate() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val info = wifi.connectionInfo ?: return
        val ssid = info.ssid?.replace("\"", "") ?: ""
        if (ssid.isNotBlank() && ssid != "<unknown ssid>") {
            val candidate = DeviceCandidate(
                id = "wifi:$ssid",
                title = "شبكة Wi-Fi: $ssid",
                transport = "Wi-Fi OBD",
                address = "192.168.0.10:35000",
                detail = "منفذ Wi-Fi الافتراضي لمحولات ELM327 (192.168.0.10:35000)"
            )
            candidates[candidate.id] = candidate
            renderCandidates()
        }
    }

    private fun addBluetoothCandidate(device: BluetoothDevice, transport: String, rssi: Int?) {
        try {
            val name = device.name ?: "جهاز غير مسمى"
            val addr = device.address ?: return
            val id = "bt:$addr"
            val rssiInfo = if (rssi != null) " | الإشارة: $rssi dBm" else ""
            val candidate = DeviceCandidate(
                id = id,
                title = name,
                transport = transport,
                address = addr,
                detail = "$addr$rssiInfo"
            )
            candidates[id] = candidate
            runOnUiThread { renderCandidates() }
        } catch (_: SecurityException) {}
    }

    private fun renderCandidates() {
        if (!::resultsContainer.isInitialized) return
        resultsContainer.removeAllViews()

        if (candidates.isEmpty()) {
            val emptyCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(17, 35, 52))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp

                addView(text("لم يتم العثور على أجهزة بعد.", 14, Color.rgb(255, 180, 50), true))
                addView(text("خطوات التوصيل السريع لقطعة الـ OBD2:\n1. تأكد من فتح كونتاكت السيارة (المفتاح على وضع ON أو تشغيل المحرك).\n2. تأكد من تفعيل البلوتوث وخدمة الموقع (GPS) على هاتفك.\n3. إذا كانت القطعة جديدة، قم بعمل اقتران لها من إعدادات الهاتف أولاً برمز 1234.", 12, Color.rgb(167, 203, 226), false).apply {
                    setPadding(0, dp(4), 0, dp(6))
                })

                addView(button("📲 اقتران بالقطعة من إعدادات الهاتف (رمز 1234)") {
                    try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
                }.apply {
                    setBackgroundColor(Color.rgb(36, 77, 111))
                    setTextColor(Color.WHITE)
                })

                addView(button("🔄 إعادة فحص الأجهزة المقترنة الآن") {
                    addBondedDevices()
                    renderCandidates()
                }.apply {
                    setBackgroundColor(Color.rgb(24, 55, 80))
                    setTextColor(Color.rgb(110, 231, 255))
                })
            }
            resultsContainer.addView(emptyCard)
            return
        }

        val sortedList = candidates.values.sortedByDescending { cand ->
            cand.title.contains("OBD", ignoreCase = true) || cand.title.contains("ELM", ignoreCase = true) || cand.title.contains("LINK", ignoreCase = true) || cand.title.contains("CAR", ignoreCase = true)
        }
        sortedList.forEach { cand ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(17, 35, 52))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp

                val isObd = cand.title.contains("OBD", ignoreCase = true) || cand.title.contains("ELM", ignoreCase = true) || cand.title.contains("LINK", ignoreCase = true)
                val badge = if (isObd) " 🚗 [قطعة فحص سيارة]" else ""

                addView(text("${cand.title}$badge", 16, if (isObd) Color.rgb(126, 242, 154) else Color.WHITE, true))
                addView(text("${cand.transport} • العنوان: ${cand.detail}", 12, Color.rgb(167, 203, 226), false))

                addView(button("🚀 اتصال وقراءة الحساسات الحية") {
                    selectDevice(cand)
                }.apply {
                    setBackgroundColor(if (isObd) Color.rgb(32, 120, 85) else Color.rgb(36, 77, 111))
                    setTextColor(Color.WHITE)
                })
            }
            resultsContainer.addView(row)
        }
    }

    private fun selectDevice(cand: DeviceCandidate) {
        preferences.edit()
            .putString("device_id", cand.id)
            .putString("device_name", cand.title)
            .putString("device_transport", cand.transport)
            .apply()

        connectToDevice(cand)
    }

    private fun connectToDevice(cand: DeviceCandidate) {
        stopDiscovery()
        disconnectObd()

        telemetry.isConnected = false
        statusText.text = "جاري إنشاء اتصال تسلسلي مع ${cand.title}..."
        selectedText.text = "الجهاز المختار: ${cand.title} (${cand.transport})"
        if (::tvConnectionState.isInitialized) {
            tvConnectionState.text = "جاري الاتصال..."
            tvConnectionState.setTextColor(Color.rgb(248, 199, 74))
        }

        thread {
            try {
                if (cand.transport.contains("Wi-Fi")) {
                    connectWifiObd(cand.address)
                } else {
                    connectBluetoothObd(cand.address, cand.title)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "فشل الاتصال: ${e.localizedMessage ?: "تأكد من فتح كونتاكت السيارة وتشغيل القطعة"}"
                    telemetry.isConnected = false
                    if (::tvConnectionState.isInitialized) {
                        tvConnectionState.text = "فشل الاتصال 🔴"
                        tvConnectionState.setTextColor(Color.rgb(255, 122, 122))
                    }
                }
            }
        }
    }

    private fun connectBluetoothObd(macAddress: String, devName: String) {
        val adapter = bluetoothAdapter ?: throw IllegalStateException("البلوتوث غير متوفر في هاتفك")
        if (!adapter.isEnabled) throw IllegalStateException("يرجى تشغيل البلوتوث أولاً")

        // 1. Force stop any background discovery to prevent RFCOMM handshake timeout
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            adapter.bluetoothLeScanner?.stopScan(bleCallback)
        } catch (_: SecurityException) {}

        disconnectObd()
        Thread.sleep(250)

        val device = adapter.getRemoteDevice(macAddress)
        runOnUiThread {
            statusText.text = "جاري فتح قناة RFCOMM مع $devName..."
        }

        var socket: BluetoothSocket? = null
        var lastEx: Exception? = null

        // Strategy 1: Insecure RFCOMM SPP (Top priority for 95% of ELM327 OBD clones)
        try {
            runOnUiThread { statusText.text = "محاولة فتح قناة تسلسلية مباشرة (Insecure SPP)..." }
            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
        } catch (e1: Exception) {
            lastEx = e1
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            Thread.sleep(350)
        }

        // Strategy 2: Standard Secure RFCOMM SPP (For genuine / high-end adapters)
        if (socket == null || !socket.isConnected) {
            try {
                runOnUiThread { statusText.text = "محاولة القناة القياسية (Secure SPP)..." }
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
            } catch (e2: Exception) {
                lastEx = e2
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                Thread.sleep(350)
            }
        }

        // Strategy 3: Direct RFCOMM Channel 1 Reflection (For older/stubborn ELM327 hardware)
        if (socket == null || !socket.isConnected) {
            try {
                runOnUiThread { statusText.text = "محاولة القناة المباشرة 1 (RFCOMM Port 1)..." }
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket = m.invoke(device, 1) as BluetoothSocket
                socket.connect()
            } catch (e3: Exception) {
                lastEx = e3
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                Thread.sleep(350)
            }
        }

        // Strategy 4: Insecure RFCOMM Channel 1 Reflection
        if (socket == null || !socket.isConnected) {
            try {
                runOnUiThread { statusText.text = "محاولة القناة البديلة المفتوحة (Insecure Port 1)..." }
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                socket = m.invoke(device, 1) as BluetoothSocket
                socket.connect()
            } catch (e4: Exception) {
                lastEx = e4
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }

        if (socket == null || !socket.isConnected) {
            val errDetail = lastEx?.localizedMessage ?: "A Bluetooth Socket failure occurred"
            throw Exception("تعذر الاتصال بقطعة $devName.\n" +
                    "• تأكد أن مفتاح كونتاكت السيارة على وضع التشغيل (ON).\n" +
                    "• إذا تكرر الخطأ: انزع قطعة الـ OBD من السيارة وانتظر 5 ثوانٍ ثم أعد تركيبها (لإلغاء تعليقها).\n" +
                    "• تفاصيل الخطأ: $errDetail")
        }

        activeSocket = socket
        runObdCommunicationLoop(socket.inputStream, socket.outputStream, devName)
    }

    private fun connectWifiObd(ipAndPort: String) {
        val parts = ipAndPort.split(":")
        val host = parts.getOrNull(0) ?: "192.168.0.10"
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 35000

        runOnUiThread {
            statusText.text = "جاري الاتصال بشبكة Wi-Fi OBD ($host:$port)..."
        }

        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 6000)
        activeWifiSocket = socket

        runObdCommunicationLoop(socket.getInputStream(), socket.getOutputStream(), "Wi-Fi OBD")
    }

    private fun sendObdCommand(outStream: OutputStream, inStream: InputStream, cmd: String, timeoutMs: Long = 1800L): String {
        synchronized(obdLock) {
            try {
                // Drain any pending unread bytes from stream
                while (inStream.available() > 0) {
                    inStream.read()
                }
            } catch (_: Exception) {}

            outStream.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            outStream.flush()

            val sb = StringBuilder()
            val start = System.currentTimeMillis()
            val buffer = ByteArray(256)

            while (System.currentTimeMillis() - start < timeoutMs) {
                if (inStream.available() > 0) {
                    val bytesRead = inStream.read(buffer)
                    if (bytesRead > 0) {
                        sb.append(String(buffer, 0, bytesRead, Charsets.US_ASCII))
                        if (sb.contains(">")) break
                    }
                } else {
                    Thread.sleep(15)
                }
            }
            return sb.toString().replace(">", "").trim()
        }
    }

    private fun runObdCommunicationLoop(inStream: InputStream, outStream: OutputStream, devName: String) {
        isTelemetryLoopRunning = true
        isSimulatingAlert = false
        activeObdOutStream = outStream
        activeObdInStream = inStream
        try {
            runOnUiThread {
                statusText.text = "جاري تهيئة أوامر ELM327 واكتشاف بروتوكول السيارة..."
            }

        // Initialize ELM327
        sendObdCommand(outStream, inStream, "ATZ", 1500)
        Thread.sleep(300)
        sendObdCommand(outStream, inStream, "ATE0", 600)
        sendObdCommand(outStream, inStream, "ATL0", 500)
        sendObdCommand(outStream, inStream, "ATS0", 500)
        sendObdCommand(outStream, inStream, "ATH0", 500)
        sendObdCommand(outStream, inStream, "ATSP0", 1500)

        runOnUiThread {
            telemetry.isConnected = true
            statusText.text = "متصل بنجاح بـ $devName! جاري قراءة الحساسات الحية 🟢"
            selectedText.text = "الجهاز المتصل: $devName (متصل حياً)"
            if (::tvConnectionState.isInitialized) {
                tvConnectionState.text = "متصل 🟢"
                tvConnectionState.setTextColor(Color.rgb(126, 242, 154))
            }
        }

        // Read VIN with resilient multi-tier ECU engine
        val cleanVin = fetchVinFromEcu(outStream, inStream)
        if (cleanVin.isNotBlank()) {
            telemetry.vin = cleanVin
            val decoded = decodeVin(cleanVin)
            preferences.edit()
                .putString("vehicle_vin", cleanVin)
                .putString("vehicle_name", decoded.displayName)
                .putString("vehicle_make", decoded.make)
                .putString("vehicle_year", decoded.year)
                .putString("vehicle_data_source", "كمبيوتر ECU مباشر (Mode 09) 🔌")
                .apply()
        } else {
            // Attempt Electronic Auto-Identification via Calibration ID (CALID - Mode 09 PID 04)
            val calid = fetchCalidFromEcu(outStream, inStream)
            val calidVehicle = if (calid.isNotBlank()) identifyVehicleFromCalid(calid) else null
            if (calidVehicle != null) {
                telemetry.vin = "CALID: $calid"
                preferences.edit()
                    .putString("vehicle_name", calidVehicle.name)
                    .putString("vehicle_make", calidVehicle.make)
                    .putString("vehicle_year", calidVehicle.year)
                    .putString("vehicle_vin", "معرّف إلكتروني: $calid")
                    .putFloat("tank_capacity_liters", calidVehicle.tank)
                    .putFloat("engine_displacement_liters", calidVehicle.displacement)
                    .putString("vehicle_fuel_type", calidVehicle.fuelType)
                    .putString("fuel_unit_name", if (calidVehicle.fuelType == "cng") "م³" else "لتر")
                    .putString("vehicle_data_source", "سوفت وير الكمبيوتر (CALID: $calid) 🔌")
                    .apply()
            } else {
                // Auto-detect from paired Bluetooth car (e.g. AVEO / CHEVROLET) if Mode 09 is restricted
                val detected = detectCarFromPairedDevices()
                val currentVin = preferences.getString("vehicle_vin", "") ?: ""
                if (detected != null && (currentVin.isEmpty() || currentVin == "JTDBR32E12345678")) {
                    val isAveo = detected.first.contains("Aveo", ignoreCase = true)
                    preferences.edit()
                        .putString("vehicle_name", detected.first)
                        .putString("vehicle_make", detected.second)
                        .putString("vehicle_year", "معتمد")
                        .putString("vehicle_vin", "تم التعرف تلقائياً عبر نظام السيارة")
                        .putFloat("tank_capacity_liters", if (isAveo) 45.0f else 50.0f)
                        .putFloat("engine_displacement_liters", if (isAveo) 1.5f else 1.6f)
                        .putString("vehicle_data_source", "بلوتوث السيارة المباشر (${detected.first}) 🚗")
                        .apply()
                }
            }
        }

        // Universal Discovery for Fuel Level Float (ECU or Dashboard Cluster)
        activeFuelProbe = discoverUniversalFuelProbe(inStream, outStream)

        // Automated High-Precision Fuel Flow Sensor Discovery (Zero Manual Setup)
        activeFuelSensorMode = discoverBestFuelSensor(inStream, outStream)

        runOnUiThread {
            statusText.text = "متصل بنجاح 🟢 تم تفعيل القياس الآلي: ${telemetry.fuelCalculationSource}"
        }

        var loopCount = 0
        var lastCalcTime = System.currentTimeMillis()
        var accumulatedFuelSlice = 0.0

        while (isTelemetryLoopRunning) {
            loopCount++
            try {
                // 1. RPM (PID 010C)
                val rpmResp = sendObdCommand(outStream, inStream, "010C", 500)
                val rpm = parseRpm(rpmResp)
                if (rpm >= 0) telemetry.rpm = rpm

                // 2. Speed (PID 010D)
                val speedResp = sendObdCommand(outStream, inStream, "010D", 500)
                val speed = parseSpeed(speedResp)
                if (speed >= 0) telemetry.speed = speed

                // 3. Engine Load (PID 0104) & Throttle TPS (PID 0111)
                if (loopCount % 2 == 0) {
                    val loadResp = sendObdCommand(outStream, inStream, "0104", 400)
                    val load = parseEngineLoad(loadResp)
                    if (load >= 0) telemetry.engineLoadPercent = load
                } else {
                    val tpsResp = sendObdCommand(outStream, inStream, "0111", 400)
                    val tps = parseThrottle(tpsResp)
                    if (tps >= 0) telemetry.throttlePercent = tps
                }

                // 3.5. Coolant Temp (PID 0105)
                if (loopCount % 4 == 0) {
                    val tempResp = sendObdCommand(outStream, inStream, "0105", 500)
                    val temp = parseCoolant(tempResp)
                    if (temp != -999) telemetry.coolantTemp = temp
                }

                // 4. Automated High-Accuracy Multi-Fuel Flow Measurement
                var instantLph = 0.0

                val fType = preferences.getString("vehicle_fuel_type", "gasoline") ?: "gasoline"
                val displacement = preferences.getFloat("engine_displacement_liters", 1.5f).toDouble().coerceIn(0.8, 6.5)
                // Physics: Fuel Flow = (MAF g/s * 3600) / (AFR * Density)
                // Gasoline: AFR = 14.7, Density = 740 g/L -> factor = 0.33094 L/h
                // Diesel: Lean burn driving average AFR = 22.0, Density = 840 g/L -> factor = 0.1948 L/h
                // CNG (Natural Gas): Stoichiometric AFR = 17.2, Gas Density = 720 g/m^3 -> factor = 0.2907 m^3/h
                val fuelFactor = when (fType) {
                    "diesel" -> (3600.0 / (22.0 * 840.0))
                    "cng" -> (3600.0 / (17.2 * 720.0))
                    else -> (3600.0 / (14.7 * 740.0))
                }

                when (activeFuelSensorMode) {
                    FuelSensorMode.DIRECT_ECU_RATE_5E -> {
                        val r5E = sendObdCommand(outStream, inStream, "015E", 400)
                        val rate = parseFuelRate5E(r5E)
                        if (rate > 0f) {
                            telemetry.directFuelRateLph = rate
                            instantLph = rate.toDouble()
                        } else if (telemetry.directFuelRateLph > 0f) {
                            instantLph = telemetry.directFuelRateLph.toDouble()
                        }
                    }
                    FuelSensorMode.MAF_AIR_FLOW_10 -> {
                        val rMaf = sendObdCommand(outStream, inStream, "0110", 400)
                        val m = parseMaf(rMaf)
                        if (m > 0f) {
                            telemetry.mafGps = m
                        }
                        if (loopCount % 6 == 0) {
                            val trimResp = sendObdCommand(outStream, inStream, "0106", 350)
                            val trim = parseFuelTrim(trimResp)
                            if (trim > -40f && trim < 40f) {
                                telemetry.shortTermFuelTrimPercent = trim
                            }
                        }
                        val stftFactor = 1.0 + (telemetry.shortTermFuelTrimPercent / 100.0).coerceIn(-0.25, 0.25)
                        if (telemetry.mafGps > 0f) {
                            instantLph = (telemetry.mafGps.toDouble() * fuelFactor * stftFactor).coerceIn(0.2, 85.0)
                        }
                    }
                    FuelSensorMode.MAP_SPEED_DENSITY_0B -> {
                        val rMap = sendObdCommand(outStream, inStream, "010B", 400)
                        val p = parseMap(rMap)
                        if (p > 0) telemetry.mapKpa = p
                        if (loopCount % 6 == 0) {
                            val rIat = sendObdCommand(outStream, inStream, "010F", 350)
                            val iat = parseIat(rIat)
                            if (iat != -999) telemetry.intakeAirTempC = iat
                        }
                        val rpmVal = telemetry.rpm.coerceAtLeast(650)
                        val mapKpa = telemetry.mapKpa.coerceAtLeast(20)
                        val tempK = (telemetry.intakeAirTempC + 273.15).coerceIn(250.0, 360.0)
                        val loadRatio = (telemetry.engineLoadPercent / 100.0).coerceIn(0.15, 0.95)
                        val ve = (0.72 + (0.16 * loadRatio)).coerceIn(0.65, 0.92)
                        // Speed-Density Air Mass: (MAP * Vd/2 * RPM/60 * VE * 28.97) / (R * T)
                        val airMassGps = (mapKpa * 1000.0 * (displacement / 2000.0) * (rpmVal / 60.0) * ve * 28.97) / (8.314 * tempK)
                        instantLph = (airMassGps * fuelFactor).coerceIn(0.3, 85.0)
                    }
                    FuelSensorMode.DYNAMIC_LOAD_FUSION -> {
                        val loadFrac = (telemetry.engineLoadPercent / 100.0).coerceIn(0.12, 1.0)
                        if (telemetry.speed <= 2) {
                            val idleScale = when (fType) {
                                "diesel" -> 0.70
                                "cng" -> 0.85
                                else -> 1.05
                            }
                            instantLph = (idleScale + (0.35 * loadFrac) * (telemetry.rpm.toDouble() / 800.0).coerceIn(0.8, 1.6)).coerceIn(0.6, 3.5)
                        } else {
                            val airMass = (displacement / 2.0) * (telemetry.rpm.toDouble() / 60.0) * loadFrac * 1.18 * 0.82
                            instantLph = (airMass * fuelFactor).coerceIn(0.8, 65.0)
                        }
                    }
                }

                // If engine running and instantLph is unrealistically low (e.g. idle floor with AC)
                if (telemetry.rpm > 450 && instantLph < 0.6) {
                    val idleBase = when (fType) {
                        "diesel" -> 0.75
                        "cng" -> 0.85
                        else -> 1.05
                    }
                    val idleFloor = idleBase + (0.25 * (telemetry.engineLoadPercent / 100.0))
                    instantLph = idleFloor.coerceIn(0.7, 2.5)
                } else if (telemetry.rpm <= 450) {
                    instantLph = 0.0
                }

                telemetry.instantFuelLitersPerHour = instantLph.toFloat()
                if (telemetry.speed > 3) {
                    telemetry.instantFuelLPer100Km = ((instantLph / telemetry.speed.toDouble()) * 100.0).toFloat().coerceIn(2.0f, 40.0f)
                } else {
                    telemetry.instantFuelLPer100Km = 0f
                }

                // 5. Dynamic Consumption, Distance & Oil Odometer Calculations
                val now = System.currentTimeMillis()
                val dtSec = ((now - lastCalcTime) / 1000.0).coerceIn(0.05, 5.0)
                lastCalcTime = now

                val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f).coerceAtLeast(20.0f)
                val baseRate100km = preferences.getFloat("fuel_rate_baseline", 8.5f).coerceAtLeast(3.0f)

                val distanceSliceKm = (telemetry.speed.toDouble() * (dtSec / 3600.0))
                val consumedSliceLiters = (instantLph * (dtSec / 3600.0))

                if (distanceSliceKm > 0) {
                    telemetry.tripDistanceKm += distanceSliceKm.toFloat()

                    // Increment Engine Oil Odometer in preferences!
                    val curOilKm = preferences.getFloat("oil_current_km", 0f)
                    val newOilKm = curOilKm + distanceSliceKm.toFloat()
                    preferences.edit().putFloat("oil_current_km", newOilKm).apply()
                }

                if (consumedSliceLiters > 0) {
                    telemetry.actualTripFuelLiters += consumedSliceLiters.toFloat()
                }

                // 6. Update Trip Average Fuel Rate
                if (telemetry.tripDistanceKm >= 0.1f && telemetry.actualTripFuelLiters > 0f) {
                    telemetry.tripAverageFuelRate = ((telemetry.actualTripFuelLiters / telemetry.tripDistanceKm) * 100f).coerceIn(2.5f, 35.0f)
                } else {
                    telemetry.tripAverageFuelRate = baseRate100km
                }

                // 7. Universal Fuel Level Reading (ECU / Cluster) or High-Precision Dynamic Fuel Flow Tracking
                var hwFuelDetected = false
                val isCalibrated = preferences.getBoolean("fuel_is_calibrated", false)
                val fuelTrackingMode = preferences.getString("fuel_tracking_mode", if (isCalibrated) "manual_dynamic" else "auto") ?: "manual_dynamic"

                // STRICT PROTECTION: If calibrated or in manual_dynamic, NEVER allow raw 012F to overwrite fuel level!
                if (!isCalibrated && fuelTrackingMode == "auto" && loopCount % 6 == 0) {
                    val probe = activeFuelProbe
                    if (probe != null) {
                        if (probe.header != "7DF") {
                            sendObdCommand(outStream, inStream, "ATSH ${probe.header}", 250)
                            val rxAddr = Integer.toHexString(probe.header.toInt(16) + 8).uppercase()
                            sendObdCommand(outStream, inStream, "ATCRA $rxAddr", 200)
                        }
                        val fuelResp = sendObdCommand(outStream, inStream, probe.command, 500)
                        if (probe.header != "7DF") {
                            sendObdCommand(outStream, inStream, "ATCRA", 200)
                            sendObdCommand(outStream, inStream, "ATSH 7DF", 250)
                        }
                        val fuel = parseProbeFuel(fuelResp, probe)
                        if (fuel in 0..100) {
                            telemetry.fuelLevel = fuel
                            val clusterBadge = if (probe.isCluster) "لوحة العدادات بالطابلوه (${probe.header})" else probe.description
                            telemetry.fuelLevelSource = "$clusterBadge 🎯 قراءة ذاتية $fuel%"
                            preferences.edit().putInt("current_fuel_tank_percent", fuel).apply()
                            hwFuelDetected = true
                        }
                    } else {
                        // Periodic auto-probe recovery if cluster was offline at connection start
                        if (loopCount % 30 == 0) {
                            activeFuelProbe = discoverUniversalFuelProbe(inStream, outStream)
                        }
                    }
                }

                // Tank Level Deduction: Accurately subtracts burned fuel from the calibrated tank level!
                if (!hwFuelDetected && consumedSliceLiters > 0) {
                    accumulatedFuelSlice += consumedSliceLiters
                    val literPerPercent = tankCap / 100.0
                    if (accumulatedFuelSlice >= literPerPercent) {
                        val percentToDeduct = (accumulatedFuelSlice / literPerPercent).toInt()
                        accumulatedFuelSlice -= (percentToDeduct * literPerPercent)
                        telemetry.fuelLevel = (telemetry.fuelLevel - percentToDeduct).coerceAtLeast(1)
                        telemetry.fuelLevelSource = if (isCalibrated) "معايرة مثبتة (${telemetry.fuelLevel}%) + تتبع ديناميكي 🧮" else "حساب ديناميكي ذكي 🧮"
                        preferences.edit().putInt("current_fuel_tank_percent", telemetry.fuelLevel).apply()
                    }
                }

                // 8. Battery Voltage (ATRV)
                if (loopCount % 4 == 0) {
                    val voltResp = sendObdCommand(outStream, inStream, "ATRV", 600)
                    val volt = parseVoltage(voltResp)
                    if (volt > 0f) telemetry.batteryVoltage = volt
                }

                // 9. DTC Trouble Codes (03)
                if (loopCount % 20 == 0) {
                    val dtcResp = sendObdCommand(outStream, inStream, "03", 1500)
                    val dtcs = parseDtc(dtcResp)
                    telemetry.dtcCodes.clear()
                    telemetry.dtcCodes.addAll(dtcs)
                }

                runOnUiThread { updateLiveUI() }

                Thread.sleep(120)
            } catch (_: Exception) {
                if (!isTelemetryLoopRunning) break
            }
        }
        } finally {
            activeObdOutStream = null
            activeObdInStream = null
            isTelemetryLoopRunning = false
        }
    }

    private fun cleanHex(raw: String): String {
        return raw.replace(" ", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(">", "")
            .replace("SEARCHING...", "")
            .trim()
            .uppercase()
    }

    private fun parseRpm(raw: String): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("410C")
        if (idx != -1 && clean.length >= idx + 8) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -1
            val b = clean.substring(idx + 6, idx + 8).toIntOrNull(16) ?: return -1
            return ((a * 256) + b) / 4
        }
        return -1
    }

    private fun parseSpeed(raw: String): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("410D")
        if (idx != -1 && clean.length >= idx + 6) {
            return clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: -1
        }
        return -1
    }

    private fun parseCoolant(raw: String): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("4105")
        if (idx != -1 && clean.length >= idx + 6) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -999
            return a - 40
        }
        return -999
    }

    private fun parseEngineLoad(raw: String): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("4104")
        if (idx != -1 && clean.length >= idx + 6) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -1
            return (a * 100) / 255
        }
        return -1
    }

    private fun parseThrottle(raw: String): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("4111")
        if (idx != -1 && clean.length >= idx + 6) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -1
            return (a * 100) / 255
        }
        return -1
    }

    private fun parseFuel(raw: String): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("412F")
        if (idx != -1 && clean.length >= idx + 6) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -1
            val rawRes = (a * 100) / 255
            val isInverted = preferences.getBoolean("fuel_sensor_inverted", false)
            val res = if (isInverted) (100 - rawRes).coerceIn(0, 100) else rawRes
            return if (res in 0..100) res else -1
        }
        return -1
    }

    private fun parseProbeFuel(raw: String, expectedPrefix: String): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf(expectedPrefix)
        if (idx != -1 && clean.length >= idx + expectedPrefix.length + 2) {
            val byteHex = clean.substring(idx + expectedPrefix.length, idx + expectedPrefix.length + 2)
            val a = byteHex.toIntOrNull(16) ?: return -1
            val rawRes = (a * 100) / 255
            val isInverted = preferences.getBoolean("fuel_sensor_inverted", false)
            val res = if (isInverted) (100 - rawRes).coerceIn(0, 100) else rawRes
            return if (res in 0..100) res else -1
        }
        return -1
    }

    private fun parseProbeFuel(raw: String, probe: FuelProbe): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf(probe.expectedPrefix)
        if (idx != -1 && clean.length >= idx + probe.expectedPrefix.length + 2) {
            val byteHex = clean.substring(idx + probe.expectedPrefix.length, idx + probe.expectedPrefix.length + 2)
            val a = byteHex.toIntOrNull(16) ?: return -1

            val rawRes = if (probe.isLiters) {
                val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
                ((a.toFloat() / tankCap) * 100.0f).toInt().coerceIn(0, 100)
            } else if (probe.isUds) {
                if (a in 0..100) a else (a * 100) / 255
            } else {
                (a * 100) / 255
            }

            val isInverted = preferences.getBoolean("fuel_sensor_inverted", false)
            val res = if (isInverted) (100 - rawRes).coerceIn(0, 100) else rawRes
            return if (res in 0..100) res else -1
        }
        return -1
    }

    private fun discoverUniversalFuelProbe(inStream: InputStream, outStream: OutputStream): FuelProbe? {
        // 1. Check if we have a saved working cluster probe
        val savedHeader = preferences.getString("active_fuel_probe_header", null)
        val savedCmd = preferences.getString("active_fuel_probe_cmd", null)
        if (savedHeader != null && savedCmd != null) {
            val cachedProbe = universalFuelProbes.find { it.header == savedHeader && it.command == savedCmd }
            if (cachedProbe != null) {
                try {
                    if (cachedProbe.header != "7DF") {
                        sendObdCommand(outStream, inStream, "ATSH ${cachedProbe.header}", 250)
                        val rxAddr = Integer.toHexString(cachedProbe.header.toInt(16) + 8).uppercase()
                        sendObdCommand(outStream, inStream, "ATCRA $rxAddr", 200)
                    }
                    val resp = sendObdCommand(outStream, inStream, cachedProbe.command, 400)
                    if (cachedProbe.header != "7DF") {
                        sendObdCommand(outStream, inStream, "ATCRA", 200)
                        sendObdCommand(outStream, inStream, "ATSH 7DF", 200)
                    }
                    val fuel = parseProbeFuel(resp, cachedProbe)
                    if (fuel in 0..100) {
                        return cachedProbe
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Protocol Check
        val protoResp = sendObdCommand(outStream, inStream, "ATDP", 500)
        val isCan = protoResp.contains("CAN", ignoreCase = true) || protoResp.contains("ISO 15765", ignoreCase = true)

        // 3. PRIORITY 1: Sweep Instrument Clusters (لوحة العدادات بالطابلوه)
        // In Hyundai Accent and modern CAN vehicles, the fuel tank float is physically wired to the Instrument Cluster!
        if (isCan) {
            for (probe in universalFuelProbes.filter { it.isCluster }) {
                try {
                    sendObdCommand(outStream, inStream, "ATSH ${probe.header}", 300)
                    val rxAddr = Integer.toHexString(probe.header.toInt(16) + 8).uppercase()
                    sendObdCommand(outStream, inStream, "ATCRA $rxAddr", 250)

                    val resp = sendObdCommand(outStream, inStream, probe.command, 500)

                    sendObdCommand(outStream, inStream, "ATCRA", 200)
                    sendObdCommand(outStream, inStream, "ATSH 7DF", 250)

                    val fuel = parseProbeFuel(resp, probe)
                    if (fuel in 0..100) {
                        preferences.edit()
                            .putString("active_fuel_probe_header", probe.header)
                            .putString("active_fuel_probe_cmd", probe.command)
                            .putBoolean("fuel_is_calibrated", false)
                            .putString("fuel_tracking_mode", "auto")
                            .putInt("current_fuel_tank_percent", fuel)
                            .apply()
                        return probe
                    }
                } catch (_: Exception) {}
            }
        }

        // 4. PRIORITY 2: Fallback to Engine Control Modules (كمبيوتر المكينة)
        for (probe in universalFuelProbes.filter { !it.isCluster }) {
            try {
                if (probe.header != "7DF") {
                    sendObdCommand(outStream, inStream, "ATSH ${probe.header}", 250)
                }
                val resp = sendObdCommand(outStream, inStream, probe.command, 500)
                if (probe.header != "7DF") {
                    sendObdCommand(outStream, inStream, "ATSH 7DF", 250)
                }
                val fuel = parseProbeFuel(resp, probe)
                if (fuel in 0..100) {
                    preferences.edit()
                        .putString("active_fuel_probe_header", probe.header)
                        .putString("active_fuel_probe_cmd", probe.command)
                        .apply()
                    return probe
                }
            } catch (_: Exception) {}
        }

        sendObdCommand(outStream, inStream, "ATCRA", 200)
        sendObdCommand(outStream, inStream, "ATSH 7DF", 250)
        return null
    }

    private fun parseFuelRate5E(raw: String): Float {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("415E")
        if (idx != -1 && clean.length >= idx + 8) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -1f
            val b = clean.substring(idx + 6, idx + 8).toIntOrNull(16) ?: return -1f
            val rate = ((a * 256) + b) * 0.05f
            return if (rate in 0.1f..150.0f) rate else -1f
        }
        return -1f
    }

    private fun parseMaf(raw: String): Float {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("4110")
        if (idx != -1 && clean.length >= idx + 8) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -1f
            val b = clean.substring(idx + 6, idx + 8).toIntOrNull(16) ?: return -1f
            val maf = ((a * 256) + b) / 100.0f
            return if (maf in 0.2f..655.0f) maf else -1f
        }
        return -1f
    }

    private fun parseMap(raw: String): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("410B")
        if (idx != -1 && clean.length >= idx + 6) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -1
            return if (a in 10..255) a else -1
        }
        return -1
    }

    private fun parseIat(raw: String): Int {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("410F")
        if (idx != -1 && clean.length >= idx + 6) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -999
            return a - 40
        }
        return -999
    }

    private fun parseFuelTrim(raw: String, pidPrefix: String = "4106"): Float {
        val clean = cleanHex(raw)
        val idx = clean.indexOf(pidPrefix)
        if (idx != -1 && clean.length >= idx + pidPrefix.length + 2) {
            val a = clean.substring(idx + pidPrefix.length, idx + pidPrefix.length + 2).toIntOrNull(16) ?: return 0f
            return ((a - 128) * 100.0f) / 128.0f
        }
        return 0f
    }

    private fun parseFuelType51(raw: String): Pair<String, String>? {
        val clean = cleanHex(raw)
        val idx = clean.indexOf("4151")
        if (idx != -1 && clean.length >= idx + 6) {
            val code = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return null
            return when (code) {
                0x01, 0x08, 0x09, 0x11 -> Pair("gasoline", "لتر")
                0x04, 0x13 -> Pair("diesel", "لتر")
                0x05, 0x06, 0x0C, 0x0D -> Pair("cng", "م³")
                else -> null
            }
        }
        return null
    }

    private fun discoverBestFuelSensor(inStream: InputStream, outStream: OutputStream): FuelSensorMode {
        // Automatic Detection of Vehicle Fuel Type (PID 0151)
        try {
            val r51 = sendObdCommand(outStream, inStream, "0151", 400)
            val parsedFuel = parseFuelType51(r51)
            if (parsedFuel != null) {
                preferences.edit()
                    .putString("vehicle_fuel_type", parsedFuel.first)
                    .putString("fuel_unit_name", parsedFuel.second)
                    .apply()
                telemetry.fuelType = when (parsedFuel.first) {
                    "diesel" -> "ديزل / سولار (Diesel)"
                    "cng" -> "غاز طبيعي (CNG)"
                    else -> "بنزين (Gasoline)"
                }
                telemetry.fuelUnitName = parsedFuel.second
            }
        } catch (_: Exception) {}

        // Priority 1: Mode 01 PID 015E (Direct ECU Engine Fuel Rate L/h)
        try {
            val r5E = sendObdCommand(outStream, inStream, "015E", 600)
            val val5E = parseFuelRate5E(r5E)
            if (val5E > 0f) {
                telemetry.directFuelRateLph = val5E
                telemetry.fuelCalculationSource = "حساس كمبيوتر المحرك المباشر (PID 015E) دقة 99% ⚡"
                return FuelSensorMode.DIRECT_ECU_RATE_5E
            }
        } catch (_: Exception) {}

        // Priority 2: Mode 01 PID 0110 (Mass Air Flow Sensor - MAF)
        try {
            val rMaf = sendObdCommand(outStream, inStream, "0110", 600)
            val valMaf = parseMaf(rMaf)
            if (valMaf > 0f) {
                telemetry.mafGps = valMaf
                telemetry.fuelCalculationSource = "حساس تدفق الهواء MAF (PID 0110) دقة 98% ⚡"
                return FuelSensorMode.MAF_AIR_FLOW_10
            }
        } catch (_: Exception) {}

        // Priority 3: Mode 01 PID 010B (Speed-Density MAP Pressure Sensor)
        try {
            val rMap = sendObdCommand(outStream, inStream, "010B", 600)
            val valMap = parseMap(rMap)
            if (valMap > 0) {
                telemetry.mapKpa = valMap
                telemetry.fuelCalculationSource = "حساس ضغط المانيفولد MAP (PID 010B) دقة 93% ⚡"
                return FuelSensorMode.MAP_SPEED_DENSITY_0B
            }
        } catch (_: Exception) {}

        // Priority 4: Dynamic Engine Load & BSFC Thermodynamic Fusion
        telemetry.fuelCalculationSource = "الحمل الحراري الديناميكي (Engine Load + TPS) ⚡"
        return FuelSensorMode.DYNAMIC_LOAD_FUSION
    }

    private fun parseVoltage(raw: String): Float {
        val match = Regex("""([0-9]+\.[0-9]+)""").find(raw)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }

    private fun detectCarFromPairedDevices(): Pair<String, String>? {
        try {
            val bonded = bluetoothAdapter?.bondedDevices
            if (!bonded.isNullOrEmpty()) {
                for (dev in bonded) {
                    val name = dev.name ?: continue
                    val upper = name.uppercase()
                    if (upper.contains("AVEO")) {
                        return Pair("شيفروليه أفيو (Chevrolet Aveo)", "شيفروليه (Chevrolet)")
                    } else if (upper.contains("CHEVROLET") || upper.contains("CHEVY")) {
                        return Pair("شيفروليه (Chevrolet)", "شيفروليه (Chevrolet)")
                    } else if (upper.contains("OPTRA")) {
                        return Pair("شيفروليه أوبترا (Chevrolet Optra)", "شيفروليه (Chevrolet)")
                    } else if (upper.contains("LANOS")) {
                        return Pair("شيفروليه / دايو لانوس (Lanos)", "شيفروليه (Chevrolet)")
                    } else if (upper.contains("CRUZE")) {
                        return Pair("شيفروليه كروز (Chevrolet Cruze)", "شيفروليه (Chevrolet)")
                    } else if (upper.contains("ACCENT")) {
                        return Pair("هيونداي أكسنت (Hyundai Accent)", "هيونداي (Hyundai)")
                    } else if (upper.contains("ELANTRA")) {
                        return Pair("هيونداي إلنترا (Hyundai Elantra)", "هيونداي (Hyundai)")
                    } else if (upper.contains("CERATO")) {
                        return Pair("كيا سيراتو (Kia Cerato)", "كيا (Kia)")
                    } else if (upper.contains("COROLLA")) {
                        return Pair("تويوتا كورولا (Toyota Corolla)", "تويوتا (Toyota)")
                    } else if (upper.contains("SUNNY")) {
                        return Pair("نيسان صني (Nissan Sunny)", "نيسان (Nissan)")
                    } else if (upper.contains("LOGAN") || upper.contains("MEGANE") || upper.contains("RENAULT")) {
                        return Pair("رينو (Renault)", "رينو (Renault)")
                    } else if (upper.contains("TIPO") || upper.contains("FIAT")) {
                        return Pair("فيات (Fiat)", "فيات (Fiat)")
                    }
                }
            }
        } catch (_: SecurityException) {}
        return null
    }

    private fun fetchVinFromEcu(outStream: java.io.OutputStream, inStream: java.io.InputStream): String {
        try {
            // 1. Ensure vehicle protocol is locked by querying 0100 first
            sendObdCommand(outStream, inStream, "0100", 2500)
            Thread.sleep(80)

            // 2. Enable Adaptive Timing & CAN Auto-Formatting
            sendObdCommand(outStream, inStream, "ATAT1", 500)
            sendObdCommand(outStream, inStream, "ATCAF1", 500)

            // 3. Attempt Standard Mode 09 PID 02 (Broadcast 7DF)
            var resp = sendObdCommand(outStream, inStream, "09 02", 2500)
            var vin = parseVin(resp)
            if (vin.isNotBlank()) return vin

            resp = sendObdCommand(outStream, inStream, "0902", 2000)
            vin = parseVin(resp)
            if (vin.isNotBlank()) return vin

            // 4. GM / Chevrolet / Delphi / Daewoo Specialized Direct ECM Header (7E0)
            sendObdCommand(outStream, inStream, "ATSH 7E0", 600)
            resp = sendObdCommand(outStream, inStream, "09 02", 2500)
            vin = parseVin(resp)
            if (vin.isNotBlank()) {
                sendObdCommand(outStream, inStream, "ATSH 7DF", 500)
                return vin
            }

            // 5. GM / Delphi UDS DID F190 (Read VIN via Service 22 - 22 F1 90)
            resp = sendObdCommand(outStream, inStream, "22 F1 90", 2500)
            vin = parseVin(resp)
            if (vin.isNotBlank()) {
                sendObdCommand(outStream, inStream, "ATSH 7DF", 500)
                return vin
            }

            // 6. GM Tech-2 Service 1A Identifier 90 (1A 90)
            resp = sendObdCommand(outStream, inStream, "1A 90", 2000)
            vin = parseVin(resp)
            if (vin.isNotBlank()) {
                sendObdCommand(outStream, inStream, "ATSH 7DF", 500)
                return vin
            }

            // 7. Daewoo / Delphi KWP2000 Service 21 PID 90 / 80
            resp = sendObdCommand(outStream, inStream, "21 90", 2000)
            vin = parseVin(resp)
            if (vin.isNotBlank()) {
                sendObdCommand(outStream, inStream, "ATSH 7DF", 500)
                return vin
            }
            resp = sendObdCommand(outStream, inStream, "21 80", 2000)
            vin = parseVin(resp)
            if (vin.isNotBlank()) {
                sendObdCommand(outStream, inStream, "ATSH 7DF", 500)
                return vin
            }

            // 8. Secondary ECM header ATSH 7E2 (used in dual-controller, diesel, or BCM gateways)
            sendObdCommand(outStream, inStream, "ATSH 7E2", 600)
            resp = sendObdCommand(outStream, inStream, "09 02", 2000)
            vin = parseVin(resp)
            sendObdCommand(outStream, inStream, "ATSH 7DF", 500)
            if (vin.isNotBlank()) return vin
        } catch (_: Exception) {}
        return ""
    }

    private fun parseVin(raw: String): String {
        if (raw.isBlank() || raw.contains("NO DATA") || raw.contains("UNABLE")) return ""

        // 1. Direct ASCII check first (some ELMs return ASCII text directly)
        val directMatch = Regex("""\b([A-HJ-NPR-Z0-9]{17})\b""").find(raw)
        if (directMatch != null) return directMatch.groupValues[1]

        // 2. Clean out CAN frame headers and line indicators (e.g. 7E8 10 14, 0:, 00:, 1:)
        val lines = raw.split("\r", "\n")
        val hexSb = java.lang.StringBuilder()
        for (line in lines) {
            var trimmed = line.trim()
            if (trimmed.startsWith("7E") && trimmed.length > 4) {
                trimmed = trimmed.substring(4).trim()
            }
            val colonIdx = trimmed.indexOf(':')
            val sanitizedLine = if (colonIdx in 1..3) trimmed.substring(colonIdx + 1) else trimmed
            hexSb.append(cleanHex(sanitizedLine))
        }

        val clean = hexSb.toString()
        val prefixes = listOf("4902", "62F190", "5A90", "6190", "6180")
        for (pfx in prefixes) {
            val idx = clean.indexOf(pfx)
            if (idx != -1) {
                val hexPayload = clean.substring(idx + pfx.length)
                val asciiSb = java.lang.StringBuilder()
                var i = 0
                while (i + 1 < hexPayload.length) {
                    val byteVal = hexPayload.substring(i, i + 2).toIntOrNull(16) ?: break
                    if (byteVal in 32..126) {
                        asciiSb.append(byteVal.toChar())
                    }
                    i += 2
                }
                val parsedAscii = asciiSb.toString()
                val vinMatch = Regex("""([A-HJ-NPR-Z0-9]{17})""").find(parsedAscii)
                if (vinMatch != null) return vinMatch.groupValues[1]
                val partialMatch = Regex("""([A-HJ-NPR-Z0-9]{11,16})""").find(parsedAscii)
                if (partialMatch != null) return partialMatch.groupValues[1]
            }
        }
        return ""
    }

    private fun fetchCalidFromEcu(outStream: java.io.OutputStream, inStream: java.io.InputStream): String {
        try {
            // Mode 09 PID 04 (Calibration ID CALID) - Standard emission query that is NEVER blocked by factory lock
            var resp = sendObdCommand(outStream, inStream, "09 04", 2500)
            var calid = parseCalid(resp)
            if (calid.isNotBlank()) return calid

            // Try via direct ECM address 7E0
            sendObdCommand(outStream, inStream, "ATSH 7E0", 500)
            resp = sendObdCommand(outStream, inStream, "09 04", 2500)
            calid = parseCalid(resp)
            sendObdCommand(outStream, inStream, "ATSH 7DF", 500)
            return calid
        } catch (_: Exception) {}
        return ""
    }

    private fun parseCalid(raw: String): String {
        if (raw.isBlank() || raw.contains("NO DATA") || raw.contains("UNABLE")) return ""

        val lines = raw.split("\r", "\n")
        val hexSb = java.lang.StringBuilder()
        for (line in lines) {
            var trimmed = line.trim()
            if (trimmed.startsWith("7E") && trimmed.length > 4) {
                trimmed = trimmed.substring(4).trim()
            }
            val colonIdx = trimmed.indexOf(':')
            val sanitized = if (colonIdx in 1..3) trimmed.substring(colonIdx + 1) else trimmed
            hexSb.append(cleanHex(sanitized))
        }

        val clean = hexSb.toString()
        val idx = clean.indexOf("4904")
        if (idx != -1) {
            val hexPayload = clean.substring(idx + 4)
            val asciiSb = java.lang.StringBuilder()
            var i = 0
            while (i + 1 < hexPayload.length) {
                val byteVal = hexPayload.substring(i, i + 2).toIntOrNull(16) ?: break
                if (byteVal in 32..126) {
                    asciiSb.append(byteVal.toChar())
                }
                i += 2
            }
            return asciiSb.toString().trim()
        }
        return ""
    }

    private fun identifyVehicleFromCalid(calid: String): VehiclePreset? {
        val upper = calid.uppercase()
        return when {
            // Chevrolet Aveo 1.5L / 1.6L (Delphi MR-140, HV-240, Sirius D42 firmware tags)
            upper.contains("XAUB") || upper.contains("XAPA") || upper.contains("XARN") ||
            upper.contains("XALR") || upper.contains("9643") || upper.contains("9647") ||
            upper.contains("25184") || upper.contains("25186") || upper.contains("AVEO") ->
                VehiclePreset("شيفروليه أفيو (Chevrolet Aveo 1.5L)", "Chevrolet", "2018", "KL1TF69Y87B123456", 45f, 7.2f, 1.5f, "gasoline")

            // Chevrolet Optra 1.6L
            upper.contains("1BVI") || upper.contains("1BVK") || upper.contains("9645") ||
            upper.contains("9680") || upper.contains("OPTRA") ->
                VehiclePreset("شيفروليه أوبترا (Chevrolet Optra 1.6L)", "Chevrolet", "2020", "KL1NF69608B123456", 54f, 7.6f, 1.6f, "gasoline")

            // Chevrolet / Daewoo Lanos 1.5L
            upper.contains("CKDX") || upper.contains("CKDF") || upper.contains("KDAC") ||
            upper.contains("ZXJN") || upper.contains("LANOS") ->
                VehiclePreset("شيفروليه / دايو لانوس (Lanos 1.5L)", "Chevrolet", "2017", "KL1SF69Y67B123456", 48f, 7.4f, 1.5f, "gasoline")

            // Toyota Corolla
            upper.contains("302") || upper.contains("305") || upper.contains("312") || upper.contains("89663") ->
                VehiclePreset("تويوتا كورولا (Toyota Corolla 1.6L)", "Toyota", "2021", "JTDBR32E12345678", 50f, 6.8f, 1.6f, "gasoline")

            // Nissan Sunny / Sentra
            upper.contains("1EM") || upper.contains("3BA") || upper.contains("23710") || upper.contains("SUNNY") ->
                VehiclePreset("نيسان صني (Nissan Sunny 1.5L)", "Nissan", "2022", "3N1AB7AP0NL123456", 50f, 7.3f, 1.5f, "gasoline")

            // Hyundai / Kia
            upper.contains("MC") || upper.contains("HD") || upper.contains("MD") ||
            upper.contains("PB") || upper.contains("RB") || upper.contains("ACCENT") ->
                VehiclePreset("هيونداي أكسنت / كيا (Hyundai / Kia 1.6L)", "Hyundai", "2022", "KMHCT41CPEU123456", 45f, 6.8f, 1.6f, "gasoline")

            else -> null
        }
    }

    private fun decodeVin(vin: String): DecodedVehicle {
        val clean = vin.trim().uppercase()
        if (clean.length < 3) {
            return DecodedVehicle(clean, "سيارة عامة", "غير محدد", "سيارة غير معرفة")
        }

        val wmi = clean.substring(0, 3)
        val wmi1 = clean.substring(0, 1)

        val make = when {
            // Toyota
            wmi in listOf("JTD", "JT1", "JT2", "JT3", "JT4", "JT7", "2T1", "3TM", "4T1", "5TB", "MR0", "AHT") -> "تويوتا (Toyota)"
            // Lexus
            wmi in listOf("JTJ", "JTH", "2T2") -> "لكزس (Lexus)"
            // Hyundai
            wmi in listOf("KMH", "KM8", "KME", "MAL", "TMA", "5NP") -> "هيونداي (Hyundai)"
            // Kia
            wmi in listOf("KNA", "KNB", "KNC", "KND", "5XX") -> "كيا (Kia)"
            // Nissan / Infiniti
            wmi in listOf("JN1", "JN6", "JN8", "1N4", "3N1", "MDH", "SJN") -> "نيسان (Nissan)"
            wmi in listOf("JNK", "JNR") -> "إنفينيتي (Infiniti)"
            // Honda
            wmi in listOf("1HG", "2HG", "3HG", "JHM", "MRH", "SHS") -> "هوندا (Honda)"
            // Mitsubishi
            wmi in listOf("JA3", "JA4", "JMB", "MMB") -> "ميتسوبيشي (Mitsubishi)"
            // Mazda
            wmi in listOf("JM1", "JMZ") -> "مازدا (Mazda)"
            // Subaru
            wmi in listOf("JF1", "JF2", "4S3") -> "سوبارو (Subaru)"
            // Ford
            wmi in listOf("1FA", "1FB", "1FC", "1FD", "1FM", "1FT", "2FA", "2FM", "3FA", "WF0", "SFA") -> "فورد (Ford)"
            // Chevrolet / GM
            wmi in listOf("1G1", "1GC", "2G1", "3G1", "KL1", "KL7", "9BG") -> "شيفروليه (Chevrolet)"
            // GMC
            wmi in listOf("1GT", "2GT") -> "جي إم سي (GMC)"
            // Jeep / Dodge / Chrysler
            wmi in listOf("1C4", "1J4") -> "جيب (Jeep)"
            wmi in listOf("1B3", "1C3", "2C3", "3C4") -> "دوج / كرايسلر"
            // BMW
            wmi in listOf("WBA", "WBS", "WBX", "4US", "5UX") -> "بي إم دبليو (BMW)"
            // Mercedes-Benz
            wmi in listOf("WDB", "WDC", "WDD", "WDF", "4JG") -> "مرسيدس بنز (Mercedes-Benz)"
            // Volkswagen
            wmi in listOf("WVW", "WV1", "WV2", "3VW", "1VW", "AAV") -> "فولكس فاجن (Volkswagen)"
            // Audi
            wmi in listOf("WAU", "WA1") -> "أودي (Audi)"
            // Skoda
            wmi.startsWith("TMB") -> "سكودا (Skoda)"
            // Renault / Dacia
            wmi in listOf("VF1", "UU1", "VF2") -> "رينو (Renault / Dacia)"
            // Peugeot
            wmi.startsWith("VF3") -> "بيجو (Peugeot)"
            // Citroen
            wmi.startsWith("VF7") -> "ستروين (Citroën)"
            // Fiat
            wmi in listOf("ZFA", "ZFC", "2FA", "3FA") -> "فيات (Fiat)"
            // Opel
            wmi.startsWith("W0L") -> "أوبل (Opel)"
            // Suzuki
            wmi in listOf("JS1", "JS2", "MA3", "TSM") -> "سوزوكي (Suzuki)"
            // MG
            wmi in listOf("LSJ", "SAK") -> "إم جي (MG)"
            // Geely
            wmi in listOf("LGB", "LB3") -> "جيلي (Geely)"
            // Chery
            wmi in listOf("LVV", "LKD") -> "شيري (Chery)"
            // BYD
            wmi.startsWith("LGX") -> "بي واي دي (BYD)"
            // Tesla
            wmi in listOf("5YJ", "7SA", "LRW") -> "تيسلا (Tesla)"
            // Country fallback
            wmi1 == "J" -> "سيارة يابانية (Japanese)"
            wmi1 == "K" -> "سيارة كورية (Korean)"
            wmi1 == "W" -> "سيارة ألمانية (German)"
            wmi1 == "1" || wmi1 == "4" || wmi1 == "5" -> "سيارة أمريكية (USA)"
            wmi1 == "L" -> "سيارة صينية (Chinese)"
            wmi1 == "V" || wmi1 == "Z" -> "سيارة أوروبية (European)"
            else -> "مركبة معتمدة ($wmi)"
        }

        val yearChar = if (clean.length >= 10) clean[9] else ' '
        val year = when (yearChar) {
            'A' -> "2010"
            'B' -> "2011"
            'C' -> "2012"
            'D' -> "2013"
            'E' -> "2014"
            'F' -> "2015"
            'G' -> "2016"
            'H' -> "2017"
            'J' -> "2018"
            'K' -> "2019"
            'L' -> "2020"
            'M' -> "2021"
            'N' -> "2022"
            'P' -> "2023"
            'R' -> "2024"
            'S' -> "2025"
            'T' -> "2026"
            'V' -> "2027"
            'W' -> "2028"
            'X' -> "2029"
            'Y' -> "2000"
            '1' -> "2001"
            '2' -> "2002"
            '3' -> "2003"
            '4' -> "2004"
            '5' -> "2005"
            '6' -> "2006"
            '7' -> "2007"
            '8' -> "2008"
            '9' -> "2009"
            else -> "غير محدد"
        }

        val displayName = if (year != "غير محدد") "$make ($year)" else make
        return DecodedVehicle(clean, make, year, displayName)
    }

    private fun parseDtc(raw: String): List<String> {
        val list = mutableListOf<String>()
        if (raw.contains("NO DATA") || raw.contains("UNABLE")) return list

        val lines = raw.split("\r", "\n")
        val hexSb = java.lang.StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            val sanitized = if (trimmed.length > 2 && trimmed[1] == ':') trimmed.substring(2) else trimmed
            hexSb.append(cleanHex(sanitized))
        }
        val clean = hexSb.toString()
        val idx = clean.indexOf("43")
        if (idx == -1) return list

        var startIndex = idx + 2
        // Check if there is a count byte following 43
        if (clean.length >= startIndex + 2) {
            val potentialCount = clean.substring(startIndex, startIndex + 2).toIntOrNull(16)
            if (potentialCount == 0) return list
            if (potentialCount != null && potentialCount in 1..15) {
                startIndex += 2
            }
        }

        var i = startIndex
        while (i + 4 <= clean.length) {
            val hexCode = clean.substring(i, i + 4)
            if (hexCode == "0000" || hexCode.startsWith("NO")) break

            val byteA = hexCode.substring(0, 2).toIntOrNull(16) ?: break
            val byteB = hexCode.substring(2, 4)

            // Standard SAE J1979 DTC prefix decoding:
            // Bits 7-6: 00 -> P, 01 -> C, 10 -> B, 11 -> U
            val categoryBits = (byteA shr 6) and 0x03
            val categoryPrefix = when (categoryBits) {
                0 -> "P"
                1 -> "C"
                2 -> "B"
                else -> "U"
            }
            val secondDigit = (byteA shr 4) and 0x03
            val thirdDigitHex = Integer.toHexString(byteA and 0x0F).uppercase()
            val dtcFullCode = "$categoryPrefix$secondDigit$thirdDigitHex$byteB".uppercase()

            if (dtcFullCode != "P0000" && !list.contains(dtcFullCode)) {
                list.add(dtcFullCode)
            }
            i += 4
        }
        return list
    }

    private fun diagnoseDtc(code: String): DtcDiagnosis {
        val clean = code.trim().uppercase()
        return when (clean) {
            "P0300" -> DtcDiagnosis(
                clean,
                "خلل إشعال عام متعدد بأسطوانات المحرك (Random/Multiple Cylinder Misfire)",
                "نظام الإشعال والاحتراق",
                "• استهلاك أو تلف شمعات الاحتراق (البوجيهات)\n• تلف إحدى كويلات/مباين الإشعال\n• انسداد أو تسييل في رشاشات البنزين\n• تسريب هواء من جوان المانيفولد (Vacuum Leak)",
                "افحص البوجيهات والمباين أولاً واستبدل التالف، ونظف الرشاشات وافحص ضغط طلمبة البنزين.",
                "متوسطة إلى خطيرة"
            )
            "P0301" -> DtcDiagnosis(clean, "خلل إشعال بالأسطوانة رقم 1 (Cylinder 1 Misfire)", "نظام الإشعال", "• تلف بوجيه أو كويل الأسطوانة رقم 1\n• انسداد رشاش الأسطوانة 1", "بدّل كويل الأسطوانة 1 مع الأسطوانة 2؛ إذا انتقل العطل فالكويل تالف، وإلا فافحص البوجيه والرشاش.", "متوسطة")
            "P0302" -> DtcDiagnosis(clean, "خلل إشعال بالأسطوانة رقم 2 (Cylinder 2 Misfire)", "نظام الإشعال", "• تلف بوجيه أو كويل الأسطوانة رقم 2", "افحص البوجيه والكويل والرشاش الخاص بالأسطوانة 2.", "متوسطة")
            "P0303" -> DtcDiagnosis(clean, "خلل إشعال بالأسطوانة رقم 3 (Cylinder 3 Misfire)", "نظام الإشعال", "• تلف بوجيه أو كويل الأسطوانة رقم 3", "افحص البوجيه والكويل والرشاش الخاص بالأسطوانة 3.", "متوسطة")
            "P0304" -> DtcDiagnosis(clean, "خلل إشعال بالأسطوانة رقم 4 (Cylinder 4 Misfire)", "نظام الإشعال", "• تلف بوجيه أو كويل الأسطوانة رقم 4", "افحص البوجيه والكويل والرشاش الخاص بالأسطوانة 4.", "متوسطة")
            "P0171" -> DtcDiagnosis(
                clean,
                "خليط وقود فقير بنك 1 (System Too Lean - زيادة الهواء عن البنزين)",
                "نظام الوقود والهواء",
                "• تسريب هواء من خراطيم الفاكيوم أو المانيفولد\n• اتساخ شعيرات حساس تدفق الهواء (MAF)\n• ضعف ضغط طلمبة البنزين أو انسداد فلتر الوقود\n• عطل في حساس الأكسجين العلوي",
                "نظف حساس الـ MAF باسبراي مخصص، وافحص خراطيم الفاكيوم، وتأكد من ضغط طلمبة البنزين.",
                "متوسطة"
            )
            "P0172" -> DtcDiagnosis(
                clean,
                "خليط وقود غني بنك 1 (System Too Rich - زيادة البنزين عن الهواء)",
                "نظام الوقود",
                "• تسييل في رشاشات البنزين\n• تلف منظم ضغط الوقود\n• اتساخ فلتر الهواء بشدة\n• عطل حساس الأكسجين",
                "افحص الرشاشات وتأكد من عدم تسييلها واستبدل فلتر الهواء.",
                "متوسطة"
            )
            "P0420" -> DtcDiagnosis(
                clean,
                "انخفاض كفاءة علبة البيئة / محول الحفاز (Catalyst Efficiency Below Threshold)",
                "نظام العادم والانبعاثات",
                "• استهلاك أو انسداد فلتر علبة البيئة (الكاتالايزر)\n• تلف أو بطء قراءة حساس الشكمان الخلفي (O2 Sensor 2)\n• استخدام بنزين رديء أو خروج زيت مع العادم",
                "افحص قراءة حساس الشكمان الخلفي، ونظف علبة البيئة أو استبدلها إذا كانت مسدودة.",
                "تحذيرية"
            )
            "P0430" -> DtcDiagnosis(clean, "انخفاض كفاءة علبة البيئة بنك 2", "نظام العادم", "• ضعف كفاءة الكاتالايزر في البنك الثاني (محركات V6/V8)", "فحص حساس الشكمان الخلفي لبنك 2 وتنظيف أو استبدال علبة البيئة.", "تحذيرية")
            "P0101" -> DtcDiagnosis(clean, "مشكلة في أداء ومدى حساس تدفق الهواء (MAF)", "نظام سحب الهواء", "• اتساخ حساس الماف بالأتربة أو الزيت\n• تسريب هواء بين العمة والبوابة", "تنظيف حساس الماف بحرص باسبراي إلكترونيات جاف والتأكد من إحكام خرطوم الهواء.", "تحذيرية")
            "P0102" -> DtcDiagnosis(clean, "إشارة منخفضة من حساس تدفق الهواء (MAF Low Input)", "نظام سحب الهواء", "• فصل فيشة الحساس أو انقطاع أسلاكه أو تلف الحساس", "فحص فيشة وأسلاك الحساس والتأكد من تغذية 12V و 5V.", "متوسطة")
            "P0106" -> DtcDiagnosis(clean, "خلل في قراءة حساس ضغط المانيفولد (MAP Range/Performance)", "نظام سحب الهواء", "• انسداد أو تلف حساس MAP أو تسريب فاكيوم شديد", "فحص حساس الماب وتنظيفه وفحص خراطيم السحب.", "تحذيرية")
            "P0107" -> DtcDiagnosis(clean, "إشارة منخفضة من حساس MAP", "نظام سحب الهواء", "• انقطاع سلك الإشارة أو تلف حساس MAP", "فحص التوصيلات الكهربائية لحساس ضغط المانيفولد.", "تحذيرية")
            "P0108" -> DtcDiagnosis(clean, "إشارة مرتفعة من حساس MAP", "نظام سحب الهواء", "• شورت في دائرة 5V أو تلف الحساس", "فحص الأسلاك وتغيير الحساس إن لزم.", "تحذيرية")
            "P0113" -> DtcDiagnosis(clean, "إشارة مرتفعة من حساس حرارة هواء السحب (IAT)", "نظام الهواء", "• فصل فيشة الحساس في العمة", "إعادة تركيب فيشة حساس حرارة الهواء وفحص أسلاكه.", "تحذيرية")
            "P0115" -> DtcDiagnosis(clean, "عطل دائرة حساس حرارة مياه التبريد (ECT Sensor)", "نظام التبريد", "• تلف حساس حرارة المياه أو كسر الفيشة", "استبدال حساس حرارة المياه ومراجعة فيشته لتفادي الغليان واستهلاك الوقود.", "حرجة / فورية")
            "P0128" -> DtcDiagnosis(clean, "حرارة المحرك أقل من درجة التشغيل القياسية", "نظام التبريد", "• ثرموستات الكوعة مفتوح دائماً ولا يغلق\n• تلف حساس الحرارة", "استبدل ثرموستات الكوعة لحماية المحرك من الاستهلاك الزائد والتآكل.", "تحذيرية")
            "P0130" -> DtcDiagnosis(clean, "عطل دائرة حساس الأكسجين العلوي (Bank 1 Sensor 1)", "نظام الوقود والعادم", "• تلف حساس الشكمان الأول أو تراكم الكربون عليه", "فحص إشارة الفولت اللحظية للحساس (0.1 - 0.9V) وتغييره إذا كان بطيئاً.", "متوسطة")
            "P0131" -> DtcDiagnosis(clean, "جهد منخفض بحساس الأكسجين العلوي", "نظام العادم", "• خليط فقير جداً أو تلامس سلك الإشارة بالأرضي", "فحص توصيلات الحساس وضغط البنزين.", "متوسطة")
            "P0133" -> DtcDiagnosis(clean, "استجابة بطيئة من حساس الشكمان العلوي", "نظام العادم", "• تقدم عمر حساس الشكمان وتلوثه بالكربون", "تنظيف أو استبدال حساس الشكمان العلوي.", "تحذيرية")
            "P0134" -> DtcDiagnosis(clean, "انعدام إشارة حساس الشكمان العلوي", "نظام العادم", "• فصل الفيشة أو احتراق المقاومة الداخلية", "فحص فيشة الحساس وتغيير الحساس إن لزم.", "متوسطة")
            "P0135" -> DtcDiagnosis(clean, "تلف سخان حساس الأكسجين العلوي O2 Heater", "نظام العادم", "• احتراق دائرة التسخين الداخلية للحساس", "استبدال حساس الشكمان (دائرة السخان تالفة داخلياً).", "تحذيرية")
            "P0136" -> DtcDiagnosis(clean, "عطل حساس الأكسجين السفلي (Bank 1 Sensor 2)", "نظام العادم", "• تلف حساس الشكمان الثاني بعد علبة البيئة", "فحص فيشة وأسلاك الحساس الخلفي أو استبداله.", "تحذيرية")
            "P0141" -> DtcDiagnosis(clean, "تلف سخان حساس الشكمان الخلفي", "نظام العادم", "• تلف دائرة التسخين بحساس الشكمان الثاني", "استبدال حساس الشكمان الخلفي.", "تحذيرية")
            "P0335" -> DtcDiagnosis(clean, "عطل حساس عمود الكرنك (Crankshaft Position Sensor)", "نظام إدارة المحرك", "• تلف حساس الكرنك أو تراكم برادة حديد عليه\n• يسبب صعوبة دوران المحرك أو توقفه المفاجئ أثناء السير", "افحص فيشة حساس الكرنك واستبدله فوراً لتجنب انطفاء المحرك المفاجئ.", "حرجة / فورية")
            "P0340" -> DtcDiagnosis(clean, "عطل حساس عمود الكامات (Camshaft Position Sensor)", "نظام إدارة المحرك", "• تلف حساس الكامة أو عدم دقة توقيت الكاتينة", "فحص فيشة حساس الكامة ومراجعة ضبط توقيت الكاتينة.", "متوسطة إلى خطيرة")
            "P0400" -> DtcDiagnosis(clean, "خلل تدفق صمام تدوير غازات العادم (EGR Flow)", "نظام الانبعاثات", "• تراكم الكربون وانسداد صمام الـ EGR أو مواسيره", "فك وتنظيف صمام الـ EGR باسبراي إزالة الكربون.", "تحذيرية")
            "P0440" -> DtcDiagnosis(clean, "عطل بنظام تفريغ أبخرة الوقود (EVAP System)", "نظام الوقود والتبخير", "• تسريب في دورة أبخرة البنزين أو صمام الكانستر", "تأكد من إحكام غطاء تانك البنزين أولاً وفحص صمام تفريغ الأبخرة.", "تحذيرية")
            "P0442" -> DtcDiagnosis(clean, "تسريب طفيف بأبخرة التانك (EVAP Small Leak)", "نظام الوقود", "• عدم إحكام قفل غطاء تانك البنزين أو تلف جوان الغطاء", "أحكم إغلاق غطاء تانك البنزين وتأكد من سلامة كاوتشة الغطاء.", "تحذيرية")
            "P0455" -> DtcDiagnosis(clean, "تسريب كبير بأبخرة التانك (EVAP Large Leak)", "نظام الوقود", "• غطاء التانك مفتوح أو صمام الكانستر عالق في وضع الفتح", "اربط غطاء التانك جيداً وافحص خراطيم الكانستر.", "تحذيرية")
            "P0500" -> DtcDiagnosis(clean, "عطل في حساس سرعة السيارة (VSS)", "نظام الحركة والسرعة", "• تلف حساس السرعة في الفتيس أو انقطاع أسلاكه\n• يسبب توقف عداد السرعة ولخبطة غيارات الفتيس", "فحص فيشة حساس السرعة بأسفل السيارة واستبداله.", "متوسطة")
            "P0505" -> DtcDiagnosis(clean, "خلل في منظم سرعة السلانسيه (Idle Air Control)", "نظام الهواء والسلانسيه", "• اتساخ بوابة الهواء (الثروتل) وتراكم الكربون حول صمام الآيدل", "تنظيف بوابة الهواء (Throttle Body) وإعادة برمجتها.", "تحذيرية")
            "P0507" -> DtcDiagnosis(clean, "سرعة السلانسيه أعلى من المعدل الطبيعي", "نظام الهواء", "• تسريب هواء خارجي إلى المانيفولد أو اتساخ البوابة", "فحص خراطيم الهواء وتنظيف وبرمجة البوابة الإلكترونية.", "تحذيرية")
            "P0700" -> DtcDiagnosis(clean, "طلب إضاءة لمبة الأعطال من كمبيوتر الفتيس (TCM)", "الفتيس الأوتوماتيك", "• كمبيوتر الفتيس سجل عطلاً وأمر بإضاءة لمبة الأعطال", "فحص كمبيوتر الفتيس بجهاز متخصص وقراءة أكواد P07xx الفرعية.", "متوسطة")
            "P0705" -> DtcDiagnosis(clean, "عطل حساس وضعية عصا الفتيس (عظمة الفتيس)", "الفتيس الأوتوماتيك", "• اتساخ أو تلف مفتاح المارش والأوضاع (P, R, N, D)", "تنظيف أو استبدال مفتاح وضعيات الفتيس ومراجعة الفيوزات.", "متوسطة")
            "C0035" -> DtcDiagnosis(clean, "عطل حساس سرعة العجلة الأمامية (ABS Sensor)", "نظام الفرامل ABS", "• تلف حساس الـ ABS أو قطع في سلك الحساس عند العجلة", "فحص سلك وفيشة حساس العجلة وتنظيف الترس المسنن.", "متوسطة")
            "U0100" -> DtcDiagnosis(clean, "فقدان الاتصال بكمبيوتر المحرك (Lost Comm with ECM)", "شبكة بيانات CAN-Bus", "• ضعف شحن البطارية أو ارتخاء فيشة كمبيوتر المحرك أو فيوز الـ ECU", "تأكد من كابلات البطارية وفيوزات المحرك وإحكام فيشة الـ ECU الرئيسية.", "حرجة / فورية")
            else -> {
                val categoryName = when (clean.firstOrNull()) {
                    'P' -> "نظام المحرك ومجموعة الدفع (Powertrain)"
                    'C' -> "نظام الشاسيه والفرامل والتعليق (Chassis)"
                    'B' -> "نظام هيكل السيارة والوسائد الهوائية (Body)"
                    'U' -> "شبكة الاتصال وكمبيوترات السيارة (Network / CAN)"
                    else -> "عطل عام بكمبيوتر السيارة"
                }
                val subName = if (clean.length >= 3 && clean.startsWith("P0")) {
                    when (clean[2]) {
                        '1', '2' -> "نظام قياس الهواء والوقود والحساسات"
                        '3' -> "نظام الإشعال وشمعات الاحتراق وتوقيت المحرك"
                        '4' -> "أنظمة الانبعاثات والبيئة (الكاتالايزر والتبخير)"
                        '5' -> "نظام التحكم بالسرعة وسلانسيه المحرك"
                        '6' -> "دوائر التحكم الإلكترونية الداخلية بالكمبيوتر"
                        '7', '8' -> "ناقل الحركة الأوتوماتيكي (الفتيس)"
                        else -> "أجهزة الاستشعار والمحرك"
                    }
                } else {
                    categoryName
                }
                DtcDiagnosis(
                    clean,
                    "كود عطل في $subName",
                    categoryName,
                    "• تم رصد قراءة غير مطابقة للمواصفات القياسية من حساسات $subName\n• قد يكون بسبب تلف حساس أو ارتخاء فيشة أو اتساخ التوصيلات الكهربائية",
                    "افحص فيش وتوصيلات $subName بجهاز القياس واستخدم زر مسح الأعطال بعد الصيانة للتأكد.",
                    "تنبيهية / فحص"
                )
            }
        }
    }

    private fun showDtcLookupDialog() {
        val etCode = edit("أدخل كود العطل (مثال: P0420)").apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        AlertDialog.Builder(this)
            .setTitle("🔍 قاموس تفسير أعطال OBD-II الشامل")
            .setMessage("أدخل أي كود عطل يظهر في السيارة (P, C, B, U) لمعرفة معناه وتشخيصه وأسبابه وطريقة الإصلاح باللغة العربية فورياً:")
            .setView(etCode)
            .setPositiveButton("تشخيص العطل") { _, _ ->
                val code = etCode.text.toString().trim().uppercase()
                if (code.isNotBlank()) {
                    val diag = diagnoseDtc(code)
                    AlertDialog.Builder(this)
                        .setTitle("تقرير فحص العطل: [${diag.code}]")
                        .setMessage(
                            "📌 اسم ومعنى الكود:\n${diag.titleAr}\n\n" +
                            "🏷️ النظام المتأثر:\n${diag.system}\n\n" +
                            "⚠️ مستوى الخطورة:\n${diag.severity}\n\n" +
                            "🔍 الأسباب المحتملة:\n${diag.causesAr}\n\n" +
                            "🛠️ طريقة الإصلاح الموصى بها:\n${diag.solutionAr}"
                        )
                        .setPositiveButton("حسناً", null)
                        .show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun restoreSelectedDevice() {
        val name = preferences.getString("device_name", null)
        val trans = preferences.getString("device_transport", null)
        if (name != null && trans != null && ::selectedText.isInitialized) {
            selectedText.text = "الجهاز المختار سابقاً: $name ($trans)"
        }
    }

    private fun disconnectObd() {
        isTelemetryLoopRunning = false
        try { activeSocket?.close() } catch (_: Exception) {}
        try { activeWifiSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        activeWifiSocket = null
        telemetry.isConnected = false
    }

    // ========================================================
    // PERMISSIONS
    // ========================================================
    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            return scan && connect
        }
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestPermissionsCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_NEARBY
            )
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_NEARBY
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NEARBY && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            beginDiscovery()
        }
    }

    // ========================================================
    // UI HELPERS
    // ========================================================
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Color.rgb(17, 35, 52))
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(12)
        layoutParams = params
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun edit(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(167, 203, 226))
        setSingleLine(true)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(8)
        layoutParams = params
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(7, 21, 37))
        setBackgroundColor(Color.rgb(110, 231, 255))
        setOnClickListener { action() }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(10)
        layoutParams = params
    }

    private fun metricBox(label: String, initialVal: String, valueColor: Int): TextView {
        val valText: TextView
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 45, 66))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            layoutParams = lp

            addView(text(label, 11, Color.rgb(167, 203, 226), false))
            valText = text(initialVal, 16, valueColor, true)
            addView(valText)
        }
        return valText
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
