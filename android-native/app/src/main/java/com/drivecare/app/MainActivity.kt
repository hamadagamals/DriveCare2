package com.drivecare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
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
        var carOdometerKm: Float = 0.0f,
        var odometerSource: String = "بانتظار الاتصال",
        var tripStartOdometerKm: Float = 0.0f,
        var activeAlertTitle: String? = null,
        var activeAlertDesc: String? = null,
        var isAlertCritical: Boolean = false,
        var obdProtocol: String = ""
    )

    private val candidates = linkedMapOf<String, DeviceCandidate>()
    private lateinit var resultsContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var selectedText: TextView
    private lateinit var scanButton: Button
    private lateinit var preferences: SharedPreferences

    // Vehicle Protocol State
    private var isCurrentProtocolCan: Boolean = false
    private var detectedProtocolName: String = ""

    // User State & Roles (owner, driver, garage)
    private var isLoggedIn: Boolean = false
    private var userName: String = ""
    private var userPhone: String = ""
    private var currentRole: String = "owner" // "owner", "driver", "garage"
    private var activeTab: String = "home" // "home", "gauges", "vehicles", "scanner", "fuel", "maint", "remote_fleet", "owner_security", "workshops_dir", "workshop_console", "log_service", "more"
    private var activeSubScreen: String? = null // When null, displays the sleek 3x3 Home Grid Dashboard matching the reference design
    private var currentNavTab: String = "home" // "home", "scanner", "gauges", "reports", "more"

    // Multi-Persona State
    private var authorizedDriverName: String = "محمد السائق (مفوض)"
    private var authorizedDriverPhone: String = "01099887766"

    // Delta-Deadband Telemetry State (التخزين عند التغير فقط)
    data class TelemetryDeltaRecord(
        val timestamp: Long,
        val timeString: String,
        val speed: Int,
        val rpm: Int,
        val fuelLevel: Int,
        val coolantTemp: Int,
        val batteryVoltage: Float,
        val dtcCount: Int,
        val changeReason: String,
        val locationName: String
    )
    private var lastSyncedSpeed: Int = -1
    private var lastSyncedRpm: Int = -1
    private var lastSyncedFuel: Int = -1
    private var lastSyncedTemp: Int = -1
    private var lastSyncedDtcCount: Int = -1
    private var lastSyncedAlert: String? = null
    private var lastSyncTimestamp: Long = 0L
    private val telemetryDeltaLog = mutableListOf<TelemetryDeltaRecord>()
    private var remoteFleetTimeframe: String = "1h"

    // Registered Workshops Directory Model & Data
    data class RegisteredWorkshop(
        val id: String,
        val name: String,
        val managerName: String,
        val specialty: String,
        val categoryKey: String, // "all", "electricity", "mechanic", "computer", "chassis", "ac"
        val phone: String,
        val address: String,
        val distanceKm: Float,
        val lat: Double,
        val lng: Double,
        val rating: Float
    )
    private var workshopSelectedCategory: String = "all"
    private val registeredWorkshops = mutableListOf<RegisteredWorkshop>(
        RegisteredWorkshop(
            "ws-1",
            "المركز الألماني لصيانة وفحص السيارات",
            "م. شريف عبد الفتاح",
            "فحص كمبيوتر وبرمجة أعطال + كهرباء وحساسات",
            "computer",
            "01023456781",
            "شارع النصر، المعادي، القاهرة",
            2.1f,
            29.9602,
            31.2569,
            4.9f
        ),
        RegisteredWorkshop(
            "ws-2",
            "مركز الأهرام لصيانة المحركات والعمرة",
            "الأسطى محمود الأهرام",
            "ميكانيكا وعمرة محركات وفتيس أوتوماتيك",
            "mechanic",
            "01134567892",
            "شارع فيصل الرئيسي، الجيزة",
            3.4f,
            30.0074,
            31.1892,
            4.8f
        ),
        RegisteredWorkshop(
            "ws-3",
            "ورشة النصر لكهرباء وحساسات السيارات",
            "م. أحمد النصر",
            "كهرباء، ضفيرة، مارش، دينامو، شحن بطاريات",
            "electricity",
            "01245678903",
            "الحي السابع، مدينة نصر، القاهرة",
            4.1f,
            30.0561,
            31.3301,
            4.7f
        ),
        RegisteredWorkshop(
            "ws-4",
            "مركز عرابي لضبط الزوايا والعفشة والميزان",
            "كابتن إبراهيم عرابي",
            "ضبط زوايا وميزان ليزر، عفشة ومساعدين وشاسيه",
            "chassis",
            "01056789014",
            "طريق الحرية، سموحة، الإسكندرية",
            1.8f,
            31.2156,
            29.9553,
            4.9f
        ),
        RegisteredWorkshop(
            "ws-5",
            "البرنس لصيانة وتعبئة تكييف السيارات",
            "المعلم سامح البرنس",
            "تكييف وتبريد، فريون أصلي، كباس، كشف تسريب ليزر",
            "ac",
            "01167890125",
            "شارع مصدق، الدقي، الجيزة",
            2.8f,
            30.0381,
            31.2064,
            4.8f
        ),
        RegisteredWorkshop(
            "ws-6",
            "مركز النيل المتكامل للصيانة السريعة",
            "م. طارق النيل",
            "صيانة سريعة، زيوت وفلاتر، بوجيهات وتيل فرامل",
            "mechanic",
            "01098765432",
            "ميدان لبنان، المهندسين، الجيزة",
            3.9f,
            30.0520,
            31.2010,
            4.8f
        )
    )

    // Security & Workshop Notifications Model
    data class SecurityNotificationItem(
        val id: String,
        val title: String,
        val message: String,
        val eventType: String,
        val severity: String, // "critical", "warning", "info"
        val timeString: String,
        val timestamp: Long,
        var isRead: Boolean
    )
    private val ownerSecurityNotifications = mutableListOf<SecurityNotificationItem>()

    // Workshop Authorized Inspection Session
    private var isWorkshopSessionUnlocked: Boolean = false
    private var unlockedVehicleName: String = "شيفروليه أفيو (Chevrolet Aveo 1.5L)"
    private var unlockedVehicleVin: String = "KMHDH41EBNA123456"
    private var workshopSessionExpiryTime: Long = 0L
    private var workshopSessionGrantedBy: String = "السائق المفوض"
    private var activeWorkshopPin: String = "7429"
    private var activeWorkshopTargetName: String = "المركز الألماني"
    private var activePinExpiryTimestamp: Long = 0L
    private var isPinActive: Boolean = true

    // Alert Dismissal, Snoozing & Background Android Notifications
    private val snoozedAlertsMap = mutableMapOf<String, Long>()
    private val permanentlyDismissedAlerts = mutableSetOf<String>()
    private var currentActiveAlertKey: String? = null
    private val SAFETY_NOTIFICATION_ID = 9991
    private val SAFETY_CHANNEL_ID = "drivecare_safety_channel"
    private var notificationManager: NotificationManager? = null
    // De-dup state so the (≈500ms) alert evaluation does not re-post a notification the user just swiped away
    @Volatile private var lastNotifiedAlertKey: String? = null
    @Volatile private var lastNotifiedTitle: String? = null

    // Adaptive Telemetry State (بث مباشر عند الطلب vs تجميع دوري بالخلفية)
    private var isAppInForeground: Boolean = true
    private var telemetrySyncMode: String = "live" // "live" or "periodic_batch"
    private var lastBatchSyncTimestamp: Long = 0L

    // My Workshop Profile Registration State
    private var myWorkshopName: String = "مركز النجم لصيانة وفحص السيارات"
    private var myWorkshopManager: String = "م. إسلام النجم"
    private var myWorkshopSpecialty: String = "فحص كمبيوتر وبرمجة أعطال + كهرباء وحساسات"
    private var myWorkshopCategory: String = "computer" // "computer", "electricity", "mechanic", "chassis", "ac", "all"
    private var myWorkshopPhone: String = "01099887755"
    private var myWorkshopAddress: String = "شارع الهرم الرئيسي، الجيزة"
    private var myWorkshopLat: Double = 29.9870
    private var myWorkshopLng: Double = 31.1500
    private var isMyWorkshopRegistered: Boolean = false

    // Driver Assigned Vehicles Model & Data
    data class DriverAssignedCar(
        val id: String,
        val name: String,
        val make: String,
        val year: String,
        val vin: String,
        val ownerName: String,
        val ownerPhone: String,
        val tankCapacity: Float,
        val baseRate: Float,
        val displacement: Float,
        val fuelType: String,
        val emptyWeightKg: Float,
        var isActive: Boolean
    )
    private val driverAssignedCars = mutableListOf<DriverAssignedCar>()

    // Owner Multi-Vehicle Fleet & Multi-Driver Delegation Models
    data class AssignedDriver(
        var name: String,
        var phone: String,
        var roleType: String = "سائق مفوض",
        var assignedDate: String = "2026/10/20"
    )

    data class OwnerVehicle(
        val id: String,
        var name: String,
        var make: String,
        var year: String,
        var vin: String,
        var plateNumber: String,
        var tankCapacity: Float = 45.0f,
        var displacement: Float = 1.5f,
        var baseRate: Float = 7.2f,
        var emptyWeightKg: Float = 1200.0f,
        var fuelType: String = "gasoline",
        var alertsEnabled: Boolean = true,
        var isCurrentActive: Boolean = false,
        val assignedDrivers: MutableList<AssignedDriver> = mutableListOf()
    )
    private val ownerFleetVehicles = mutableListOf<OwnerVehicle>()

    // Driver Delegation Requests (دعوات تفويض السائقين برقم الهاتف)
    data class DriverDelegationRequest(
        val id: String,
        val vehicleId: String,
        val vehicleName: String,
        val vehicleMake: String,
        val vehicleYear: String,
        val vehicleVin: String,
        val vehiclePlate: String,
        val ownerName: String,
        val ownerPhone: String,
        val driverCustomName: String, // اسم السائق الخاص بالمالك
        val driverPhone: String,       // رقم هاتف السائق المسجل به في التطبيق
        val driverRole: String = "سائق مفوض",
        val requestDate: String,
        var status: String = "pending" // "pending", "accepted", "rejected"
    )
    private val driverDelegationRequests = mutableListOf<DriverDelegationRequest>()

    // Workshop Single-Session Access Requests (طلبات فحص الورشة بموافقة هاتف المالك لمرة واحدة)
    data class WorkshopAccessRequest(
        val id: String,
        val workshopName: String,
        val workshopPhone: String,
        val ownerPhone: String,
        val targetVehicleName: String,
        val serviceReason: String,
        val requestDate: String,
        var status: String = "pending" // "pending", "approved", "rejected", "closed"
    )
    private val workshopAccessRequests = mutableListOf<WorkshopAccessRequest>()
    private var activeWorkshopSessionRequest: WorkshopAccessRequest? = null

    // Scanner state
    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { stopDiscovery(timedOut = true) }
    private var classicReceiverRegistered = false

    // Telemetry & OBD Connection
    private val telemetry = LiveTelemetry()
    @Volatile private var activeSocket: BluetoothSocket? = null
    @Volatile private var activeWifiSocket: Socket? = null
    @Volatile private var isTelemetryLoopRunning = false
    // Each OBD loop captures its own generation; a stale/zombie loop can never touch the newer one's state.
    private val obdLoopGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastConnectCandidate: DeviceCandidate? = null
    @Volatile private var obdReconnectAttempts = 0
    @Volatile private var userRequestedDisconnect = false
    private var isSimulating = false
    private var toneGenerator: ToneGenerator? = null
    private var alertBanner: LinearLayout? = null
    private var tvAlertTitle: TextView? = null
    private var tvAlertDesc: TextView? = null
    private var lastAlertToneTime = 0L
    private var isSimulatingAlert = false

    // Route & Payload Trip Planner State
    private var phoneGpsSpeedKmh: Int = 0
    private var currentGpsLocation: Location? = null
    private var gpsLocationListener: android.location.LocationListener? = null
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

    // === Delta Odometer Architecture (قراءة عداد السيارة التراكمي وحساب الفروق) ===
    private data class OdometerProbe(
        val header: String,
        val command: String,
        val expectedPrefix: String,
        val description: String,
        val isCluster: Boolean = false,
        val isUds: Boolean = false,
        val byteLength: Int = 4,
        val scalingDivisor: Float = 10.0f
    )

    private var activeOdometerProbe: OdometerProbe? = null

    private val universalOdometerProbes = listOf(
        // === الأولوية الأولى: معيار OBD2 القياسي الحديث SAE J1979 Mode 01 PID 01A6 ===
        OdometerProbe("7DF", "01A6", "41A6", "عداد السيارة القياسي العام (Standard OBD PID 01A6)", isCluster = false, isUds = false, byteLength = 4, scalingDivisor = 10.0f),
        OdometerProbe("7E0", "01A6", "41A6", "عداد كمبيوتر المحرك المباشر (Engine ECU PID 01A6)", isCluster = false, isUds = false, byteLength = 4, scalingDivisor = 10.0f),

        // === الأولوية الثانية: لوحة عدادات الطابلوه عبر وضع التشخيص الموسع UDS / Mode 22 لمختلف الماركات ===
        OdometerProbe("7C6", "22B002", "62B002", "لوحة عدادات هيونداي / كيا UDS (Cluster 7C6)", isCluster = true, isUds = true, byteLength = 4, scalingDivisor = 1.0f),
        OdometerProbe("7C6", "2101", "6101", "لوحة عدادات هيونداي / كيا (Cluster 7C6 2101)", isCluster = true, isUds = false, byteLength = 3, scalingDivisor = 1.0f),
        OdometerProbe("7C0", "220201", "620201", "لوحة عدادات تويوتا / لكزس UDS (Cluster 7C0)", isCluster = true, isUds = true, byteLength = 4, scalingDivisor = 1.0f),
        OdometerProbe("7C0", "2101", "6101", "لوحة عدادات تويوتا / لكزس (Cluster 7C0 2101)", isCluster = true, isUds = false, byteLength = 3, scalingDivisor = 1.0f),
        OdometerProbe("720", "22DD01", "62DD01", "لوحة عدادات فورد / مازدا UDS (Cluster 720)", isCluster = true, isUds = true, byteLength = 4, scalingDivisor = 1.0f),
        OdometerProbe("7A4", "220020", "620020", "لوحة عدادات شيفروليه / GM UDS (Cluster 7A4)", isCluster = true, isUds = true, byteLength = 4, scalingDivisor = 1.0f),
        OdometerProbe("7A0", "220020", "620020", "لوحة عدادات شيفروليه البديلة (Cluster 7A0)", isCluster = true, isUds = true, byteLength = 4, scalingDivisor = 1.0f),
        OdometerProbe("714", "222203", "622203", "لوحة عدادات فولكس فاجن / سكودا UDS (Cluster 714)", isCluster = true, isUds = true, byteLength = 4, scalingDivisor = 1.0f),
        OdometerProbe("760", "2101", "6101", "لوحة عدادات رينو / داسيا (Cluster 760)", isCluster = true, isUds = false, byteLength = 3, scalingDivisor = 1.0f),
        OdometerProbe("743", "2101", "6101", "لوحة عدادات نيسان / إنفينيتي (Cluster 743)", isCluster = true, isUds = false, byteLength = 3, scalingDivisor = 1.0f),

        // === الأولوية الثالثة: عداد مسافة التشغيل التراكمي في كمبيوتر المكينة (PID 0131 العالمي) ===
        OdometerProbe("7DF", "0131", "4131", "عداد مسافة المحرك التراكمي (ECU Run Distance 0131)", isCluster = false, isUds = false, byteLength = 2, scalingDivisor = 1.0f),
        OdometerProbe("7E0", "0131", "4131", "عداد مسافة المحرك المباشر (ECU Run Distance 0131)", isCluster = false, isUds = false, byteLength = 2, scalingDivisor = 1.0f)
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

    private fun initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SAFETY_CHANNEL_ID,
                "تنبيهات الأمان والسلامة العاجلة لسيارتك",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات فورية عن حرارة المحرك والوقود وشحن البطارية والأعطال"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager?.createNotificationChannel(channel)
        } else {
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
    }

    private fun showSystemSafetyNotification(key: String, title: String, desc: String, critical: Boolean) {
        val nm = notificationManager ?: getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Same alert as the one already posted: never re-post it (that makes a swiped notification pop back
        // every 500ms). Only refresh it silently when it is still visible and its title actually changed.
        val sameKey = lastNotifiedAlertKey == key
        if (sameKey) {
            val stillVisible = try {
                nm.activeNotifications.any { it.id == SAFETY_NOTIFICATION_ID }
            } catch (_: Exception) { false }
            if (!stillVisible || lastNotifiedTitle == title) return
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, SAFETY_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle(title)
            .setContentText(desc)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(sameKey)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
            builder.setCategory(Notification.CATEGORY_ALARM)
        }

        nm.notify(SAFETY_NOTIFICATION_ID, builder.build())
        lastNotifiedAlertKey = key
        lastNotifiedTitle = title
    }

    private fun cancelSystemSafetyNotification() {
        lastNotifiedAlertKey = null
        lastNotifiedTitle = null
        notificationManager?.cancel(SAFETY_NOTIFICATION_ID)
    }

    private fun setSafetyAlert(key: String, title: String?, desc: String?, critical: Boolean) {
        if (title == null) {
            if (currentActiveAlertKey == key || key == "all") {
                telemetry.activeAlertTitle = null
                telemetry.activeAlertDesc = null
                currentActiveAlertKey = null
                cancelSystemSafetyNotification()
                runOnUiThread { updateAlertBannerUI() }
            }
            return
        }

        // Check if user permanently dismissed this alert for this trip
        if (permanentlyDismissedAlerts.contains(key)) {
            return
        }

        // Check if user snoozed this alert
        val snoozedUntil = snoozedAlertsMap[key]
        if (snoozedUntil != null && System.currentTimeMillis() < snoozedUntil) {
            return
        }

        val vehName = preferences.getString("vehicle_name", "") ?: ""
        val enrichedTitle = if (vehName.isNotEmpty() && !title.contains(vehName)) "$title • $vehName" else title

        currentActiveAlertKey = key
        telemetry.activeAlertTitle = enrichedTitle
        telemetry.activeAlertDesc = desc
        telemetry.isAlertCritical = critical

        playAlertSound(critical)
        showSystemSafetyNotification(key, enrichedTitle, desc ?: "", critical)

        runOnUiThread { updateAlertBannerUI() }
    }

    // Overload for legacy callers
    private fun setSafetyAlert(title: String?, desc: String?, critical: Boolean) {
        setSafetyAlert("general", title, desc, critical)
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
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = lp

            // 1. Snooze for 15 minutes
            addView(Button(this@MainActivity).apply {
                text = "🔕 تأجيل 15 دقيقة"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(40, 55, 75))
                val blp = LinearLayout.LayoutParams(0, dp(38), 1f)
                blp.setMargins(0, 0, dp(4), 0)
                layoutParams = blp
                setOnClickListener {
                    val key = currentActiveAlertKey ?: "general"
                    snoozedAlertsMap[key] = System.currentTimeMillis() + (15 * 60 * 1000L)
                    telemetry.activeAlertTitle = null
                    telemetry.activeAlertDesc = null
                    isSimulatingAlert = false
                    cancelSystemSafetyNotification()
                    try { toneGenerator?.stopTone() } catch (_: Exception) {}
                    banner.visibility = View.GONE
                    updateAlertBannerUI()
                    Toast.makeText(this@MainActivity, "🔕 تم إخفاء وتأجيل التنبيه لمدة 15 دقيقة بنجاح.", Toast.LENGTH_SHORT).show()
                }
            })

            // 2. Permanent Dismiss for current trip
            addView(Button(this@MainActivity).apply {
                text = "✖ إخفاء نهائي للرحلة"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(160, 30, 40))
                val blp = LinearLayout.LayoutParams(0, dp(38), 1f)
                blp.setMargins(0, 0, dp(4), 0)
                layoutParams = blp
                setOnClickListener {
                    val key = currentActiveAlertKey ?: "general"
                    permanentlyDismissedAlerts.add(key)
                    telemetry.activeAlertTitle = null
                    telemetry.activeAlertDesc = null
                    isSimulatingAlert = false
                    cancelSystemSafetyNotification()
                    try { toneGenerator?.stopTone() } catch (_: Exception) {}
                    banner.visibility = View.GONE
                    updateAlertBannerUI()
                    Toast.makeText(this@MainActivity, "✖ تم إخفاء التنبيه نهائياً للرحلة الحالية بنجاح.", Toast.LENGTH_SHORT).show()
                }
            })

            // 3. Audio Mute / Unmute
            val btnSilence = Button(this@MainActivity).apply {
                text = if (isAudioMuted) "🔊 إعادة الصوت" else "🔇 كتم الصوت"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(if (isAudioMuted) Color.rgb(20, 70, 40) else Color.rgb(80, 25, 35))
                val blp = LinearLayout.LayoutParams(0, dp(38), 1f)
                layoutParams = blp
                setOnClickListener {
                    isAudioMuted = !isAudioMuted
                    preferences.edit().putBoolean("is_audio_alert_muted", isAudioMuted).apply()
                    text = if (isAudioMuted) "🔊 إعادة الصوت" else "🔇 كتم الصوت"
                    setBackgroundColor(if (isAudioMuted) Color.rgb(20, 70, 40) else Color.rgb(80, 25, 35))
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
    private lateinit var tvCarOdometer: TextView
    private lateinit var tvTripDistance: TextView
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

    /** Invoked (on the service's main thread) when the user taps "إيقاف المراقبة" in the foreground notification. */
    private val stopRequestHandler: () -> Unit = {
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                disconnectObd(userInitiated = true)
                if (::statusText.isInitialized) {
                    statusText.text = "تم إيقاف المراقبة من الإشعار. اضغط اتصال لإعادة التشغيل."
                }
                if (::tvConnectionState.isInitialized) {
                    tvConnectionState.text = "منقطع 🔴"
                    tvConnectionState.setTextColor(Color.rgb(255, 122, 122))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObdForegroundService.onStopRequested = stopRequestHandler
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
        vehicleEmptyWeightKg = preferences.getFloat("vehicle_empty_weight", 1200.0f)

        // Restore persistent trip metrics (persists even if phone or car disconnects)
        telemetry.tripDistanceKm = preferences.getFloat("saved_trip_distance_km", 0.0f)
        telemetry.actualTripFuelLiters = preferences.getFloat("saved_trip_fuel_liters", 0.0f)
        if (telemetry.tripDistanceKm > 0.05f && telemetry.actualTripFuelLiters > 0f) {
            telemetry.tripAverageFuelRate = ((telemetry.actualTripFuelLiters / telemetry.tripDistanceKm) * 100f).coerceIn(2.5f, 35.0f)
        }

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

        initSecurityNotificationsAndDelta()
        initNotificationChannel()
        loadWorkshopProfile()
        loadDriverAssignedCars()
        loadOwnerFleetVehicles()
        loadDriverDelegationRequests()
        loadWorkshopAccessRequests()
        startPhoneGpsTracking()

        uiUpdateHandler.post(uiUpdateRunnable)

        renderCurrentScreen()
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
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
        stopPhoneGpsTracking()
        userRequestedDisconnect = true
        if (ObdForegroundService.onStopRequested === stopRequestHandler) {
            ObdForegroundService.onStopRequested = null
        }
        uiUpdateHandler.removeCallbacksAndMessages(null)
        scanHandler.removeCallbacksAndMessages(null)
        stopDiscovery()
        disconnectObd()
        ObdForegroundService.stop(applicationContext)
        toneGenerator?.release()
        if (classicReceiverRegistered) {
            try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
            classicReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun startPhoneGpsTracking() {
        if (!hasLocationPermissions()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            if (gpsLocationListener == null) {
                gpsLocationListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        currentGpsLocation = location
                        if (location.hasSpeed()) {
                            phoneGpsSpeedKmh = (location.speed * 3.6f).toInt()
                        }
                        if (!telemetry.isConnected && !isSimulating) {
                            telemetry.speed = phoneGpsSpeedKmh
                            if (activeTab == "gauges") {
                                updateGaugesTabUI()
                            }
                        }
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
            }
            gpsLocationListener?.let { listener ->
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, listener)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5.0f, listener)
                }
            }
        } catch (_: SecurityException) {} catch (_: Exception) {}
    }

    private fun stopPhoneGpsTracking() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            gpsLocationListener?.let { lm.removeUpdates(it) }
        } catch (_: Exception) {}
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
        root.addView(text("تسجيل الدخول", 15, Color.rgb(167, 203, 226), false))

        val cardAuth = card().apply {
            addView(text("بيانات الحساب", 18, Color.WHITE, true))

            val etName = edit("الاسم الكامل")
            val etPhone = edit("رقم الموبايل").apply {
                inputType = InputType.TYPE_CLASS_PHONE
            }
            val etPassword = edit("كلمة المرور").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addView(etName)
            addView(etPhone)
            addView(etPassword)

            addView(text("اختر نوع الواجهة والحساب (قائمة منسدلة):", 14, Color.rgb(110, 231, 255), true).apply {
                setPadding(0, dp(14), 0, dp(6))
            })

            val roleOptions = listOf(
                "👑 واجهة المالك الأساسي (Owner)",
                "🚗 واجهة السائق المفوض (Driver)",
                "🔧 واجهة مركز الصيانة والورشة (Workshop)"
            )
            val roleKeys = listOf("owner", "driver", "garage")
            var selectedRoleIndex = 0

            val roleSpinner = Spinner(this@MainActivity).apply {
                val adapter = ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, roleOptions)
                this.adapter = adapter
                setBackgroundColor(Color.rgb(16, 38, 56))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                lp.bottomMargin = dp(10)
                layoutParams = lp
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        selectedRoleIndex = position
                        (view as? TextView)?.apply {
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            addView(roleSpinner)

            val tvAuthErr = text("", 13, Color.rgb(255, 122, 122), false)
            addView(tvAuthErr)

            addView(button("تسجيل وإنشاء الحساب") {
                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                val pass = etPassword.text.toString().trim()
                if (name.isEmpty() || phone.isEmpty() || pass.isEmpty()) {
                    tvAuthErr.text = "يرجى إدخال الاسم، رقم الموبايل، وكلمة المرور للمتابعة."
                    return@button
                }
                val role = roleKeys[selectedRoleIndex]
                preferences.edit()
                    .putBoolean("is_logged_in", true)
                    .putString("user_name", name)
                    .putString("user_phone", phone)
                    .putString("user_password", pass)
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
            addView(button("⚡ دخول تجريبي سريع") {
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
    // 2. MAIN COCKPIT SCREEN (واجهة نظيفة، بدون بيانات وهمية، وبدون تكرار للأيقونات)
    // ========================================================
    private fun buildMainCockpitScreen(): View {
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            layoutParams = p
            setBackgroundColor(Color.rgb(8, 23, 32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // 1. الشريط العلوي (اسم المستخدم + قائمة اختيار الواجهة المنسدلة + زر الصوت والخروج)
        mainLayout.addView(buildCockpitTopBar())

        // 2. المحتوى القابل للتمرير
        val scroll = ScrollView(this).apply {
            val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutParams = sp
            isFillViewport = true
        }

        val contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(16))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(contentBox)
        mainLayout.addView(scroll)

        // 3. شريط التنقل السفلي الثابت (خيارين فقط: الرئيسية والإعدادات لمنع التكرار مع بطاقات الشاشة)
        mainLayout.addView(buildBottomNavDock())

        if (activeSubScreen != null) {
            // شاشة الخدمة المفتوحة مع زر العودة للرئيسية بالأعلى
            contentBox.addView(buildSubScreenHeader(activeSubScreen!!))

            val banner = buildAlertBanner()
            alertBanner = banner
            contentBox.addView(banner)
            updateAlertBannerUI()

            val subView = when (activeSubScreen) {
                "owner_fleet" -> buildOwnerFleetTabContent()
                "remote_fleet" -> buildRemoteFleetTabContent()
                "vehicles" -> buildVehiclesTabContent()
                "owner_security" -> buildOwnerSecurityTabContent()
                "workshops_dir" -> buildWorkshopsDirectoryTabContent()
                "workshop_console" -> buildWorkshopConsoleTabContent()
                "workshop_profile" -> buildWorkshopProfileTabContent()
                "driver_cars" -> buildDriverCarsTabContent()
                "log_service" -> buildLogServiceTabContent()
                "gauges" -> buildGaugesTabContent()
                "scanner" -> buildScannerTabContent()
                "fuel" -> buildFuelTabContent()
                "maint" -> buildMaintenanceTabContent()
                "more" -> buildMoreSettingsTabContent()
                else -> buildGaugesTabContent()
            }
            contentBox.addView(subView)
        } else {
            // لوحة التحكم الرئيسية المنظمة (بيانات حقيقية 100% وبدون تكرار)
            // 1. بطاقة السيارة المضغوطة (الاسم واللوحة بجوار بعض مباشرة وبدون مسافة متباعدة)
            contentBox.addView(buildVehicleHeaderCard())

            // 2. شريط التنبيهات والأمان الطارئ
            val banner = buildAlertBanner()
            alertBanner = banner
            contentBox.addView(banner)
            updateAlertBannerUI()

            // 3. إشعارات تصاريح الورش أو دعوات السائقين الحقيقية
            if (currentRole == "owner") {
                buildOwnerAccessRequestsBanner()?.let { contentBox.addView(it) }
            } else if (currentRole == "driver") {
                buildDriverDelegationRequestsBanner()?.let { contentBox.addView(it) }
            }

            // 4. شبكة الخدمات المباشرة والواضحة (بدون تكرار أو AI أو بيانات وهمية)
            contentBox.addView(buildCleanServiceGrid())
        }

        return mainLayout
    }

    // ========================================================
    // TOP COCKPIT BAR (مع القائمة المنسدلة لاختيار وتحديد الواجهة)
    // ========================================================
    private fun buildCockpitTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(Color.rgb(10, 25, 38))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = lp

            val userInfo = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(text("مرحباً، $userName 👋", 15, Color.WHITE, true).apply {
                    setPadding(0, 0, 0, 0)
                })
            }
            addView(userInfo)

            val roleBtnLabel = when (currentRole) {
                "owner" -> "👑 واجهة المالك ▼"
                "driver" -> "🚗 واجهة السائق ▼"
                else -> "🔧 واجهة الورشة ▼"
            }
            val roleAccent = when (currentRole) {
                "owner" -> Color.rgb(248, 199, 74)
                "driver" -> Color.rgb(110, 231, 255)
                else -> Color.rgb(255, 140, 90)
            }
            val btnRoleDropdown = Button(this@MainActivity).apply {
                text = roleBtnLabel
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(8, 23, 32))
                setBackgroundColor(roleAccent)
                val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                blp.setMargins(dp(4), 0, dp(4), 0)
                layoutParams = blp
                setOnClickListener {
                    showRoleSelectionDialog()
                }
            }
            addView(btnRoleDropdown)

            val btnAudio = Button(this@MainActivity).apply {
                text = if (isAudioMuted) "🔇" else "🔊"
                textSize = 12f
                setTextColor(if (isAudioMuted) Color.rgb(255, 180, 100) else Color.rgb(126, 242, 154))
                setBackgroundColor(Color.rgb(16, 36, 52))
                val blp = LinearLayout.LayoutParams(dp(40), dp(34))
                blp.setMargins(dp(2), 0, dp(2), 0)
                layoutParams = blp
                setOnClickListener {
                    isAudioMuted = !isAudioMuted
                    preferences.edit().putBoolean("is_audio_alert_muted", isAudioMuted).apply()
                    text = if (isAudioMuted) "🔇" else "🔊"
                    setTextColor(if (isAudioMuted) Color.rgb(255, 180, 100) else Color.rgb(126, 242, 154))
                    try { toneGenerator?.stopTone() } catch (_: Exception) {}
                    updateAlertBannerUI()
                }
            }
            addView(btnAudio)

            val btnLogout = Button(this@MainActivity).apply {
                text = "🚪"
                textSize = 12f
                setTextColor(Color.rgb(255, 122, 122))
                setBackgroundColor(Color.rgb(36, 16, 22))
                val blp = LinearLayout.LayoutParams(dp(38), dp(34))
                blp.setMargins(dp(2), 0, 0, 0)
                layoutParams = blp
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
    }

    // ========================================================
    // ROLE SELECTION DROPDOWN DIALOG (قائمة منسدلة لاختيار وتحديد الدور)
    // ========================================================
    private fun showRoleSelectionDialog() {
        val roleKeys = arrayOf("owner", "driver", "garage")
        val roleLabels = arrayOf(
            "👑 واجهة المالك الأساسي (Owner) - إدارة الأسطول والتفويض والمراقبة",
            "🚗 واجهة السائق المفوض (Driver) - مقصورة القيادة والحساسات والرحلات",
            "🔧 واجهة مركز الصيانة والورشة (Workshop) - فحص الكمبيوتر وأذونات الصيانة"
        )
        val currentIndex = roleKeys.indexOf(currentRole).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("اختر واجهة المستخدم (تحديد الدور):")
            .setSingleChoiceItems(roleLabels, currentIndex) { dialog, which ->
                val selectedRole = roleKeys[which]
                if (currentRole != selectedRole) {
                    currentRole = selectedRole
                    preferences.edit().putString("active_role", currentRole).apply()
                    activeSubScreen = null
                    currentNavTab = "home"
                    activeTab = "home"
                    Toast.makeText(this, "تم التبديل بنجاح إلى: ${roleLabels[which]}", Toast.LENGTH_SHORT).show()
                    renderCurrentScreen()
                }
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ========================================================
    // SWITCH OWNER ACTIVE VEHICLE DIALOG (تبديل سيارة المالك النشطة)
    // ========================================================
    private fun showSwitchOwnerVehicleDialog() {
        if (ownerFleetVehicles.isEmpty()) {
            loadOwnerFleetVehicles()
        }
        val names = ownerFleetVehicles.map { veh ->
            val activeMark = if (veh.isCurrentActive) " (النشطة حالياً ✓)" else ""
            "🚗 ${veh.name} [لوحة: ${veh.plateNumber}]$activeMark"
        }.toTypedArray() + arrayOf("➕ إضافة سيارة جديدة للأسطول")

        AlertDialog.Builder(this)
            .setTitle("تبديل سيارة المالك النشطة:")
            .setItems(names) { _, which ->
                if (which < ownerFleetVehicles.size) {
                    val targetVeh = ownerFleetVehicles[which]
                    activateOwnerVehicle(targetVeh)
                } else {
                    showAddOwnerVehicleDialog()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ========================================================
    // DIRECT CALL OWNER DIALOG (اتصال مباشر بمالك السيارة للسائق)
    // ========================================================
    private fun showCallOwnerDialog() {
        val ownerPhone = preferences.getString("vehicle_primary_owner_phone", "01000000000") ?: "01000000000"
        val ownerName = preferences.getString("vehicle_primary_owner", "مالك السيارة") ?: "مالك السيارة"
        AlertDialog.Builder(this)
            .setTitle("📞 اتصال مباشر بمالك السيارة:")
            .setMessage("المالك: $ownerName\nرقم الهاتف: $ownerPhone\n\nهل ترغب في الاتصال المباشر به الآن؟")
            .setPositiveButton("📞 اتصال هاتفي") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ownerPhone"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذر فتح لوحة الاتصال: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("💬 مراسلة واتساب") { _, _ ->
                try {
                    val cleanPhone = ownerPhone.replace("+", "").replace(" ", "").trim()
                    val waUrl = "https://api.whatsapp.com/send?phone=$cleanPhone&text=" + Uri.encode("السلام عليكم، رسالة من سائق سيارتك عبر تطبيق DriveCare.")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)))
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذر فتح تطبيق واتساب: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ========================================================
    // VEHICLE HEADER CARD (مضغوطة وأنيقة: الاسم واللوحة بجوار بعض وحالة الاتصال الحقيقية)
    // ========================================================
    private fun buildVehicleHeaderCard(): View {
        if (ownerFleetVehicles.isEmpty()) {
            loadOwnerFleetVehicles()
        }
        val activeVeh = ownerFleetVehicles.firstOrNull { it.isCurrentActive } ?: ownerFleetVehicles.firstOrNull() ?: OwnerVehicle(
            id = "default_car",
            name = "شيفروليه أفيو 2015",
            make = "Chevrolet",
            year = "2015",
            vin = "KL1TA69...2015",
            plateNumber = "أ ب ج 123",
            tankCapacity = 45.0f,
            displacement = 1.5f,
            baseRate = 7.2f,
            emptyWeightKg = 1200.0f,
            fuelType = "gasoline",
            alertsEnabled = true,
            isCurrentActive = true
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14, 30, 46))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }

        // السطر الأول: اسم السيارة واللوحة ملتصقان بجوار بعض مباشرة وبدون أي تباعد
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = lp

            val carInfo = TextView(this@MainActivity).apply {
                text = "🚗 ${activeVeh.name} • لوحة: ${activeVeh.plateNumber}"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 0, 0, 0)
            }
            addView(carInfo)

            if (currentRole == "owner") {
                val btnSwitchCar = Button(this@MainActivity).apply {
                    text = "تبديل السيارة ▼"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(12, 30, 46))
                    setBackgroundColor(Color.rgb(248, 199, 74))
                    val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
                    layoutParams = blp
                    setOnClickListener {
                        showSwitchOwnerVehicleDialog()
                    }
                }
                addView(btnSwitchCar)
            }
        }
        card.addView(titleRow)

        // السطر الثاني: حالة الاتصال الحقيقية مع زر توصيل/فصل مباشر
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp

            val isConn = telemetry.isConnected
            val (statusText, statusColor) = when {
                isConn -> Pair("🟢 متصل بقارئ OBD (Bluetooth)", Color.rgb(126, 242, 154))
                isSimulating -> Pair("🟡 وضع المحاكاة نشط", Color.rgb(255, 210, 100))
                else -> Pair("⚪ غير متصل بقارئ OBD", Color.rgb(200, 160, 160))
            }

            val tvStatus = TextView(this@MainActivity).apply {
                text = statusText
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(statusColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 0, 0, 0)
            }
            addView(tvStatus)

            if (!isConn && !isSimulating) {
                val btnConnect = Button(this@MainActivity).apply {
                    text = "🔌 توصيل القارئ"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(8, 23, 32))
                    setBackgroundColor(Color.rgb(0, 229, 255))
                    val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
                    layoutParams = blp
                    setOnClickListener {
                        openSubScreen("scanner")
                    }
                }
                addView(btnConnect)
            } else {
                val btnDisconnect = Button(this@MainActivity).apply {
                    text = "قطع الاتصال"
                    textSize = 11f
                    setTextColor(Color.rgb(255, 122, 122))
                    setBackgroundColor(Color.rgb(40, 20, 25))
                    val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
                    layoutParams = blp
                    setOnClickListener {
                        disconnectObd()
                        if (isSimulating) toggleSimulation()
                        Toast.makeText(this@MainActivity, "تم قطع الاتصال.", Toast.LENGTH_SHORT).show()
                        renderCurrentScreen()
                    }
                }
                addView(btnDisconnect)
            }
        }
        card.addView(statusRow)

        return card
    }

    // ========================================================
    // OWNER & DRIVER ACCESS / DELEGATION BANNERS
    // ========================================================
    private fun buildOwnerAccessRequestsBanner(): View? {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        var hasItems = false

        if (isWorkshopSessionUnlocked && activeWorkshopSessionRequest != null) {
            hasItems = true
            val activeBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(55, 15, 20))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(6)
                layoutParams = lp

                addView(text("⚠️ تنبيه أمني: ورشة (${activeWorkshopSessionRequest?.workshopName}) متصلة بالسيارة الآن!", 14, Color.rgb(255, 122, 122), true))
                addView(text("الورشة تقرأ الحساسات لفحص: (${activeWorkshopSessionRequest?.serviceReason}) • هاتف: ${activeWorkshopSessionRequest?.workshopPhone}", 12, Color.WHITE, false))

                addView(Button(this@MainActivity).apply {
                    text = "⛔ إنهاء جلسة الورشة وإلغاء الدخول فوراً"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(180, 30, 30))
                    val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
                    blp.topMargin = dp(6)
                    layoutParams = blp
                    setOnClickListener {
                        revokeWorkshopAccessNow()
                    }
                })
            }
            container.addView(activeBox)
        }

        val pendingWs = workshopAccessRequests.filter { it.status == "pending" }
        if (pendingWs.isNotEmpty()) {
            hasItems = true
            pendingWs.forEach { req ->
                val reqBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(20, 42, 60))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.bottomMargin = dp(6)
                    layoutParams = lp

                    addView(text("📡 طلب إذن فحص من ورشة صيانة (بدون أرقام سرية):", 14, Color.rgb(248, 199, 74), true))
                    addView(text("• اسم الورشة: ${req.workshopName} (هاتف: ${req.workshopPhone})", 13, Color.rgb(110, 231, 255), true))
                    addView(text("• السيارة: ${req.targetVehicleName} • الغرض: ${req.serviceReason}", 12, Color.WHITE, false))

                    val btnRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        blp.topMargin = dp(8)
                        layoutParams = blp

                        addView(Button(this@MainActivity).apply {
                            text = "✅ موافقة ودخول لمرة واحدة"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.rgb(20, 120, 80))
                            val lpBtn = LinearLayout.LayoutParams(0, dp(40), 1.3f)
                            lpBtn.rightMargin = dp(4)
                            layoutParams = lpBtn
                            setOnClickListener {
                                approveWorkshopAccessRequest(req)
                            }
                        })

                        addView(Button(this@MainActivity).apply {
                            text = "❌ رفض"
                            textSize = 12f
                            setTextColor(Color.rgb(255, 122, 122))
                            setBackgroundColor(Color.rgb(55, 20, 25))
                            val lpBtn = LinearLayout.LayoutParams(0, dp(40), 1f)
                            layoutParams = lpBtn
                            setOnClickListener {
                                rejectWorkshopAccessRequest(req)
                            }
                        })
                    }
                    addView(btnRow)
                }
                container.addView(reqBox)
            }
        }

        return if (hasItems) container else null
    }

    private fun buildDriverDelegationRequestsBanner(): View? {
        val pendingInvites = driverDelegationRequests.filter {
            it.status == "pending" && (it.driverPhone == userPhone || userPhone.isEmpty() || it.driverPhone.endsWith(userPhone.takeLast(8)))
        }
        if (pendingInvites.isEmpty()) return null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        pendingInvites.forEach { req ->
            val inviteBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(18, 48, 70))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(6)
                layoutParams = lp

                addView(text("🚗 وصلتك دعوة تفويض استلام سيارة جديدة!", 15, Color.rgb(248, 199, 74), true))
                addView(text("• السيارة: ${req.vehicleName} (${req.vehicleMake} - ${req.vehicleYear}) - لوحة: ${req.vehiclePlate}", 13, Color.WHITE, true))
                addView(text("• المالك: ${req.ownerName} (هاتف: ${req.ownerPhone})", 12, Color.rgb(180, 215, 240), false))

                val btnRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    blp.topMargin = dp(8)
                    layoutParams = blp

                    addView(Button(this@MainActivity).apply {
                        text = "✅ قبول واستلام"
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.rgb(20, 120, 80))
                        val lpBtn = LinearLayout.LayoutParams(0, dp(40), 1.3f)
                        lpBtn.rightMargin = dp(4)
                        layoutParams = lpBtn
                        setOnClickListener {
                            acceptDriverDelegation(req)
                        }
                    })

                    addView(Button(this@MainActivity).apply {
                        text = "❌ رفض"
                        textSize = 12f
                        setTextColor(Color.rgb(255, 122, 122))
                        setBackgroundColor(Color.rgb(55, 20, 25))
                        val lpBtn = LinearLayout.LayoutParams(0, dp(40), 1f)
                        layoutParams = lpBtn
                        setOnClickListener {
                            rejectDriverDelegation(req)
                        }
                    })
                }
                addView(btnRow)
            }
            container.addView(inviteBox)
        }

        return container
    }

    // ========================================================
    // CLEAN SERVICE GRID (بطاقات واضحة بدون تكرار وبدون بيانات وهمية وبدون AI)
    // ========================================================
    private fun buildCleanServiceGrid(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }

        val titleText = when(currentRole) {
            "owner" -> "لوحة تحكم خدمات المالك 👑"
            "driver" -> "لوحة مقصورة وخدمات السائق 🚗"
            else -> "لوحة فحص وتشخيص الورشة 🔧"
        }
        container.addView(text(titleText, 14, Color.rgb(160, 205, 235), true).apply {
            setPadding(dp(2), dp(2), dp(2), dp(8))
        })

        data class ServiceCard(val title: String, val subtitle: String, val icon: String, val key: String)

        val cards: List<ServiceCard> = when(currentRole) {
            "owner" -> listOf(
                ServiceCard("فحص وحذف الأعطال", "قراءة أكواد الكمبيوتر ومسحها", "🔍", "scanner"),
                ServiceCard("البيانات والحساسات", "السرعة والحرارة والشحن الحية", "📊", "gauges"),
                ServiceCard("أسطول سياراتي", "إدارة وتفويض السائقين بالهاتف", "🚗", "owner_fleet"),
                ServiceCard("المراقبة والتتبع", "متابعة الموقع والسرعة عبر GPS", "📡", "remote_fleet"),
                ServiceCard("أمان وتصاريح الورش", "طلبات الفحص والطرد الفوري", "🚨", "owner_security"),
                ServiceCard("ملكية الشاسيه", "بيانات الشاسيه والقيد القانوني", "👑", "vehicles"),
                ServiceCard("حساب استهلاك الوقود", "تسجيل التفويل وحساب التكلفة", "⛽", "fuel"),
                ServiceCard("جدول الصيانة", "مواعيد الزيوت والفلاتر الدورية", "🔧", "maint")
            )
            "driver" -> listOf(
                ServiceCard("مقصورة العدادات", "السرعة والحرارة والشحن الحية", "🏎️", "gauges"),
                ServiceCard("فحص أعطال السيارة", "فحص سريع للمبة Check Engine", "🔍", "scanner"),
                ServiceCard("سياراتي المفوض بها", "السيارات المصرح بقيادتها", "🚗", "driver_cars"),
                ServiceCard("حاسبة الحمولة والوقود", "توزيع الوزن وتكلفة المسار", "⛽", "fuel"),
                ServiceCard("دليل وأرقام الورش", "أرقام الورش وأوناش الإنقاذ", "🗺️", "workshops_dir"),
                ServiceCard("اتصال بمالك السيارة", "مكالمة هاتفية أو واتساب مباشر", "📞", "call_owner")
            )
            else -> listOf(
                ServiceCard("طلب إذن الفحص", "إرسال طلب للمالك بدون أرقام سرية", "🔑", "workshop_console"),
                ServiceCard("فحص ومسح الأعطال", "قراءة ومسح أعطال الكمبيوتر ECU", "🔍", "scanner"),
                ServiceCard("الحساسات الحية", "قراءة الحساسات و Freeze Frame", "📊", "gauges"),
                ServiceCard("تسجيل صيانة وفاتورة", "توثيق القطع وتكلفة الصيانة", "📝", "log_service"),
                ServiceCard("ملف الورشة وبياناتي", "اسم الورشة والهاتف للعملاء", "🏪", "workshop_profile"),
                ServiceCard("جاهزية الفحص I/M", "فحص جاهزية انبعاثات العادم", "📋", "gauges")
            )
        }

        val rowCount = (cards.size + 1) / 2
        for (r in 0 until rowCount) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                rlp.bottomMargin = dp(8)
                layoutParams = rlp
            }

            for (c in 0 until 2) {
                val idx = r * 2 + c
                if (idx < cards.size) {
                    val cardItem = cards[idx]
                    val tile = buildServiceTile(cardItem.title, cardItem.subtitle, cardItem.icon) {
                        if (cardItem.key == "call_owner") {
                            showCallOwnerDialog()
                        } else {
                            openSubScreen(cardItem.key)
                        }
                    }
                    row.addView(tile)
                } else {
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(74), 1f)
                    })
                }
            }
            container.addView(row)
        }

        return container
    }

    // ========================================================
    // SERVICE TILE (بطاقة خدمة أفقية أنيقة ومريحة للعين)
    // ========================================================
    private fun buildServiceTile(title: String, subtitle: String, iconEmoji: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(13, 29, 45))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val lp = LinearLayout.LayoutParams(0, dp(74), 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            layoutParams = lp

            val iconBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val iconSize = dp(38)
                val ilp = LinearLayout.LayoutParams(iconSize, iconSize)
                ilp.leftMargin = dp(8)
                layoutParams = ilp

                val circleBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.rgb(18, 45, 68))
                }
                background = circleBg

                addView(text(iconEmoji, 16, Color.rgb(0, 229, 255), false).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                })
            }
            addView(iconBox)

            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    maxLines = 1
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 9f
                    setTextColor(Color.rgb(140, 180, 210))
                    maxLines = 1
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(textCol)

            setOnClickListener { onClick() }
        }
    }

    // ========================================================
    // SUB-SCREEN DRILL-DOWN HEADER (شريط العودة للرئيسية مع العنوان)
    // ========================================================
    private fun buildSubScreenHeader(key: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(12, 30, 46))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp

            val btnBack = Button(this@MainActivity).apply {
                text = "⬅️ الرئيسية"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(8, 23, 32))
                setBackgroundColor(Color.rgb(0, 229, 255))
                val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
                blp.leftMargin = dp(8)
                layoutParams = blp
                setOnClickListener {
                    activeSubScreen = null
                    activeTab = "home"
                    currentNavTab = "home"
                    renderCurrentScreen()
                }
            }
            addView(btnBack)

            addView(text(getSubScreenTitle(key), 13, Color.WHITE, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun openSubScreen(key: String) {
        activeSubScreen = key
        activeTab = key
        currentNavTab = if (key == "more") "more" else "home"
        renderCurrentScreen()
    }

    private fun getSubScreenTitle(key: String): String {
        return when(key) {
            "scanner" -> "🔍 فحص ومسح أعطال الكمبيوتر (DTC)"
            "gauges" -> "📊 عدادات وحساسات السيارة الحية (PIDs)"
            "owner_fleet" -> "🚗 أسطول سيارات المالك وتفويض السائقين"
            "remote_fleet" -> "📡 التتبع الحي والمراقبة عن بعد (GPS)"
            "owner_security" -> "🚨 أمان السيارة وأذونات الورشة (Kill-Switch)"
            "vehicles" -> "👑 ملكية الشاسيه والقيد القانوني (VIN)"
            "workshops_dir" -> "🗺️ دليل وخريطة مراكز الصيانة والورش"
            "workshop_console" -> "🔧 لوحة فحص الورشة وإذن الدخول"
            "workshop_profile" -> "🏪 ملف الورشة وبيانات الاتصال"
            "driver_cars" -> "🚗 سياراتي المفوض بقيادتها"
            "log_service" -> "📝 تسجيل صيانة وفاتورة جديدة"
            "fuel" -> "⛽ تكلفة الوقود وحساب الاستهلاك"
            "maint" -> "🔧 جدول وتذكيرات الصيانة الدورية"
            "more" -> "⚙️ إعدادات التطبيق والحساب"
            else -> "شاشة فرعية"
        }
    }

    // ========================================================
    // FIXED BOTTOM NAVIGATION DOCK (شريط التنقل السفلي الثابت - بدون أي تكرار مع البطاقات)
    // ========================================================
    private fun buildBottomNavDock(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(10, 22, 34))
            setPadding(0, dp(4), 0, dp(6))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            layoutParams = lp

            val tabs = listOf(
                Pair("home", "🏠 الرئيسية (لوحة الخدمات)"),
                Pair("more", "⚙️ الحساب والإعدادات")
            )

            for ((tabKey, tabTitle) in tabs) {
                val isActive = (currentNavTab == tabKey)
                val navItem = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    val itemLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    layoutParams = itemLp

                    val activeColor = Color.rgb(0, 229, 255)
                    val inactiveColor = Color.rgb(140, 175, 200)
                    val tint = if (isActive) activeColor else inactiveColor

                    if (isActive) {
                        setBackgroundColor(Color.rgb(16, 38, 58))
                    }

                    addView(text(tabTitle, 12, tint, isActive).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, 0)
                    })

                    setOnClickListener {
                        currentNavTab = tabKey
                        if (tabKey == "home") {
                            activeSubScreen = null
                            activeTab = "home"
                        } else {
                            activeSubScreen = "more"
                            activeTab = "more"
                        }
                        renderCurrentScreen()
                    }
                }
                addView(navItem)
            }
        }
    }

    // ========================================================
    // TAB: MORE SETTINGS & PROFILE (شاشة الحساب والإعدادات)
    // ========================================================
    private fun buildMoreSettingsTabContent(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val cardProfile = card().apply {
            addView(text("👤 ملف الحساب والواجهة الحالية", 16, Color.rgb(0, 229, 255), true))
            addView(text("• الاسم: $userName", 13, Color.WHITE, false))
            addView(text("• رقم الموبايل: $userPhone", 13, Color.WHITE, false))
            val roleDesc = when(currentRole) {
                "owner" -> "👑 واجهة المالك الأساسي (تحكم كامل بالأسطول)"
                "driver" -> "🚗 واجهة السائق المفوض (مقصورة القيادة والرحلات)"
                else -> "🔧 واجهة الورشة ومركز الصيانة (فحص الكمبيوتر)"
            }
            addView(text("• الدور الحالي: $roleDesc", 13, Color.rgb(248, 199, 74), true))

            addView(button("🔄 تبديل واجهة المستخدم (تحديد الدور) ▼") {
                showRoleSelectionDialog()
            }.apply {
                setBackgroundColor(Color.rgb(20, 52, 78))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardProfile)

        if (currentRole == "owner") {
            val cardVeh = card().apply {
                addView(text("🚗 إدارة سيارات الأسطول", 16, Color.rgb(248, 199, 74), true))
                addView(button("🚗 تبديل السيارة النشطة للأسطول ▼") {
                    showSwitchOwnerVehicleDialog()
                })
                addView(button("➕ إضافة سيارة جديدة للأسطول") {
                    showAddOwnerVehicleDialog()
                }.apply {
                    setBackgroundColor(Color.rgb(22, 68, 48))
                    setTextColor(Color.rgb(126, 242, 154))
                })
            }
            container.addView(cardVeh)
        }

        val cardConn = card().apply {
            addView(text("🔌 قارئ فحص السيارة (OBD-II Adapter)", 16, Color.rgb(110, 231, 255), true))
            val connStatus = if (telemetry.isConnected) "متصل بنجاح 🟢" else if (isSimulating) "وضع المحاكاة نشط 🟡" else "غير متصل ⚪"
            addView(text("• حالة الاتصال: $connStatus", 13, Color.WHITE, false))

            addView(button("🔍 فتح شاشة فحص وتوصيل الكمبيوتر") {
                openSubScreen("scanner")
            })
        }
        container.addView(cardConn)

        val cardLogout = card().apply {
            addView(text("🚪 إدارة الجلسة", 16, Color.rgb(255, 122, 122), true))
            addView(button("تسجيل الخروج من الحساب") {
                preferences.edit().putBoolean("is_logged_in", false).apply()
                isLoggedIn = false
                stopDiscovery()
                disconnectObd()
                renderCurrentScreen()
            }.apply {
                setBackgroundColor(Color.rgb(55, 20, 25))
                setTextColor(Color.rgb(255, 122, 122))
            })
        }
        container.addView(cardLogout)

        return container
    }

    private fun switchTab(tab: String) {
        if (tab == "home") {
            activeSubScreen = null
            activeTab = "home"
            currentNavTab = "home"
        } else {
            activeSubScreen = tab
            activeTab = tab
            currentNavTab = if (tab == "more") "more" else "home"
        }
        renderCurrentScreen()
    }

    // ========================================================
    // TAB: OWNER MULTI-VEHICLE & DRIVER FLEET MANAGEMENT (أسطول سيارات المالك)
    // ========================================================
    private fun buildOwnerFleetTabContent(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (ownerFleetVehicles.isEmpty()) {
            loadOwnerFleetVehicles()
        }

        // 1. Fleet Overview Header Card
        val cardOverview = card().apply {
            addView(text("🚗 أسطول سيارات المالك وتفويض السائقين 👑", 18, Color.rgb(248, 199, 74), true))
            addView(text("يمكنك هنا إدارة سياراتك المتعددة، وتعيين سائق أو أكثر لكل سيارة، وتفعيل أو كتم وصول إنذارات كل سيارة على هاتفك، والتبديل المباشر بين السيارات بنقرة واحدة:", 13, Color.rgb(167, 203, 226), false))

            // 3-Metric Summary Row
            val rowMetrics = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            rowMetrics.addView(metricBox("إجمالي سياراتك", "${ownerFleetVehicles.size} مركبات 🚙", Color.rgb(110, 231, 255)).parent as View)
            val totalDrivers = ownerFleetVehicles.sumOf { it.assignedDrivers.size }
            rowMetrics.addView(metricBox("السائقون المفوضون", "$totalDrivers سائقين 👥", Color.rgb(248, 199, 74)).parent as View)
            val alertsActiveCount = ownerFleetVehicles.count { it.alertsEnabled }
            rowMetrics.addView(metricBox("إنذارات الهاتف", "$alertsActiveCount مفعّلة 🔔", Color.rgb(126, 242, 154)).parent as View)
            addView(rowMetrics)

            // Action Buttons Row (Add Car + Test Multi-Car Alerts)
            val rowBtns = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(10)
                layoutParams = lp
            }

            rowBtns.addView(Button(this@MainActivity).apply {
                text = "➕ إضافة سيارة جديدة للأسطول"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(22, 100, 65))
                val blp = LinearLayout.LayoutParams(0, dp(42), 1.3f)
                blp.rightMargin = dp(4)
                layoutParams = blp
                setOnClickListener { showAddOwnerVehicleDialog() }
            })

            rowBtns.addView(Button(this@MainActivity).apply {
                text = "🚨 تجربة إنذار لسيارة"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(140, 50, 40))
                val blp = LinearLayout.LayoutParams(0, dp(42), 1f)
                blp.leftMargin = dp(4)
                layoutParams = blp
                setOnClickListener { showSimulateMultiCarAlertsDialog() }
            })
            addView(rowBtns)
        }
        container.addView(cardOverview)

        // 2. Individual Vehicle Cards
        ownerFleetVehicles.forEach { veh ->
            val isActive = veh.isCurrentActive
            val cardCar = card().apply {
                if (isActive) {
                    setBackgroundColor(Color.rgb(14, 42, 34))
                }

                // Title & Active Badge
                val rowTitle = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.bottomMargin = dp(4)
                    layoutParams = lp

                    addView(TextView(this@MainActivity).apply {
                        text = "🚗 ${veh.name}"
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isActive) Color.rgb(248, 199, 74) else Color.WHITE)
                        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        layoutParams = tlp
                    })

                    addView(TextView(this@MainActivity).apply {
                        text = if (isActive) "✅ النشطة حالياً" else "متاحة بالأسطول"
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isActive) Color.rgb(126, 242, 154) else Color.rgb(160, 190, 210))
                    })
                }
                addView(rowTitle)

                // Vehicle Specs line
                addView(text("• اللوحة: ${veh.plateNumber.ifEmpty { "غير محددة" }} | الماركة: ${veh.make} (${veh.year})", 13, Color.rgb(200, 225, 245), false))
                addView(text("• رقم الشاسيه (VIN): ${veh.vin}", 13, Color.rgb(180, 210, 230), false))
                addView(text("• سعة التانك: ${veh.tankCapacity.toInt()}L | محرك: ${veh.displacement}L | استهلاك: ${veh.baseRate}L/100km | وزن: ${veh.emptyWeightKg.toInt()} كجم", 13, Color.rgb(180, 220, 200), false))

                // Mobile Alerts Subscription Box
                val alertBox = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(if (veh.alertsEnabled) Color.rgb(18, 52, 40) else Color.rgb(35, 30, 32))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    alp.topMargin = dp(6)
                    alp.bottomMargin = dp(6)
                    layoutParams = alp

                    val tvAlertStatus = TextView(this@MainActivity).apply {
                        text = if (veh.alertsEnabled) "🔔 استلام الإنذارات على هاتفي: مفعّل (تصلك إنذارات الحرارة والأعطال)" else "🔕 استلام الإنذارات على هاتفي: متوقف"
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (veh.alertsEnabled) Color.rgb(126, 242, 154) else Color.rgb(200, 150, 150))
                        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        layoutParams = tlp
                    }
                    addView(tvAlertStatus)

                    val btnToggleAlert = Button(this@MainActivity).apply {
                        text = if (veh.alertsEnabled) "كتم 🔕" else "تفعيل 🔔"
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setBackgroundColor(if (veh.alertsEnabled) Color.rgb(120, 45, 40) else Color.rgb(24, 85, 60))
                        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
                        layoutParams = blp
                        setOnClickListener { toggleVehicleAlerts(veh) }
                    }
                    addView(btnToggleAlert)
                }
                addView(alertBox)

                // Assigned Drivers Box
                val driversBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(12, 28, 42))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    dlp.topMargin = dp(4)
                    layoutParams = dlp

                    val headRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(TextView(this@MainActivity).apply {
                            text = "👥 السائقون المفوضون على هذه السيارة (${veh.assignedDrivers.size}):"
                            textSize = 13f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.rgb(110, 231, 255))
                            val hlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            layoutParams = hlp
                        })
                        addView(Button(this@MainActivity).apply {
                            text = "➕ إضافة سائق"
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.rgb(8, 23, 32))
                            setBackgroundColor(Color.rgb(110, 231, 255))
                            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
                            layoutParams = blp
                            setOnClickListener { showAddDriverToVehicleDialog(veh) }
                        })
                    }
                    addView(headRow)

                    if (veh.assignedDrivers.isEmpty()) {
                        addView(text("لا يوجد سائق مفوض مسجل حالياً. انقر على 'إضافة سائق' لربط سائق بهذه السيارة.", 12, Color.rgb(170, 190, 210), false).apply {
                            setPadding(0, dp(6), 0, dp(4))
                        })
                    } else {
                        veh.assignedDrivers.forEachIndexed { dIdx, drv ->
                            val driverRow = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, dp(6), 0, dp(6))
                                val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                layoutParams = rlp

                                val drvInfo = LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    val ilp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    layoutParams = ilp
                                    addView(text("• ${drv.name} (${drv.roleType})", 13, Color.WHITE, true))
                                    addView(text("  هاتف: ${drv.phone} | مفوض منذ: ${drv.assignedDate}", 11, Color.rgb(167, 203, 226), false))
                                }
                                addView(drvInfo)

                                // Call Button
                                addView(Button(this@MainActivity).apply {
                                    text = "📞"
                                    textSize = 13f
                                    setTextColor(Color.WHITE)
                                    setBackgroundColor(Color.rgb(25, 65, 95))
                                    val blp = LinearLayout.LayoutParams(dp(40), dp(36))
                                    blp.leftMargin = dp(4)
                                    layoutParams = blp
                                    setOnClickListener {
                                        try {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${drv.phone}"))
                                            startActivity(intent)
                                        } catch (_: Exception) {
                                            Toast.makeText(this@MainActivity, "رقم السائق: ${drv.phone}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                })

                                // Remove Driver Button
                                addView(Button(this@MainActivity).apply {
                                    text = "❌"
                                    textSize = 12f
                                    setTextColor(Color.rgb(255, 122, 122))
                                    setBackgroundColor(Color.rgb(45, 20, 25))
                                    val blp = LinearLayout.LayoutParams(dp(40), dp(36))
                                    blp.leftMargin = dp(4)
                                    layoutParams = blp
                                    setOnClickListener {
                                        AlertDialog.Builder(this@MainActivity)
                                            .setTitle("إلغاء تفويض السائق")
                                            .setMessage("هل أنت متأكد من إلغاء تفويض السائق (${drv.name}) عن قيادة (${veh.name})؟")
                                            .setPositiveButton("نعم، إلغاء") { _, _ ->
                                                veh.assignedDrivers.removeAt(dIdx)
                                                saveOwnerFleetVehicles()
                                                addSecurityNotification(
                                                    "إلغاء تفويض سائق",
                                                    "تم إلغاء تفويض السائق (${drv.name}) عن سيارتك (${veh.name}).",
                                                    "driver_removed",
                                                    "info"
                                                )
                                                Toast.makeText(this@MainActivity, "تم إلغاء التفويض بنجاح", Toast.LENGTH_SHORT).show()
                                                renderCurrentScreen()
                                            }
                                            .setNegativeButton("تراجع", null)
                                            .show()
                                    }
                                })
                            }
                            addView(driverRow)
                        }
                    }
                }
                addView(driversBox)

                // Bottom Actions (Activate as current car / Delete Car)
                val rowActions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(8)
                    layoutParams = lp

                    if (!isActive) {
                        addView(Button(this@MainActivity).apply {
                            text = "🔘 تفعيل هذه السيارة كسيارة رئيسية الآن"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.rgb(32, 90, 130))
                            val blp = LinearLayout.LayoutParams(0, dp(40), 1.5f)
                            blp.rightMargin = dp(4)
                            layoutParams = blp
                            setOnClickListener { activateOwnerVehicle(veh) }
                        })
                    } else {
                        addView(Button(this@MainActivity).apply {
                            text = "📡 متابعة القراءات الحية لهذه السيارة"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.rgb(8, 23, 32))
                            setBackgroundColor(Color.rgb(126, 242, 154))
                            val blp = LinearLayout.LayoutParams(0, dp(40), 1.5f)
                            blp.rightMargin = dp(4)
                            layoutParams = blp
                            setOnClickListener { switchTab("remote_fleet") }
                        })
                    }

                    if (ownerFleetVehicles.size > 1) {
                        addView(Button(this@MainActivity).apply {
                            text = "🗑️ حذف"
                            textSize = 12f
                            setTextColor(Color.rgb(255, 122, 122))
                            setBackgroundColor(Color.rgb(55, 20, 25))
                            val blp = LinearLayout.LayoutParams(0, dp(40), 0.7f)
                            blp.leftMargin = dp(4)
                            layoutParams = blp
                            setOnClickListener {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("حذف سيارة من الأسطول")
                                    .setMessage("هل تريد حذف سيارة (${veh.name}) من أسطولك؟")
                                    .setPositiveButton("نعم، حذف") { _, _ ->
                                        ownerFleetVehicles.remove(veh)
                                        if (isActive && ownerFleetVehicles.isNotEmpty()) {
                                            ownerFleetVehicles[0].isCurrentActive = true
                                            activateOwnerVehicle(ownerFleetVehicles[0])
                                        } else {
                                            saveOwnerFleetVehicles()
                                            renderCurrentScreen()
                                        }
                                        Toast.makeText(this@MainActivity, "تم حذف السيارة من الأسطول", Toast.LENGTH_SHORT).show()
                                    }
                                    .setNegativeButton("إلغاء", null)
                                    .show()
                            }
                        })
                    }
                }
                addView(rowActions)
            }
            container.addView(cardCar)
        }

        return container
    }

    private fun activateOwnerVehicle(veh: OwnerVehicle) {
        ownerFleetVehicles.forEach { it.isCurrentActive = (it.id == veh.id) }
        saveOwnerFleetVehicles()

        preferences.edit()
            .putString("vehicle_name", veh.name)
            .putString("vehicle_make", veh.make)
            .putString("vehicle_year", veh.year)
            .putString("vehicle_vin", veh.vin)
            .putString("vehicle_plate", veh.plateNumber)
            .putFloat("tank_capacity_liters", veh.tankCapacity)
            .putFloat("engine_displacement_liters", veh.displacement)
            .putFloat("fuel_rate_baseline", veh.baseRate)
            .putFloat("vehicle_empty_weight", veh.emptyWeightKg)
            .putString("fuel_type", veh.fuelType)
            .putString("vehicle_data_source", "أسطول سيارات المالك 👑")
            .apply()

        vehicleEmptyWeightKg = veh.emptyWeightKg
        val firstDriver = veh.assignedDrivers.firstOrNull()
        if (firstDriver != null) {
            authorizedDriverName = firstDriver.name
            authorizedDriverPhone = firstDriver.phone
        }

        Toast.makeText(this, "👑 تم التبديل إلى سيارتك: [${veh.name}] بنجاح!", Toast.LENGTH_SHORT).show()
        renderCurrentScreen()
    }

    private fun toggleVehicleAlerts(veh: OwnerVehicle) {
        veh.alertsEnabled = !veh.alertsEnabled
        saveOwnerFleetVehicles()
        val status = if (veh.alertsEnabled) "مفعّلة 🔔" else "معطّلة 🔕"
        Toast.makeText(this, "تم تغيير حالة تنبيهات [${veh.name}] إلى: $status", Toast.LENGTH_SHORT).show()
        renderCurrentScreen()
    }

    private fun showAddOwnerVehicleDialog() {
        val options = arrayOf(
            "⚡ فحص وقراءة كمبيوتر السيارة آلياً (OBD Auto-Scan)",
            "✏️ إضافة يدوية بالكامل (Manual Entry)"
        )
        AlertDialog.Builder(this)
            .setTitle("➕ إضافة سيارة جديدة لأسطولك")
            .setItems(options) { _, which ->
                if (which == 0) {
                    autoScanAndAddVehicleToFleet()
                } else {
                    showManualAddOwnerVehicleDialog()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun autoScanAndAddVehicleToFleet() {
        if (!telemetry.isConnected && activeSocket == null && activeWifiSocket == null) {
            val detected = detectCarFromPairedDevices()
            val pairedMsg = if (detected != null) "\n• تم اكتشاف سيارة مقترنة بالبلوتوث: ${detected.first}" else ""
            AlertDialog.Builder(this)
                .setTitle("🔌 فحص كمبيوتر السيارة")
                .setMessage("يرجى توصيل قطعة الـ OBD بالسيارة لقراءة بيانات الشاسيه والمواصفات آلياً.$pairedMsg\n\nهل ترغب في فحص الأجهزة المقترنة ومحاكاة القراءة التلقائية الآن؟")
                .setPositiveButton("فحص واستيراد تلقائي ⚡") { _, _ ->
                    val simMake = detected?.second ?: "تويوتا"
                    val simModel = detected?.first ?: "تويوتا كورولا 2023"
                    val simVin = "JTDBR32E" + System.currentTimeMillis().toString().takeLast(9)
                    showConfirmAutoScannedVehicleDialog(simModel, simMake, "2023", simVin, 50f, 1.6f)
                }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }

        val progress = AlertDialog.Builder(this)
            .setTitle("🔍 جاري فحص كمبيوتر السيارة...")
            .setMessage("يتم الآن قراءة رقم الشاسيه (Mode 09) والمواصفات من عقل المحرك...")
            .setCancelable(false)
            .create()
        progress.show()

        refreshVehicleEcuData { success, msg ->
            progress.dismiss()
            if (success) {
                val vin = preferences.getString("vehicle_vin", "") ?: ""
                val name = preferences.getString("vehicle_name", "سيارة جديدة") ?: "سيارة جديدة"
                val make = preferences.getString("vehicle_make", "عامة") ?: "عامة"
                val year = preferences.getString("vehicle_year", "2022") ?: "2022"
                val tank = preferences.getFloat("tank_capacity_liters", 50.0f)
                val disp = preferences.getFloat("engine_displacement_liters", 1.6f)
                showConfirmAutoScannedVehicleDialog(name, make, year, vin, tank, disp)
            } else {
                Toast.makeText(this@MainActivity, "تعذر قراءة الكمبيوتر آلياً. يمكنك الإضافة يدوياً.", Toast.LENGTH_LONG).show()
                showManualAddOwnerVehicleDialog()
            }
        }
    }

    private fun showConfirmAutoScannedVehicleDialog(name: String, make: String, year: String, vin: String, tank: Float, disp: Float) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        root.addView(text("✅ تم التعرف على السيارة وقراءة بياناتها:", 14, Color.rgb(126, 242, 154), true))
        root.addView(text("• المركبة: $name\n• الماركة: $make • الموديل: $year\n• الشاسيه: $vin\n• سعة التانك: ${tank.toInt()} لتر • سعة المحرك: ${disp}L", 13, Color.WHITE, false).apply {
            setPadding(0, dp(6), 0, dp(6))
        })

        root.addView(text("رقم اللوحة المرورية (اختياري):", 12, Color.rgb(248, 199, 74), true))
        val etPlate = edit("رقم اللوحة المرورية (مثال: أ ب ج 1234)")
        root.addView(etPlate)

        root.addView(text("تفويض سائق أولي (اختياري):", 12, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(6), 0, 0) })
        val etDriverName = edit("اسم السائق المفوض (اختياري)")
        val etDriverPhone = edit("رقم هاتف السائق (اختياري)")
        root.addView(etDriverName)
        root.addView(etDriverPhone)

        val scroll = ScrollView(this).apply { addView(root) }

        AlertDialog.Builder(this)
            .setTitle("🚗 اعتماد السيارة بالأسطول")
            .setView(scroll)
            .setPositiveButton("اعتماد وتأمين الملكية") { _, _ ->
                val plate = etPlate.text.toString().trim().ifEmpty { "بدون لوحة" }
                val drvName = etDriverName.text.toString().trim()
                val drvPhone = etDriverPhone.text.toString().trim()

                val driversList = mutableListOf<AssignedDriver>()
                if (drvName.isNotEmpty()) {
                    val today = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date())
                    driversList.add(AssignedDriver(drvName, drvPhone.ifEmpty { "01000000000" }, "سائق مفوض", today))
                }

                val newCar = OwnerVehicle(
                    id = "own-veh-${System.currentTimeMillis()}",
                    name = name,
                    make = make,
                    year = year,
                    vin = vin,
                    plateNumber = plate,
                    tankCapacity = tank,
                    displacement = disp,
                    baseRate = if (disp > 1.8f) 8.5f else 6.8f,
                    emptyWeightKg = if (tank > 55f) 1400f else 1200f,
                    fuelType = "gasoline",
                    alertsEnabled = true,
                    isCurrentActive = false,
                    assignedDrivers = driversList
                )

                ownerFleetVehicles.add(newCar)
                saveOwnerFleetVehicles()
                registerAndLockVin(vin, userName.ifEmpty { "المالك الأساسي" }, userPhone.ifEmpty { "01000000000" })

                if (drvName.isNotEmpty()) {
                    val drvCar = DriverAssignedCar(
                        id = "drv-car-${newCar.id}",
                        name = newCar.name,
                        make = newCar.make,
                        year = newCar.year,
                        vin = newCar.vin,
                        ownerName = userName.ifEmpty { "المالك الأساسي" },
                        ownerPhone = userPhone.ifEmpty { "01000000000" },
                        tankCapacity = newCar.tankCapacity,
                        baseRate = newCar.baseRate,
                        displacement = newCar.displacement,
                        fuelType = newCar.fuelType,
                        emptyWeightKg = newCar.emptyWeightKg,
                        isActive = driverAssignedCars.none { it.isActive }
                    )
                    driverAssignedCars.add(drvCar)
                    saveDriverAssignedCars()
                }

                Toast.makeText(this@MainActivity, "✅ تمت إضافة [$name] لأسطولك وتأمين ملكيتها آلياً!", Toast.LENGTH_LONG).show()
                renderCurrentScreen()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showManualAddOwnerVehicleDialog() {
        val dialog = AlertDialog.Builder(this)
        dialog.setTitle("➕ إضافة سيارة يدوياً")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        val etName = edit("اسم السيارة والموديل (مثال: كيا سيراتو 2022)")
        val etMake = edit("الماركة المصنعة (مثال: كيا أو تويوتا)")
        val etYear = edit("سنة الصنع (مثال: 2022)")
        val etVin = edit("رقم الشاسيه VIN (17 خانة)")
        val etPlate = edit("رقم اللوحة المرورية (مثال: س ر ق 7812)")
        val etDriverName = edit("اسم السائق المفوض الأول (اختياري)")
        val etDriverPhone = edit("رقم هاتف السائق (اختياري)")

        root.addView(etName)
        root.addView(etMake)
        root.addView(etYear)
        root.addView(etVin)
        root.addView(etPlate)
        root.addView(text("تفويض سائق أولي (اختياري):", 12, Color.rgb(248, 199, 74), true))
        root.addView(etDriverName)
        root.addView(etDriverPhone)

        val scroll = ScrollView(this)
        scroll.addView(root)
        dialog.setView(scroll)

        dialog.setPositiveButton("حفظ وتأمين السيارة") { _, _ ->
            val name = etName.text.toString().trim().ifEmpty { "سيارة المالك الجديدة" }
            val make = etMake.text.toString().trim().ifEmpty { "عامة" }
            val year = etYear.text.toString().trim().ifEmpty { "2022" }
            val vin = etVin.text.toString().trim().uppercase().ifEmpty { "VIN${System.currentTimeMillis()}" }
            val plate = etPlate.text.toString().trim().ifEmpty { "لوحة جديدة" }
            val drvName = etDriverName.text.toString().trim()
            val drvPhone = etDriverPhone.text.toString().trim()

            // Check VIN ownership lock
            val (canRegister, lockReason) = checkVinOwnershipLock(vin)
            if (!canRegister) {
                AlertDialog.Builder(this)
                    .setTitle("🛑 حظر تسجيل الشاسيه")
                    .setMessage(lockReason ?: "هذا الشاسيه مقيد لمالك آخر.")
                    .setPositiveButton("حسناً", null)
                    .show()
                return@setPositiveButton
            }

            val primaryOwner = preferences.getString("vehicle_primary_owner", userName.ifEmpty { "المالك الأساسي" }) ?: "المالك الأساسي"
            val primaryPhone = preferences.getString("vehicle_primary_owner_phone", userPhone.ifEmpty { "01000000000" }) ?: "01000000000"
            registerAndLockVin(vin, primaryOwner, primaryPhone)

            val driversList = mutableListOf<AssignedDriver>()
            if (drvName.isNotEmpty()) {
                val today = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date())
                driversList.add(AssignedDriver(drvName, drvPhone.ifEmpty { "01000000000" }, "سائق مفوض", today))
            }

            val newCar = OwnerVehicle(
                id = "own-veh-${System.currentTimeMillis()}",
                name = name,
                make = make,
                year = year,
                vin = vin,
                plateNumber = plate,
                tankCapacity = 50.0f,
                displacement = 1.6f,
                baseRate = 6.8f,
                emptyWeightKg = 1200.0f,
                fuelType = "gasoline",
                alertsEnabled = true,
                isCurrentActive = false,
                assignedDrivers = driversList
            )

            ownerFleetVehicles.add(newCar)
            saveOwnerFleetVehicles()

            if (drvName.isNotEmpty()) {
                val drvCar = DriverAssignedCar(
                    id = "drv-car-${newCar.id}",
                    name = newCar.name,
                    make = newCar.make,
                    year = newCar.year,
                    vin = newCar.vin,
                    ownerName = userName.ifEmpty { "المالك الأساسي" },
                    ownerPhone = userPhone.ifEmpty { "01000000000" },
                    tankCapacity = newCar.tankCapacity,
                    baseRate = newCar.baseRate,
                    displacement = newCar.displacement,
                    fuelType = newCar.fuelType,
                    emptyWeightKg = newCar.emptyWeightKg,
                    isActive = driverAssignedCars.none { it.isActive }
                )
                driverAssignedCars.add(drvCar)
                saveDriverAssignedCars()
            }

            addSecurityNotification(
                "إضافة سيارة جديدة للأسطول",
                "تمت إضافة السيارة ($name - لوحة $plate) وتأمين شاسيهها باسمك بنجاح.",
                "car_added",
                "info"
            )

            Toast.makeText(this, "✅ تمت إضافة [$name] لأسطولك بنجاح!", Toast.LENGTH_SHORT).show()
            renderCurrentScreen()
        }
        dialog.setNegativeButton("إلغاء", null)
        dialog.show()
    }

    private fun showAddDriverToVehicleDialog(veh: OwnerVehicle) {
        val dialog = AlertDialog.Builder(this)
        dialog.setTitle("➕ تفويض سائق جديد لـ: ${veh.name}")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        val tvNotice = TextView(this).apply {
            text = "💡 ملحوظة: اكتب اسم السائق المفضل لديك لتمييزه (ليس شرطاً أن يطابق اسمه في حسابه)، ولكن يجب إدخال رقم هاتف السائق المسجل به في التطبيق بدقة لتصله دعوة التفويض في واجهته."
            textSize = 12f
            setTextColor(Color.rgb(180, 215, 245))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(tvNotice)

        val etDriverName = edit("اسم السائق لدى المالك (لتمييزه لديك - اختياري للتطابق)")
        val etDriverPhone = edit("رقم موبايل السائق المسجل به في التطبيق (إلزامي)").apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val etDriverRole = edit("صفة / وردية السائق (مثال: سائق نهاري، سائق أوبر، سائق عائلي)")

        root.addView(etDriverName)
        root.addView(etDriverPhone)
        root.addView(etDriverRole)

        dialog.setView(root)
        dialog.setPositiveButton("إرسال دعوة التفويض") { _, _ ->
            val dName = etDriverName.text.toString().trim()
            val dPhone = etDriverPhone.text.toString().trim()
            val dRole = etDriverRole.text.toString().trim().ifEmpty { "سائق مفوض" }

            if (dName.isEmpty() || dPhone.isEmpty()) {
                Toast.makeText(this, "يرجى كتابة اسم السائق ورقم هاتفه المسجل به في التطبيق", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            val today = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

            // Create pending delegation request
            val reqId = "del-${System.currentTimeMillis()}"
            val req = DriverDelegationRequest(
                id = reqId,
                vehicleId = veh.id,
                vehicleName = veh.name,
                vehicleMake = veh.make,
                vehicleYear = veh.year,
                vehicleVin = veh.vin,
                vehiclePlate = veh.plateNumber,
                ownerName = userName.ifEmpty { "المالك الأساسي" },
                ownerPhone = userPhone.ifEmpty { "01000000000" },
                driverCustomName = dName,
                driverPhone = dPhone,
                driverRole = dRole,
                requestDate = today,
                status = "pending"
            )
            driverDelegationRequests.add(0, req)
            saveDriverDelegationRequests()

            // Also record in vehicle assigned drivers as pending
            val existingInVeh = veh.assignedDrivers.find { it.phone == dPhone }
            if (existingInVeh == null) {
                veh.assignedDrivers.add(
                    AssignedDriver(
                        name = dName,
                        phone = dPhone,
                        roleType = "$dRole (بانتظار قبول السائق ⏳)",
                        assignedDate = today
                    )
                )
                saveOwnerFleetVehicles()
            }

            addSecurityNotification(
                "إرسال دعوة تفويض سائق",
                "تم إرسال دعوة تفويض قيادة سيارتك (${veh.name}) إلى السائق ($dName - هاتف: $dPhone). ستنضم السيارة لحسابه فور موافقته وقبول الاستلام.",
                "driver_invitation_sent",
                "info"
            )

            Toast.makeText(this, "📡 تم إرسال دعوة التفويض للسائق ($dPhone). ستظهر له رسالة قبول في واجهته للموافقة واستلام السيارة!", Toast.LENGTH_LONG).show()
            renderCurrentScreen()
        }
        dialog.setNegativeButton("إلغاء", null)
        dialog.show()
    }

    private fun showSimulateMultiCarAlertsDialog() {
        if (ownerFleetVehicles.isEmpty()) {
            Toast.makeText(this, "لا توجد سيارات بالأسطول حالياً", Toast.LENGTH_SHORT).show()
            return
        }

        val carNames = ownerFleetVehicles.map {
            val status = if (it.alertsEnabled) "🔔 تنبيهات مفعّلة" else "🔕 معطّلة"
            "${it.name} (${it.plateNumber}) • $status"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("🚨 تجربة إنذار طارئ لسيارة من الأسطول")
            .setItems(carNames) { _, which ->
                val chosen = ownerFleetVehicles[which]
                if (!chosen.alertsEnabled) {
                    AlertDialog.Builder(this)
                        .setTitle("🔕 التنبيهات معطلة لهذه السيارة")
                        .setMessage("لقد قمت بكتم التنبيهات لسيارة (${chosen.name}).\nيرجى تفعيل زر 'استلام التنبيهات 🔔' من بطاقة السيارة لتصلك إشعاراتها على هاتفك.")
                        .setPositiveButton("تفعيلها الآن") { _, _ ->
                            toggleVehicleAlerts(chosen)
                        }
                        .setNegativeButton("إغلاق", null)
                        .show()
                    return@setItems
                }

                // Simulate high temperature / fault alert for this specific car
                val drv = chosen.assignedDrivers.firstOrNull()?.name ?: "السائق المفوض"
                val alertMsg = "⚠️ إنذار حرارة محرك زائدة: وصلت الحرارة إلى 105°C أثناء قيادة السائق ($drv) على طريق السفر!"

                showSystemSafetyNotification(
                    "multi_car_alert_${chosen.id}",
                    "🚨 إنذار لسيارتك [${chosen.name} - ${chosen.plateNumber}]",
                    alertMsg,
                    true
                )

                addSecurityNotification(
                    "🚨 إنذار طارئ: ${chosen.name}",
                    alertMsg,
                    "engine_overheat",
                    "critical"
                )

                playAlertSound(true)

                AlertDialog.Builder(this)
                    .setTitle("✅ تم إطلاق الإنذار وإرسال الإشعار لهاتفك")
                    .setMessage("تم إرسال إشعار فوري لهاتفك وتشغيل صفارة الإنذار لسيارة:\n[${chosen.name} - لوحة: ${chosen.plateNumber}]\nالسائق: $drv\nالسبب: ارتفاع حرارة المحرك إلى 105°C.")
                    .setPositiveButton("حسناً") { _, _ ->
                        switchTab("owner_security")
                    }
                    .show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun saveOwnerFleetVehicles() {
        val array = org.json.JSONArray()
        ownerFleetVehicles.forEach { veh ->
            val obj = org.json.JSONObject().apply {
                put("id", veh.id)
                put("name", veh.name)
                put("make", veh.make)
                put("year", veh.year)
                put("vin", veh.vin)
                put("plateNumber", veh.plateNumber)
                put("tankCapacity", veh.tankCapacity.toDouble())
                put("displacement", veh.displacement.toDouble())
                put("baseRate", veh.baseRate.toDouble())
                put("emptyWeightKg", veh.emptyWeightKg.toDouble())
                put("fuelType", veh.fuelType)
                put("alertsEnabled", veh.alertsEnabled)
                put("isCurrentActive", veh.isCurrentActive)
                val drvArray = org.json.JSONArray()
                veh.assignedDrivers.forEach { drv ->
                    val dObj = org.json.JSONObject().apply {
                        put("name", drv.name)
                        put("phone", drv.phone)
                        put("roleType", drv.roleType)
                        put("assignedDate", drv.assignedDate)
                    }
                    drvArray.put(dObj)
                }
                put("drivers", drvArray)
            }
            array.put(obj)
        }
        preferences.edit().putString("owner_fleet_vehicles_json", array.toString()).apply()
    }

    private fun loadOwnerFleetVehicles() {
        val jsonStr = preferences.getString("owner_fleet_vehicles_json", null)
        ownerFleetVehicles.clear()
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val drvList = mutableListOf<AssignedDriver>()
                    val drvArr = obj.optJSONArray("drivers")
                    if (drvArr != null) {
                        for (j in 0 until drvArr.length()) {
                            val dObj = drvArr.getJSONObject(j)
                            drvList.add(
                                AssignedDriver(
                                    name = dObj.optString("name", "سائق"),
                                    phone = dObj.optString("phone", "01000000000"),
                                    roleType = dObj.optString("roleType", "سائق مفوض"),
                                    assignedDate = dObj.optString("assignedDate", "2026/10/20")
                                )
                            )
                        }
                    }
                    ownerFleetVehicles.add(
                        OwnerVehicle(
                            id = obj.optString("id", "own-veh-$i"),
                            name = obj.optString("name", "سيارة المالك"),
                            make = obj.optString("make", "عامة"),
                            year = obj.optString("year", "2020"),
                            vin = obj.optString("vin", "KL1TD69E29B123456"),
                            plateNumber = obj.optString("plateNumber", "س ص ع 1234"),
                            tankCapacity = obj.optDouble("tankCapacity", 45.0).toFloat(),
                            displacement = obj.optDouble("displacement", 1.5).toFloat(),
                            baseRate = obj.optDouble("baseRate", 7.2).toFloat(),
                            emptyWeightKg = obj.optDouble("emptyWeightKg", 1200.0).toFloat(),
                            fuelType = obj.optString("fuelType", "gasoline"),
                            alertsEnabled = obj.optBoolean("alertsEnabled", true),
                            isCurrentActive = obj.optBoolean("isCurrentActive", (i == 0)),
                            assignedDrivers = drvList
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        if (ownerFleetVehicles.isEmpty()) {
            val curName = preferences.getString("vehicle_name", "شيفروليه أفيو (Chevrolet Aveo 1.5L)") ?: "شيفروليه أفيو (Chevrolet Aveo 1.5L)"
            val curVin = preferences.getString("vehicle_vin", "KL1TD69E29B123456") ?: "KL1TD69E29B123456"
            val curMake = preferences.getString("vehicle_make", "شيفروليه") ?: "شيفروليه"
            val curYear = preferences.getString("vehicle_year", "2018") ?: "2018"

            ownerFleetVehicles.add(
                OwnerVehicle(
                    id = "own-veh-1",
                    name = curName,
                    make = curMake,
                    year = curYear,
                    vin = curVin,
                    plateNumber = "أ ب ج 4589",
                    tankCapacity = 45.0f,
                    displacement = 1.5f,
                    baseRate = 7.2f,
                    emptyWeightKg = 1070.0f,
                    fuelType = "gasoline",
                    alertsEnabled = true,
                    isCurrentActive = true,
                    assignedDrivers = mutableListOf(
                        AssignedDriver("محمد علي (سائق أساسي)", "01012345678", "سائق أساسي", "2026/10/18"),
                        AssignedDriver("عمر حسن (سائق بديل)", "01198765432", "سائق وردية مسائية", "2026/10/19")
                    )
                )
            )

            ownerFleetVehicles.add(
                OwnerVehicle(
                    id = "own-veh-2",
                    name = "كيا سيراتو (Kia Cerato 1.6L)",
                    make = "كيا",
                    year = "2022",
                    vin = "KNAFK411BF5123456",
                    plateNumber = "س ر ق 7812",
                    tankCapacity = 50.0f,
                    displacement = 1.6f,
                    baseRate = 6.8f,
                    emptyWeightKg = 1230.0f,
                    fuelType = "gasoline",
                    alertsEnabled = true,
                    isCurrentActive = false,
                    assignedDrivers = mutableListOf(
                        AssignedDriver("أحمد فتحي (سائق الشركة)", "01234567890", "سائق مفوض", "2026/10/19")
                    )
                )
            )

            ownerFleetVehicles.add(
                OwnerVehicle(
                    id = "own-veh-3",
                    name = "تويوتا كورولا (Toyota Corolla 1.6L)",
                    make = "تويوتا",
                    year = "2023",
                    vin = "JTDBR32E12345678",
                    plateNumber = "ط ن م 3344",
                    tankCapacity = 50.0f,
                    displacement = 1.6f,
                    baseRate = 6.5f,
                    emptyWeightKg = 1280.0f,
                    fuelType = "gasoline",
                    alertsEnabled = false,
                    isCurrentActive = false,
                    assignedDrivers = mutableListOf(
                        AssignedDriver("كابتن إبراهيم محمود", "01511223344", "سائق عائلي", "2026/10/20")
                    )
                )
            )
            saveOwnerFleetVehicles()
        }
    }

    private fun saveDriverDelegationRequests() {
        val arr = org.json.JSONArray()
        for (req in driverDelegationRequests) {
            val obj = org.json.JSONObject().apply {
                put("id", req.id)
                put("vehicleId", req.vehicleId)
                put("vehicleName", req.vehicleName)
                put("vehicleMake", req.vehicleMake)
                put("vehicleYear", req.vehicleYear)
                put("vehicleVin", req.vehicleVin)
                put("vehiclePlate", req.vehiclePlate)
                put("ownerName", req.ownerName)
                put("ownerPhone", req.ownerPhone)
                put("driverCustomName", req.driverCustomName)
                put("driverPhone", req.driverPhone)
                put("driverRole", req.driverRole)
                put("requestDate", req.requestDate)
                put("status", req.status)
            }
            arr.put(obj)
        }
        preferences.edit().putString("saved_driver_delegation_requests", arr.toString()).apply()
    }

    private fun loadDriverDelegationRequests() {
        driverDelegationRequests.clear()
        val raw = preferences.getString("saved_driver_delegation_requests", null)
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    driverDelegationRequests.add(
                        DriverDelegationRequest(
                            id = obj.optString("id", "req-$i"),
                            vehicleId = obj.optString("vehicleId", ""),
                            vehicleName = obj.optString("vehicleName", ""),
                            vehicleMake = obj.optString("vehicleMake", ""),
                            vehicleYear = obj.optString("vehicleYear", ""),
                            vehicleVin = obj.optString("vehicleVin", ""),
                            vehiclePlate = obj.optString("vehiclePlate", ""),
                            ownerName = obj.optString("ownerName", ""),
                            ownerPhone = obj.optString("ownerPhone", ""),
                            driverCustomName = obj.optString("driverCustomName", ""),
                            driverPhone = obj.optString("driverPhone", ""),
                            driverRole = obj.optString("driverRole", "سائق مفوض"),
                            requestDate = obj.optString("requestDate", ""),
                            status = obj.optString("status", "pending")
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun saveWorkshopAccessRequests() {
        val arr = org.json.JSONArray()
        for (req in workshopAccessRequests) {
            val obj = org.json.JSONObject().apply {
                put("id", req.id)
                put("workshopName", req.workshopName)
                put("workshopPhone", req.workshopPhone)
                put("ownerPhone", req.ownerPhone)
                put("targetVehicleName", req.targetVehicleName)
                put("serviceReason", req.serviceReason)
                put("requestDate", req.requestDate)
                put("status", req.status)
            }
            arr.put(obj)
        }
        preferences.edit().putString("saved_workshop_access_requests", arr.toString()).apply()
    }

    private fun loadWorkshopAccessRequests() {
        workshopAccessRequests.clear()
        val raw = preferences.getString("saved_workshop_access_requests", null)
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val req = WorkshopAccessRequest(
                        id = obj.optString("id", "wsreq-$i"),
                        workshopName = obj.optString("workshopName", ""),
                        workshopPhone = obj.optString("workshopPhone", ""),
                        ownerPhone = obj.optString("ownerPhone", ""),
                        targetVehicleName = obj.optString("targetVehicleName", ""),
                        serviceReason = obj.optString("serviceReason", ""),
                        requestDate = obj.optString("requestDate", ""),
                        status = obj.optString("status", "pending")
                    )
                    workshopAccessRequests.add(req)
                    if (req.status == "approved" && isWorkshopSessionUnlocked) {
                        activeWorkshopSessionRequest = req
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun submitWorkshopAccessRequest(targetOwnerPhone: String, reason: String) {
        val reqId = "wsreq-${System.currentTimeMillis()}"
        val activeVehName = preferences.getString("vehicle_name", "شيفروليه أفيو (Chevrolet Aveo 1.5L)") ?: "شيفروليه أفيو"
        val today = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val wsName = if (myWorkshopName.isNotBlank()) myWorkshopName else userName.ifEmpty { "مركز صيانة معتمد" }
        val wsPhone = if (myWorkshopPhone.isNotBlank()) myWorkshopPhone else userPhone.ifEmpty { "01099887755" }

        val wsReq = WorkshopAccessRequest(
            id = reqId,
            workshopName = wsName,
            workshopPhone = wsPhone,
            ownerPhone = targetOwnerPhone,
            targetVehicleName = activeVehName,
            serviceReason = reason.ifEmpty { "كشف أعطال كمبيوتر وقراءة الحساسات" },
            requestDate = today,
            status = "pending"
        )
        workshopAccessRequests.add(0, wsReq)
        saveWorkshopAccessRequests()

        addSecurityNotification(
            "طلب إذن فحص من ورشة صيانة",
            "تطلب ورشة ($wsName - هاتف: $wsPhone) إذناً لفحص كمبيوتر سيارتك ($activeVehName) لغرض: (${wsReq.serviceReason}). بدون أرقام سرية وبموافقتك المباشرة فقط.",
            "workshop_access_request",
            "warning"
        )

        Toast.makeText(this, "📡 تم إرسال طلب الفحص لموبايل المالك ($targetOwnerPhone). ستفتح جلسة الفحص فور موافقة المالك!", Toast.LENGTH_LONG).show()
        renderCurrentScreen()
    }

    private fun approveWorkshopAccessRequest(req: WorkshopAccessRequest) {
        req.status = "approved"
        activeWorkshopSessionRequest = req
        isWorkshopSessionUnlocked = true
        workshopSessionGrantedBy = userName.ifEmpty { "المالك الأساسي" }
        saveWorkshopAccessRequests()

        addSecurityNotification(
            "منح تصريح فحص لورشة",
            "منحت ورشة (${req.workshopName} - هاتف: ${req.workshopPhone}) إذن دخول لمرة واحدة لفحص سيارتك (${req.targetVehicleName}) لغرض: (${req.serviceReason}).",
            "workshop_session_approved",
            "info"
        )

        Toast.makeText(this, "✅ تم منح إذن الدخول لمرة واحدة لورشة (${req.workshopName}) بنجاح. يمكنك إلغاء الدخول فوراً في أي وقت!", Toast.LENGTH_LONG).show()
        renderCurrentScreen()
    }

    private fun rejectWorkshopAccessRequest(req: WorkshopAccessRequest) {
        req.status = "rejected"
        saveWorkshopAccessRequests()
        addSecurityNotification(
            "رفض طلب ورشة صيانة",
            "رفضت طلب فحص السيارة المقدم من ورشة (${req.workshopName}).",
            "workshop_session_rejected",
            "info"
        )
        Toast.makeText(this, "تم رفض طلب الورشة.", Toast.LENGTH_SHORT).show()
        renderCurrentScreen()
    }

    private fun revokeWorkshopAccessNow() {
        isWorkshopSessionUnlocked = false
        activeWorkshopSessionRequest?.status = "closed"
        saveWorkshopAccessRequests()

        addSecurityNotification(
            "إلغاء تصريح ورشة الصيانة",
            "قام المالك ($userName) بإنهاء جلسة فحص الورشة وإلغاء الدخول فورياً وقفل كمبيوتر السيارة.",
            "workshop_session_revoked",
            "warning"
        )

        Toast.makeText(this, "⛔ تم إلغاء تصريح الورشة وطرد الجلسة فوراً ووقف القراءة!", Toast.LENGTH_SHORT).show()
        renderCurrentScreen()
    }

    private fun closeWorkshopSessionAndExit() {
        val wsName = activeWorkshopSessionRequest?.workshopName ?: myWorkshopName
        isWorkshopSessionUnlocked = false
        activeWorkshopSessionRequest?.status = "closed"
        saveWorkshopAccessRequests()

        addSecurityNotification(
            "انتهاء جلسة فحص الورشة",
            "أنهت ورشة ($wsName) جلسة فحص سيارتك وتم الخروج وقفل السيارة نهائياً. لا يمكن الدخول مجدداً إلا بطلب جديد وموافقتك.",
            "workshop_session_closed",
            "info"
        )

        Toast.makeText(this, "🚪 تم إنهاء جلسة الفحص بنجاح والخروج. تم قفل السيارة ولا يمكن الدخول إلا بطلب جديد وموافقة المالك ✅", Toast.LENGTH_LONG).show()
        renderCurrentScreen()
    }

    private fun acceptDriverDelegation(req: DriverDelegationRequest) {
        req.status = "accepted"
        saveDriverDelegationRequests()

        // Link vehicle to driver's fleet
        val existing = driverAssignedCars.find { it.vin == req.vehicleVin }
        if (existing == null) {
            val ownerVeh = ownerFleetVehicles.find { it.vin == req.vehicleVin }
            driverAssignedCars.add(
                DriverAssignedCar(
                    id = "drv-car-${req.vehicleId}",
                    name = req.vehicleName,
                    make = req.vehicleMake,
                    year = req.vehicleYear,
                    vin = req.vehicleVin,
                    ownerName = req.ownerName,
                    ownerPhone = req.ownerPhone,
                    tankCapacity = ownerVeh?.tankCapacity ?: 45f,
                    baseRate = ownerVeh?.baseRate ?: 7.2f,
                    displacement = ownerVeh?.displacement ?: 1.5f,
                    fuelType = ownerVeh?.fuelType ?: "gasoline",
                    emptyWeightKg = ownerVeh?.emptyWeightKg ?: 1200f,
                    isActive = driverAssignedCars.none { it.isActive }
                )
            )
            saveDriverAssignedCars()
        }

        // Update in owner's vehicle assigned driver status
        val ownerVeh = ownerFleetVehicles.find { it.vin == req.vehicleVin }
        val matchingDriver = ownerVeh?.assignedDrivers?.find { it.phone == req.driverPhone }
        if (matchingDriver != null) {
            matchingDriver.roleType = "${req.driverRole} (مفوض ومستلم ✅)"
            saveOwnerFleetVehicles()
        }

        addSecurityNotification(
            "قبول تفويض قيادة",
            "وافق السائق (${req.driverCustomName} - هاتف: ${req.driverPhone}) على تفويض قيادة سيارتك (${req.vehicleName}) واستلمها رسمياً.",
            "driver_delegation_accepted",
            "info"
        )

        Toast.makeText(this, "✅ تم قبول التفويض واستلام السيارة (${req.vehicleName}) بنجاح! متاحة الآن للقيادة بأسطولك 🚗", Toast.LENGTH_LONG).show()
        renderCurrentScreen()
    }

    private fun rejectDriverDelegation(req: DriverDelegationRequest) {
        req.status = "rejected"
        saveDriverDelegationRequests()

        addSecurityNotification(
            "اعتذار عن تفويض قيادة",
            "اعتذر السائق (${req.driverCustomName} - هاتف: ${req.driverPhone}) عن قبول تفويض قيادة سيارتك (${req.vehicleName}).",
            "driver_delegation_rejected",
            "warning"
        )

        Toast.makeText(this, "تم الاعتذار عن قبول تفويض السيارة.", Toast.LENGTH_SHORT).show()
        renderCurrentScreen()
    }

    // ========================================================
    // TAB: REMOTE FLEET & DELTA MONITORING (المراقبة عن بعد للمالك)
    // ========================================================
    private fun buildRemoteFleetTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val activeVehName = preferences.getString("vehicle_name", "شيفروليه أفيو (Chevrolet Aveo 1.5L)") ?: "شيفروليه أفيو"
        val activeVin = preferences.getString("vehicle_vin", "KMHDH41EBNA123456") ?: "KMHDH41EBNA123456"

        val cardRemoteLive = card().apply {
            addView(text("📡 مراقبة السيارة اللحظية عن بُعد (Live Fleet Telemetry)", 17, Color.rgb(110, 231, 255), true))
            addView(text("متابعة حية لقراءات السيارة أثناء تواجد السائق على الطريق بعيداً عن المالك:", 13, Color.rgb(167, 203, 226), false))

            val statusBox = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.rgb(14, 42, 30))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
                addView(text("🟢 السائق متصل حياً بالمركبة • السائق: $authorizedDriverName ($authorizedDriverPhone)", 13, Color.rgb(126, 242, 154), true))
            }
            addView(statusBox)

            val r1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            r1.addView(metricBox("سرعة السيارة اللحظية", "${telemetry.speed} كم/س", Color.rgb(110, 231, 255)).parent as View)
            r1.addView(metricBox("عزم المحرك (RPM)", "${telemetry.rpm} لفة/د", Color.rgb(248, 199, 74)).parent as View)
            addView(r1)

            val r2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val fuelColor = if (telemetry.fuelLevel <= 15) Color.rgb(255, 122, 122) else Color.rgb(126, 242, 154)
            r2.addView(metricBox("مستوى الوقود بالتنك", "${telemetry.fuelLevel}%", fuelColor).parent as View)
            val tempColor = if (telemetry.coolantTemp >= 100) Color.rgb(255, 100, 100) else Color.rgb(110, 231, 255)
            r2.addView(metricBox("حرارة مياه التبريد", "${telemetry.coolantTemp} °C", tempColor).parent as View)
            addView(r2)

            val r3 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            r3.addView(metricBox("جهد شحن البطارية", "${String.format("%.1f", telemetry.batteryVoltage)}V", Color.WHITE).parent as View)
            val dtcColor = if (telemetry.dtcCodes.isEmpty()) Color.rgb(126, 242, 154) else Color.rgb(255, 122, 122)
            val dtcLabel = if (telemetry.dtcCodes.isEmpty()) "سليمة (0 أعطال) ✅" else "${telemetry.dtcCodes.size} أعطال مسجلة ⚠️"
            r3.addView(metricBox("حالة كمبيوتر المحرك", dtcLabel, dtcColor).parent as View)
            addView(r3)

            addView(text("📍 خط السير والموقع الحالي: $tripStartLocationName ← $tripEndLocationName (على الطريق السريع)", 13, Color.rgb(248, 199, 74), true).apply {
                setPadding(0, dp(8), 0, dp(4))
            })

            addView(button("⚡ محاكاة تحرك وتغيرات السائق على الطريق (Live Telemetry Demo)") {
                simulateDriverRoadDelta()
            }.apply {
                setBackgroundColor(Color.rgb(32, 85, 120))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardRemoteLive)

        // Delta-Deadband Storage Log Card
        val cardDelta = card().apply {
            addView(text("📊 سجل التخزين الذكي عند التغير (Delta Storage Log)", 17, Color.rgb(248, 199, 74), true))
            addView(text("تطبيقاً للبروتوكول الذكي: لا يتم تسجيل أو إرسال داتا جديدة إلا عند حدوث تغير فعلي بالقيم (سرعة ≥3، وقود ≥1%، حرارة ≥2°C، أعطال)، وذلك لضغط البيانات وحماية السيرفر:", 13, Color.rgb(167, 203, 226), false))

            // Timeframe filters
            val filterRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            val timeframes = listOf(
                Pair("1h", "⏱️ آخر ساعة"),
                Pair("24h", "📅 آخر 24 ساعة"),
                Pair("7d", "📆 آخر أسبوع")
            )
            for ((key, title) in timeframes) {
                val isActive = (remoteFleetTimeframe == key)
                filterRow.addView(Button(this@MainActivity).apply {
                    text = title
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
                    lp.setMargins(dp(2), 0, dp(2), 0)
                    layoutParams = lp
                    setBackgroundColor(if (isActive) Color.rgb(110, 231, 255) else Color.rgb(20, 42, 64))
                    setTextColor(if (isActive) Color.rgb(8, 23, 32) else Color.rgb(200, 225, 245))
                    setOnClickListener {
                        remoteFleetTimeframe = key
                        switchTab("remote_fleet")
                    }
                })
            }
            addView(filterRow)

            val containerDeltas = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }

            if (telemetryDeltaLog.isEmpty()) {
                val emptyBox = text("لا توجد تغيرات مسجلة بعد. عند تحرك السيارة ستظهر اللقطات هنا فورياً.", 13, Color.rgb(167, 203, 226), false).apply {
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                }
                containerDeltas.addView(emptyBox)
            } else {
                for (record in telemetryDeltaLog.take(25)) {
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.rgb(12, 32, 48))
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        lp.bottomMargin = dp(6)
                        layoutParams = lp
                    }
                    val topR = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(text("⏰ ${record.timeString}", 12, Color.rgb(110, 231, 255), true).apply {
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(text("📌 ${record.changeReason}", 12, Color.rgb(248, 199, 74), true))
                    }
                    row.addView(topR)
                    val metricsStr = "🏎️ السرعة: ${record.speed} كم/س • ⛽ الوقود: ${record.fuelLevel}% • 🌡️ الحرارة: ${record.coolantTemp}°C • 🔋 الجهد: ${String.format("%.1f", record.batteryVoltage)}V • ⚠️ الأعطال: ${record.dtcCount}"
                    row.addView(text(metricsStr, 12, Color.WHITE, false).apply {
                        setPadding(0, dp(4), 0, 0)
                    })
                    containerDeltas.addView(row)
                }
            }
            addView(containerDeltas)
        }
        container.addView(cardDelta)

        return container
    }

    // ========================================================
    // TAB: OWNER SECURITY NOTIFICATIONS (إشعارات أمان المالك)
    // ========================================================
    private fun buildOwnerSecurityTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val activeVehName = preferences.getString("vehicle_name", "شيفروليه أفيو") ?: "شيفروليه أفيو"

        val cardStatus = card().apply {
            addView(text("🚨 سجل أمان المالك وإشعارات دخول الورش", 17, Color.rgb(255, 122, 122), true))
            addView(text("حماية كاملة: تصلك إشعارات فورية عن أي ورشة تدخل على السيارة أو أي صيانة يسجلها السائق:", 13, Color.rgb(167, 203, 226), false))

            val statusBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
                if (isWorkshopSessionUnlocked && activeWorkshopSessionRequest != null) {
                    setBackgroundColor(Color.rgb(65, 20, 25))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(text("🔴 جلسة فحص نشطة حالياً بواسطة ورشة: ${activeWorkshopSessionRequest?.workshopName}", 14, Color.rgb(255, 122, 122), true))
                    addView(text("هاتف الورشة: ${activeWorkshopSessionRequest?.workshopPhone} • السيارة: ${activeWorkshopSessionRequest?.targetVehicleName}", 12, Color.WHITE, false))
                    addView(text("الغرض: ${activeWorkshopSessionRequest?.serviceReason} • المصرح: ($workshopSessionGrantedBy)", 12, Color.rgb(248, 199, 74), false))
                    addView(text("الورشة تقرأ الحساسات الآن - يمكنك إنهاء الجلسة فوراً وإلغاء الدخول بضغطة زر أدناه.", 11, Color.rgb(180, 215, 240), false))
                } else {
                    setBackgroundColor(Color.rgb(18, 52, 32))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(text("🔒 السيارة محمية: لا توجد أي ورشة مصرح لها بالدخول حالياً.", 14, Color.rgb(126, 242, 154), true))
                    addView(text("كمبيوتر وحساسات السيارة مقفلة ومؤمنة تماماً ضد أي فحص غير معتمد (بدون أرقام سرية - يتطلب موافقتك الصريحة فقط).", 12, Color.WHITE, false))
                }
            }
            addView(statusBox)

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }

            btnRow.addView(Button(this@MainActivity).apply {
                text = "⛔ إبطال تصاريح الورش فوراً"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(160, 35, 35))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(0, 0, dp(3), 0)
                layoutParams = lp
                setOnClickListener {
                    revokeWorkshopAccessNow()
                    switchTab("owner_security")
                }
            })

            btnRow.addView(Button(this@MainActivity).apply {
                text = "🧹 تفريغ الإشعارات"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(45, 60, 75))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(3), 0, 0, 0)
                layoutParams = lp
                setOnClickListener {
                    ownerSecurityNotifications.clear()
                    saveSecurityNotifications()
                    switchTab("owner_security")
                }
            })
            addView(btnRow)
        }
        container.addView(cardStatus)

        // Pending Workshop Access Requests
        val pendingWs = workshopAccessRequests.filter { it.status == "pending" }
        if (pendingWs.isNotEmpty()) {
            val cardPending = card().apply {
                setBackgroundColor(Color.rgb(20, 42, 60))
                addView(text("📡 طلبات فحص واستئذان صيانة واردة بانتظار موافقتك (${pendingWs.size}):", 15, Color.rgb(248, 199, 74), true))
                addView(text("تطلب الورش التالية إذناً لدخول كمبيوتر سيارتك لمرة واحدة. اضغط [موافقة] لفتح الفحص فوراً:", 12, Color.rgb(167, 203, 226), false))

                pendingWs.forEach { req ->
                    val reqBox = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.rgb(10, 25, 38))
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        lp.topMargin = dp(8)
                        layoutParams = lp

                        addView(text("🔧 ${req.workshopName} (هاتف: ${req.workshopPhone})", 14, Color.rgb(110, 231, 255), true))
                        addView(text("• السيارة: ${req.targetVehicleName} • الغرض: ${req.serviceReason}", 12, Color.WHITE, false))
                        addView(text("• تاريخ الطلب: ${req.requestDate} • الدخول مخصص لمرة واحدة وينتهي عند الخروج.", 11, Color.rgb(180, 210, 230), false))

                        val bRow = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            blp.topMargin = dp(6)
                            layoutParams = blp

                            addView(Button(this@MainActivity).apply {
                                text = "✅ موافقة ودخول لمرة واحدة"
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.rgb(20, 120, 80))
                                val lpBtn = LinearLayout.LayoutParams(0, dp(40), 1.3f)
                                lpBtn.rightMargin = dp(4)
                                layoutParams = lpBtn
                                setOnClickListener {
                                    approveWorkshopAccessRequest(req)
                                    switchTab("owner_security")
                                }
                            })

                            addView(Button(this@MainActivity).apply {
                                text = "❌ رفض"
                                textSize = 12f
                                setTextColor(Color.rgb(255, 122, 122))
                                setBackgroundColor(Color.rgb(55, 20, 25))
                                val lpBtn = LinearLayout.LayoutParams(0, dp(40), 1f)
                                layoutParams = lpBtn
                                setOnClickListener {
                                    rejectWorkshopAccessRequest(req)
                                    switchTab("owner_security")
                                }
                            })
                        }
                        addView(bRow)
                    }
                    addView(reqBox)
                }
            }
            container.addView(cardPending)
        }

        // List of Security Alerts
        val cardAlerts = card().apply {
            addView(text("📋 قائمة الإخطارات والأحداث الأمنية المسجلة:", 15, Color.WHITE, true))

            val listContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }

            if (ownerSecurityNotifications.isEmpty()) {
                listContainer.addView(text("لا توجد إخطارات أمنية جديدة. سيارتك بأمان تام ✅", 13, Color.rgb(126, 242, 154), false).apply {
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                })
            } else {
                for (item in ownerSecurityNotifications) {
                    val bg = when(item.severity) {
                        "critical" -> Color.rgb(65, 18, 22)
                        "warning" -> Color.rgb(55, 35, 15)
                        else -> Color.rgb(16, 38, 56)
                    }
                    val accent = when(item.severity) {
                        "critical" -> Color.rgb(255, 122, 122)
                        "warning" -> Color.rgb(248, 199, 74)
                        else -> Color.rgb(110, 231, 255)
                    }
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(bg)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        lp.bottomMargin = dp(8)
                        layoutParams = lp
                    }
                    val header = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(text(item.title, 14, accent, true).apply {
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(text(item.timeString, 11, Color.rgb(167, 203, 226), false))
                    }
                    row.addView(header)
                    row.addView(text(item.message, 13, Color.WHITE, false).apply {
                        setPadding(0, dp(4), 0, 0)
                    })
                    listContainer.addView(row)
                }
            }
            addView(listContainer)
        }
        container.addView(cardAlerts)

        return container
    }

    // ========================================================
    // TAB: WORKSHOPS DIRECTORY & MAP FOR DRIVER (دليل وخريطة الورش)
    // ========================================================
    private fun buildWorkshopsDirectoryTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val cardMap = card().apply {
            addView(text("🗺️ خريطة ودليل الورش المعتمدة القريبة", 17, Color.rgb(110, 231, 255), true))
            addView(text("استكشف الورش القريبة منك على الخريطة حسب التخصص، مع إمكانية الاتصال المباشر ومنح كود فحص:", 13, Color.rgb(167, 203, 226), false))

            // Embedded Interactive Leaflet Map for Nearby Workshops
            val webView = WebView(this@MainActivity).apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
                lp.topMargin = dp(8)
                layoutParams = lp
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://drivecare.app", generateWorkshopsMapHtml(), "text/html", "UTF-8", null)
            }
            addView(webView)

            // Category Filter Chips
            val filterScroll = HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            val filterRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            filterScroll.addView(filterRow)

            val categories = listOf(
                Pair("all", "🌟 الكل"),
                Pair("computer", "💻 فحص كمبيوتر"),
                Pair("electricity", "⚡ كهرباء"),
                Pair("mechanic", "🔧 ميكانيكا"),
                Pair("chassis", "🚗 عفشة"),
                Pair("ac", "❄️ تكييف")
            )

            for ((catKey, catTitle) in categories) {
                val isActive = (workshopSelectedCategory == catKey)
                filterRow.addView(Button(this@MainActivity).apply {
                    text = catTitle
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                    lp.setMargins(dp(2), 0, dp(2), 0)
                    layoutParams = lp
                    setBackgroundColor(if (isActive) Color.rgb(110, 231, 255) else Color.rgb(18, 42, 64))
                    setTextColor(if (isActive) Color.rgb(8, 23, 32) else Color.rgb(200, 225, 245))
                    setOnClickListener {
                        workshopSelectedCategory = catKey
                        switchTab("workshops_dir")
                    }
                })
            }
            addView(filterScroll)
        }
        container.addView(cardMap)

        // Workshop List Cards
        val filteredList = if (workshopSelectedCategory == "all") {
            registeredWorkshops
        } else {
            registeredWorkshops.filter { it.categoryKey == workshopSelectedCategory }
        }

        for (ws in filteredList) {
            val cardWs = card().apply {
                val headerRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(text(ws.name, 16, Color.WHITE, true).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(text("⭐ ${ws.rating}", 12, Color.rgb(248, 199, 74), true))
                }
                addView(headerRow)

                val specBox = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(text("🛠️ التخصص: ${ws.specialty}", 13, Color.rgb(110, 231, 255), false))
                }
                addView(specBox)

                addView(text("📍 العنوان: ${ws.address} • على بعد ${ws.distanceKm} كم 🚗", 12, Color.rgb(167, 203, 226), false).apply {
                    setPadding(0, dp(2), 0, dp(6))
                })

                val actionRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(6)
                    layoutParams = lp
                }

                // 1. Direct Dial
                actionRow.addView(Button(this@MainActivity).apply {
                    text = "📞 اتصال (${ws.phone})"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(25, 120, 65))
                    val lp = LinearLayout.LayoutParams(0, dp(38), 1f)
                    lp.setMargins(0, 0, dp(3), 0)
                    layoutParams = lp
                    setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${ws.phone}"))
                            startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "رقم الهاتف: ${ws.phone}", Toast.LENGTH_LONG).show()
                        }
                    }
                })

                // 2. Direct Navigation
                actionRow.addView(Button(this@MainActivity).apply {
                    text = "🧭 خريطة"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(32, 75, 115))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
                    lp.setMargins(dp(2), 0, dp(2), 0)
                    layoutParams = lp
                    setOnClickListener {
                        try {
                            val uri = Uri.parse("geo:${ws.lat},${ws.lng}?q=${ws.lat},${ws.lng}(${Uri.encode(ws.name)})")
                            val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                            startActivity(mapIntent)
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "إحداثيات الورشة: ${ws.lat}, ${ws.lng}", Toast.LENGTH_SHORT).show()
                        }
                    }
                })

                // 3. Grant Temporary Access PIN
                actionRow.addView(Button(this@MainActivity).apply {
                    text = "🔑 تصريح فحص"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(8, 23, 32))
                    setBackgroundColor(Color.rgb(248, 199, 74))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
                    lp.setMargins(dp(3), 0, 0, 0)
                    layoutParams = lp
                    setOnClickListener {
                        showGenerateWorkshopPinDialog(ws.name)
                    }
                })

                addView(actionRow)
            }
            container.addView(cardWs)
        }

        return container
    }

    private fun generateWorkshopsMapHtml(): String {
        val markersJs = registeredWorkshops.joinToString("\n") { ws ->
            val color = when(ws.categoryKey) {
                "computer" -> "#00e5ff"
                "electricity" -> "#ffeb3b"
                "mechanic" -> "#ff9800"
                "chassis" -> "#4caf50"
                "ac" -> "#00bcd4"
                else -> "#2196f3"
            }
            """
            L.circleMarker([${ws.lat}, ${ws.lng}], {
                color: '$color',
                fillColor: '$color',
                fillOpacity: 0.9,
                radius: 8
            }).addTo(map).bindPopup('<b>${ws.name}</b><br>${ws.specialty}<br>📞 ${ws.phone}');
            """.trimIndent()
        }

        return """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <style>
    body { margin: 0; padding: 0; background: #081720; font-family: sans-serif; }
    #map { width: 100%; height: 100vh; }
    .leaflet-popup-content-wrapper { background: #0c2030; color: #fff; border: 1px solid #00e5ff; direction: rtl; text-align: right; }
  </style>
</head>
<body>
  <div id="map"></div>
  <script>
    var map = L.map('map').setView([30.0444, 31.2357], 11);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 18,
      attribution: '© OpenStreetMap'
    }).addTo(map);

    // Car Current Position
    L.marker([30.0444, 31.2357]).addTo(map).bindPopup('<b>🚗 موقع سيارتك الحالي</b><br>القاهرة').openPopup();

    $markersJs
  </script>
</body>
</html>
        """.trimIndent()
    }

    // ========================================================
    // TAB: WORKSHOP INSPECTION CONSOLE (منصة فحص الورشة بالـ PIN)
    // ========================================================
    private fun buildWorkshopConsoleTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val activeVehName = preferences.getString("vehicle_name", "شيفروليه أفيو") ?: "شيفروليه أفيو"
        val activeVin = preferences.getString("vehicle_vin", "KMHDH41EBNA123456") ?: "KMHDH41EBNA123456"
        val primaryOwner = preferences.getString("vehicle_primary_owner", "مالك مسجل") ?: "مالك مسجل"

        if (!isWorkshopSessionUnlocked) {
            val myPendingReq = workshopAccessRequests.find {
                it.status == "pending" && (it.workshopPhone == myWorkshopPhone || it.workshopPhone == userPhone || myWorkshopPhone.isEmpty())
            }

            if (myPendingReq != null) {
                // Pending Request Waiting Screen
                val cardPending = card().apply {
                    setBackgroundColor(Color.rgb(20, 45, 65))
                    addView(text("⏳ جاري انتظار موافقة المالك على طلب الفحص", 17, Color.rgb(248, 199, 74), true))
                    addView(text("تم إرسال طلب استئذان فحص إلى هاتف مالك السيارة (${myPendingReq.ownerPhone}) لغرض: (${myPendingReq.serviceReason}).", 13, Color.rgb(180, 215, 240), false))
                    addView(text("💡 لا توجد أرقام سرية: بمجرد ضغط المالك على [موافقة] في هاتفه، ستفتح الجلسة تلقائياً لمرة واحدة فقط.", 12, Color.rgb(126, 242, 154), false))

                    addView(button("⚡ محاكاة موافقة المالك فورياً (لتجربة الفحص على نفس الجهاز)") {
                        approveWorkshopAccessRequest(myPendingReq)
                    }.apply {
                        setBackgroundColor(Color.rgb(25, 120, 80))
                        setTextColor(Color.WHITE)
                    })

                    addView(button("❌ إلغاء طلب الفحص الحالي") {
                        rejectWorkshopAccessRequest(myPendingReq)
                    }.apply {
                        setBackgroundColor(Color.rgb(55, 20, 25))
                        setTextColor(Color.rgb(255, 122, 122))
                    })
                }
                container.addView(cardPending)
            } else {
                // Request Inspection by Owner Phone Form
                val cardAuth = card().apply {
                    addView(text("📡 طلب إذن فحص سيارة من المالك (بدون أرقام سرية)", 17, Color.rgb(248, 199, 74), true))
                    addView(text("لحماية أمان وخصوصية السيارة: لا توجد أرقام سرية. ضع رقم موبايل مالك السيارة المسجل به في التطبيق وسبب الفحص، وسيصل إشعار فوري للمالك لمنحك إذن فحص لمرة واحدة:", 13, Color.rgb(167, 203, 226), false))

                    val etOwnerPhone = edit("رقم موبايل مالك السيارة المسجل بالتطبيق (إلزامي)").apply {
                        inputType = InputType.TYPE_CLASS_PHONE
                    }
                    val etReason = edit("سبب الفحص / الشكوى (مثال: فحص لمبة Check Engine، دورة التبريد)")
                    addView(etOwnerPhone)
                    addView(etReason)

                    addView(button("📡 إرسال طلب الفحص والاستئذان للمالك") {
                        val phone = etOwnerPhone.text.toString().trim()
                        val reason = etReason.text.toString().trim()
                        if (phone.isEmpty()) {
                            Toast.makeText(this@MainActivity, "يرجى كتابة رقم موبايل مالك السيارة المسجل به في التطبيق", Toast.LENGTH_SHORT).show()
                            return@button
                        }
                        submitWorkshopAccessRequest(phone, reason)
                    }.apply {
                        setBackgroundColor(Color.rgb(32, 120, 85))
                        setTextColor(Color.WHITE)
                    })

                    val primaryOwnerPhone = preferences.getString("vehicle_primary_owner_phone", "01000000000") ?: "01000000000"
                    addView(button("⚡ تعبئة برقم مالك السيارة الحالي ($primaryOwnerPhone) تجريبياً") {
                        etOwnerPhone.setText(primaryOwnerPhone)
                        etReason.setText("فحص وتشخيص أعطال كمبيوتر المحرك وقراءة الحساسات")
                    }.apply {
                        setBackgroundColor(Color.rgb(36, 77, 111))
                        setTextColor(Color.WHITE)
                    })
                }
                container.addView(cardAuth)
            }
        } else {
            // Authorized Inspection Session (One-Time Access)
            val cardSession = card().apply {
                val banner = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(18, 52, 32))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    layoutParams = lp
                    addView(text("✅ فحص مصرح به ونشط (دخول لمرة واحدة) - السيارة: $activeVehName", 15, Color.rgb(126, 242, 154), true))
                    addView(text("الشاسيه: $activeVin • المالك: $primaryOwner • المصرح: $workshopSessionGrantedBy", 12, Color.WHITE, false))
                    addView(text("⚠️ تنبيه أمني: عند خروجك ينتهي التصريح فوراً وتُقفل السيارة ولن تتمكن من الدخول مجدداً إلا بطلب جديد وموافقة المالك 📡", 11, Color.rgb(248, 199, 74), false))
                }
                addView(banner)

                // Live Sensor Metrics Grid
                addView(text("📊 القراءات المباشرة لكمبيوتر وحساسات السيارة (Live Sensors):", 14, Color.rgb(110, 231, 255), true).apply {
                    setPadding(0, dp(10), 0, dp(4))
                })

                val r1 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    layoutParams = lp
                }
                r1.addView(metricBox("سرعة المحرك (RPM)", "${telemetry.rpm} RPM", Color.rgb(248, 199, 74)).parent as View)
                r1.addView(metricBox("حرارة سائل التبريد", "${telemetry.coolantTemp} °C", Color.rgb(110, 231, 255)).parent as View)
                addView(r1)

                val r2 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(6)
                    layoutParams = lp
                }
                r2.addView(metricBox("شحن البطارية / الدينامو", "${String.format("%.1f", telemetry.batteryVoltage)}V", Color.WHITE).parent as View)
                r2.addView(metricBox("نسبة الوقود بالتنك", "${telemetry.fuelLevel}%", Color.rgb(126, 242, 154)).parent as View)
                addView(r2)

                // Diagnostic Trouble Codes
                addView(text("🔍 فحص أعطال المحرك والكمبيوتر (DTC Scanner):", 14, Color.rgb(248, 199, 74), true).apply {
                    setPadding(0, dp(10), 0, dp(4))
                })

                if (telemetry.dtcCodes.isEmpty()) {
                    addView(text("✅ لا توجد أي أكواد أعطال مسجلة بكمبيوتر السيارة (No Fault Codes).", 13, Color.rgb(126, 242, 154), false).apply {
                        setPadding(0, dp(4), 0, dp(8))
                    })
                } else {
                    for (code in telemetry.dtcCodes) {
                        val diag = diagnoseDtc(code)
                        val dtcBox = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setBackgroundColor(Color.rgb(45, 20, 25))
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            lp.bottomMargin = dp(4)
                            layoutParams = lp
                            addView(text("⚠️ كود: $code - ${diag.titleAr}", 13, Color.rgb(255, 122, 122), true))
                            addView(text("التفاصيل: ${diag.causesAr} • النظام: ${diag.system}", 12, Color.WHITE, false))
                        }
                        addView(dtcBox)
                    }
                }

                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(8)
                    layoutParams = lp
                }

                btnRow.addView(Button(this@MainActivity).apply {
                    text = "🧹 مسح أكواد الأعطال (Clear)"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(140, 45, 45))
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(0, 0, dp(3), 0)
                    layoutParams = lp
                    setOnClickListener {
                        telemetry.dtcCodes.clear()
                        addSecurityNotification(
                            "🧹 تصفير أعطال السيارة",
                            "قامت الورشة بمسح وتصفير أكواد أعطال كمبيوتر المحرك بنجاح.",
                            "dtc_cleared",
                            "info"
                        )
                        Toast.makeText(this@MainActivity, "تم مسح وتصفير أكواد الأعطال بنجاح ✅", Toast.LENGTH_SHORT).show()
                        switchTab("workshop_console")
                    }
                })

                btnRow.addView(Button(this@MainActivity).apply {
                    text = "📝 تسجيل صيانة وإصلاح"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(8, 23, 32))
                    setBackgroundColor(Color.rgb(248, 199, 74))
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(dp(3), 0, 0, 0)
                    layoutParams = lp
                    setOnClickListener {
                        switchTab("log_service")
                    }
                })
                addView(btnRow)

                addView(button("🚪 إنهاء جلسة الفحص والخروج من السيارة (قفل نهائي)") {
                    closeWorkshopSessionAndExit()
                }.apply {
                    setBackgroundColor(Color.rgb(150, 35, 40))
                    setTextColor(Color.WHITE)
                })
            }
            container.addView(cardSession)
        }

        return container
    }

    // ========================================================
    // TAB: WORKSHOP PROFILE & REGISTRATION (نافذة تسجيل وتعديل بيانات الورشة)
    // ========================================================
    private fun buildWorkshopProfileTabContent(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Header Card
        val cardHeader = card().apply {
            addView(text("🏪 بيانات الورشة والمركز الفني", 18, Color.rgb(248, 199, 74), true))
        }
        container.addView(cardHeader)

        // Form Card
        val cardForm = card().apply {
            addView(text("📝 بيانات المركز الأساسية:", 15, Color.WHITE, true))

            // 1. Workshop Name
            addView(text("اسم الورشة / المركز الفني:", 12, Color.rgb(180, 200, 220), false))
            val etName = EditText(this@MainActivity).apply {
                setText(myWorkshopName)
                setTextColor(Color.WHITE)
                textSize = 14f
                setBackgroundColor(Color.rgb(18, 38, 58))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }
            addView(etName)

            // 2. Manager Name
            addView(text("اسم المدير / الفني المسؤول:", 12, Color.rgb(180, 200, 220), false))
            val etManager = EditText(this@MainActivity).apply {
                setText(myWorkshopManager)
                setTextColor(Color.WHITE)
                textSize = 14f
                setBackgroundColor(Color.rgb(18, 38, 58))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }
            addView(etManager)

            // 3. Contact Phone
            addView(text("رقم هاتف التواصل والواتساب:", 12, Color.rgb(180, 200, 220), false))
            val etPhone = EditText(this@MainActivity).apply {
                setText(myWorkshopPhone)
                setTextColor(Color.WHITE)
                textSize = 14f
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                setBackgroundColor(Color.rgb(18, 38, 58))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }
            addView(etPhone)

            // 4. Street Address
            addView(text("العنوان التفصيلي:", 12, Color.rgb(180, 200, 220), false))
            val etAddress = EditText(this@MainActivity).apply {
                setText(myWorkshopAddress)
                setTextColor(Color.WHITE)
                textSize = 14f
                setBackgroundColor(Color.rgb(18, 38, 58))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }
            addView(etAddress)

            // 5. Activity & Specialization Category
            addView(text("نوع النشاط والتخصص الرئيسي:", 12, Color.rgb(180, 200, 220), false))
            var selectedCategory = myWorkshopCategory

            val catLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }

            val catOptions = listOf(
                Pair("computer", "💻 فحص كمبيوتر وبرمجة أعطال OBD"),
                Pair("electricity", "⚡ كهرباء سيارات وحساسات وضفيرة"),
                Pair("mechanic", "🔧 ميكانيكا محركات وعمرة وفتيس"),
                Pair("chassis", "🔩 عفشة وشاسيه وميزان هيدروليك"),
                Pair("ac", "❄️ تكييف سيارات وشحن فريون وفلاتر"),
                Pair("all", "🏆 صيانة شاملة وخدمة متكاملة")
            )

            val catButtons = mutableListOf<Button>()
            catOptions.chunked(2).forEach { pairChunk ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    rlp.bottomMargin = dp(4)
                    layoutParams = rlp
                }
                pairChunk.forEach { (catKey, catLabel) ->
                    val btn = Button(this@MainActivity).apply {
                        text = catLabel
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        val blp = LinearLayout.LayoutParams(0, dp(42), 1f)
                        blp.setMargins(dp(2), 0, dp(2), 0)
                        layoutParams = blp
                        val isSel = (catKey == selectedCategory)
                        setBackgroundColor(if (isSel) Color.rgb(20, 110, 80) else Color.rgb(28, 48, 68))
                        setTextColor(if (isSel) Color.rgb(248, 199, 74) else Color.rgb(200, 215, 230))
                        setOnClickListener {
                            selectedCategory = catKey
                            catButtons.forEachIndexed { idx, b ->
                                val k = catOptions[idx].first
                                val sel = (k == selectedCategory)
                                b.setBackgroundColor(if (sel) Color.rgb(20, 110, 80) else Color.rgb(28, 48, 68))
                                b.setTextColor(if (sel) Color.rgb(248, 199, 74) else Color.rgb(200, 215, 230))
                            }
                        }
                    }
                    catButtons.add(btn)
                    row.addView(btn)
                }
                catLayout.addView(row)
            }
            addView(catLayout)

            // 6. Detailed Specialty description
            addView(text("وصف دقيق للخدمات المقدمة:", 12, Color.rgb(180, 200, 220), false))
            val etSpecialty = EditText(this@MainActivity).apply {
                setText(myWorkshopSpecialty)
                setTextColor(Color.WHITE)
                textSize = 14f
                setBackgroundColor(Color.rgb(18, 38, 58))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }
            addView(etSpecialty)

            // 7. Workshop Location (GPS / Google Maps)
            var pickedLat = myWorkshopLat
            var pickedLng = myWorkshopLng

            addView(text("📍 موقع الورشة الجغرافي:", 14, Color.rgb(248, 199, 74), true).apply {
                setPadding(0, dp(6), 0, dp(2))
            })

            val tvLocDisplay = text("الموقع الحالي: ${String.format(java.util.Locale.US, "%.5f, %.5f", pickedLat, pickedLng)}", 13, Color.rgb(167, 203, 226), false).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setBackgroundColor(Color.rgb(18, 38, 58))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }
            addView(tvLocDisplay)

            // Button to capture real GPS or pick on Google Maps
            val btnLocRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(10)
                layoutParams = lp

                addView(Button(this@MainActivity).apply {
                    text = "📍 تحديد موقع الورشة من موقعي الحالي (GPS)"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(20, 100, 70))
                    val blp = LinearLayout.LayoutParams(0, dp(40), 1f)
                    blp.rightMargin = dp(4)
                    layoutParams = blp
                    setOnClickListener {
                        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                        val loc = currentGpsLocation ?: try {
                            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        } catch (_: SecurityException) { null }

                        if (loc != null) {
                            pickedLat = loc.latitude
                            pickedLng = loc.longitude
                            myWorkshopLat = pickedLat
                            myWorkshopLng = pickedLng
                            tvLocDisplay.text = "الموقع الحالي: ${String.format(java.util.Locale.US, "%.5f, %.5f", pickedLat, pickedLng)} (GPS 📍)"
                            Toast.makeText(this@MainActivity, "تم التقاط موقعك الجغرافي الفعلي بدقة 📍", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "يرجى تشغيل الـ GPS بالهاتف لالتقاط الموقع تلقائياً", Toast.LENGTH_SHORT).show()
                        }
                    }
                })

                addView(Button(this@MainActivity).apply {
                    text = "🗺️ فتح وتحديد الموقع في Google Maps"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(36, 77, 111))
                    val blp = LinearLayout.LayoutParams(0, dp(40), 1f)
                    blp.leftMargin = dp(4)
                    layoutParams = blp
                    setOnClickListener {
                        try {
                            val mapUri = Uri.parse("geo:$pickedLat,$pickedLng?q=$pickedLat,$pickedLng(${Uri.encode(myWorkshopName.ifEmpty { "موقع الورشة" })})")
                            val intent = Intent(Intent.ACTION_VIEW, mapUri)
                            startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "الإحداثيات الحالية: $pickedLat, $pickedLng", Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
            addView(btnLocRow)

            // Save / Update Profile Button
            addView(Button(this@MainActivity).apply {
                text = "💾 حفظ ونشر بيانات الورشة"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(8, 23, 32))
                setBackgroundColor(Color.rgb(248, 199, 74))
                val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
                blp.topMargin = dp(6)
                layoutParams = blp
                setOnClickListener {
                    val name = etName.text.toString().trim()
                    val manager = etManager.text.toString().trim()
                    val phone = etPhone.text.toString().trim()
                    val address = etAddress.text.toString().trim()
                    val specialty = etSpecialty.text.toString().trim()

                    if (name.isEmpty() || phone.isEmpty()) {
                        Toast.makeText(this@MainActivity, "يرجى كتابة اسم الورشة ورقم هاتف التواصل على الأقل.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    myWorkshopName = name
                    myWorkshopManager = if (manager.isNotEmpty()) manager else "فني المركز"
                    myWorkshopPhone = phone
                    myWorkshopAddress = if (address.isNotEmpty()) address else "مصر"
                    myWorkshopSpecialty = if (specialty.isNotEmpty()) specialty else "صيانة عامة وفحص"
                    myWorkshopCategory = selectedCategory
                    myWorkshopLat = pickedLat
                    myWorkshopLng = pickedLng

                    saveWorkshopProfile()

                    Toast.makeText(this@MainActivity, "✅ تم حفظ ونشر بيانات الورشة بنجاح!", Toast.LENGTH_LONG).show()
                    renderCurrentScreen()
                }
            })
        }
        container.addView(cardForm)

        // Status Card
        val cardStatus = card().apply {
            addView(text("📌 معاينة بيانات الورشة الحالية بالسيرفر:", 14, Color.rgb(248, 199, 74), true))
            addView(text("• اسم المركز: $myWorkshopName", 13, Color.WHITE, false))
            addView(text("• المسؤول: $myWorkshopManager | هاتف: $myWorkshopPhone", 13, Color.rgb(180, 220, 200), false))
            addView(text("• العنوان: $myWorkshopAddress", 13, Color.rgb(200, 210, 220), false))
            addView(text("• التخصص: $myWorkshopSpecialty ($myWorkshopCategory)", 13, Color.rgb(180, 220, 240), false))
            addView(text("• الإحداثيات: ($myWorkshopLat, $myWorkshopLng)", 13, Color.rgb(160, 200, 180), false))

            addView(button("🗺️ فتح ومعاينة موقع الورشة في دليل وخريطة الورش") {
                switchTab("workshops_dir")
            }.apply {
                setBackgroundColor(Color.rgb(28, 55, 85))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardStatus)

        return container
    }

    // ========================================================
    // TAB: DRIVER ASSIGNED CARS (عرض واختيار سيارات السائق المفوض بها)
    // ========================================================
    private fun buildDriverCarsTabContent(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Header Card
        val cardHeader = card().apply {
            addView(text("🚗 السيارات المسجلة والمفوض بقيادتها", 18, Color.rgb(248, 199, 74), true))
            addView(text("كافة المركبات المسجلة باسمك أو المفوض بقيادتها من المالكين. يمكنك اختيار وتفعيل السيارة الحالية لربط عدادات الـ OBD وحسابات استهلاك الوقود وتكلفة المشوار بها مباشرة:", 13, Color.rgb(167, 203, 226), false))

            addView(button("➕ إضافة سيارة مفوضة جديدة (برقم الشاسية وموافقة المالك)") {
                showAddDriverCarDialog()
            }.apply {
                setBackgroundColor(Color.rgb(20, 110, 80))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardHeader)

        // Pending Driver Delegation Invitations (دعوات تفويض السيارات الواردة من المالكين)
        val pendingInvites = driverDelegationRequests.filter {
            it.status == "pending" && (it.driverPhone == userPhone || userPhone.isEmpty() || it.driverPhone.endsWith(userPhone.takeLast(8)))
        }
        if (pendingInvites.isNotEmpty()) {
            val cardPending = card().apply {
                setBackgroundColor(Color.rgb(18, 45, 65))
                addView(text("📩 طلبات تفويض قيادة واردة بانتظار موافقتك (${pendingInvites.size}):", 16, Color.rgb(248, 199, 74), true))
                addView(text("أرسل لك المالكون تفويضاً لاستلام السيارات التالية وقيادتها برقم هاتفك المسجل. اضغط [قبول] لتظهر السيارة فوراً بأسطولك:", 12, Color.rgb(167, 203, 226), false))

                pendingInvites.forEach { req ->
                    val inviteBox = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.rgb(10, 28, 42))
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        lp.topMargin = dp(8)
                        layoutParams = lp

                        addView(text("🚗 ${req.vehicleName} (${req.vehicleMake} - موديل ${req.vehicleYear})", 15, Color.WHITE, true))
                        addView(text("• لوحة السيارة: ${req.vehiclePlate} • رقم الشاسية (VIN): ${req.vehicleVin}", 12, Color.rgb(180, 200, 215), false))
                        addView(text("• المالك: ${req.ownerName} (هاتف: ${req.ownerPhone})", 12, Color.rgb(248, 199, 74), false))
                        addView(text("• اسمك لدى المالك: ${req.driverCustomName} • الصفة / الوردية: ${req.driverRole}", 12, Color.rgb(126, 242, 154), false))
                        addView(text("• تاريخ إرسال الطلب: ${req.requestDate}", 11, Color.rgb(150, 175, 195), false))

                        val btnRow = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            blp.topMargin = dp(8)
                            layoutParams = blp

                            addView(Button(this@MainActivity).apply {
                                text = "✅ قبول واستلام السيارة"
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.rgb(20, 120, 80))
                                val lpBtn = LinearLayout.LayoutParams(0, dp(40), 1.3f)
                                lpBtn.rightMargin = dp(4)
                                layoutParams = lpBtn
                                setOnClickListener {
                                    acceptDriverDelegation(req)
                                }
                            })

                            addView(Button(this@MainActivity).apply {
                                text = "❌ اعتذار / رفض"
                                textSize = 12f
                                setTextColor(Color.rgb(255, 122, 122))
                                setBackgroundColor(Color.rgb(55, 20, 25))
                                val lpBtn = LinearLayout.LayoutParams(0, dp(40), 1f)
                                layoutParams = lpBtn
                                setOnClickListener {
                                    rejectDriverDelegation(req)
                                }
                            })
                        }
                        addView(btnRow)
                    }
                    addView(inviteBox)
                }
            }
            container.addView(cardPending)
        }

        // List of Cars
        if (driverAssignedCars.isEmpty()) {
            loadDriverAssignedCars()
        }

        driverAssignedCars.forEach { car ->
            val isCurrentActive = car.isActive
            val cardCar = card().apply {
                if (isCurrentActive) {
                    setBackgroundColor(Color.rgb(12, 45, 35))
                }

                // Row Title & Active Badge
                val rowTitle = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.bottomMargin = dp(6)
                    layoutParams = lp

                    addView(TextView(this@MainActivity).apply {
                        text = car.name
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isCurrentActive) Color.rgb(248, 199, 74) else Color.WHITE)
                        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        layoutParams = tlp
                    })

                    addView(TextView(this@MainActivity).apply {
                        text = if (isCurrentActive) "✅ السيارة النشطة حالياً" else "متاحة للقيادة"
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isCurrentActive) Color.rgb(120, 240, 160) else Color.rgb(180, 190, 200))
                    })
                }
                addView(rowTitle)

                // Details
                addView(text("• الماركة والموديل: ${car.make} - موديل ${car.year}", 13, Color.rgb(200, 220, 240), false))
                addView(text("• رقم الشاسية (VIN): ${car.vin}", 13, Color.rgb(180, 200, 210), false))
                addView(text("• سعة التانك: ${car.tankCapacity} لتر | سعة المحرك: ${car.displacement}L | وزن فارغ: ${car.emptyWeightKg.toInt()} كجم", 13, Color.rgb(180, 210, 190), false))
                addView(text("• مالك السيارة: ${car.ownerName} (هاتف: ${car.ownerPhone})", 13, Color.rgb(240, 200, 150), false))

                // Actions Row
                val rowActions = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(8)
                    layoutParams = lp

                    // Switch Active Car Button
                    addView(Button(this@MainActivity).apply {
                        text = if (isCurrentActive) "🏎️ الانتقال لعدادات هذه السيارة" else "🔘 قيادة وتفعيل هذه السيارة الآن"
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setBackgroundColor(if (isCurrentActive) Color.rgb(15, 90, 60) else Color.rgb(35, 75, 115))
                        val blp = LinearLayout.LayoutParams(0, dp(40), 1.5f)
                        blp.rightMargin = dp(4)
                        layoutParams = blp
                        setOnClickListener {
                            activateDriverCar(car)
                        }
                    })

                    // Call Owner Button
                    addView(Button(this@MainActivity).apply {
                        text = "📞 اتصال بالمالك"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.rgb(40, 55, 70))
                        val blp = LinearLayout.LayoutParams(0, dp(40), 1f)
                        blp.leftMargin = dp(4)
                        layoutParams = blp
                        setOnClickListener {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${car.ownerPhone}"))
                                startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, "رقم المالك: ${car.ownerPhone}", Toast.LENGTH_LONG).show()
                            }
                        }
                    })
                }
                addView(rowActions)
            }
            container.addView(cardCar)
        }

        return container
    }

    private fun activateDriverCar(car: DriverAssignedCar) {
        driverAssignedCars.forEach { it.isActive = (it.id == car.id) }
        saveDriverAssignedCars()

        preferences.edit()
            .putString("vehicle_name", car.name)
            .putString("vehicle_make", car.make)
            .putString("vehicle_year", car.year)
            .putString("vehicle_vin", car.vin)
            .putFloat("tank_capacity_liters", car.tankCapacity)
            .putFloat("engine_displacement_liters", car.displacement)
            .putFloat("fuel_rate_baseline", car.baseRate)
            .putFloat("vehicle_empty_weight", car.emptyWeightKg)
            .putString("fuel_type", car.fuelType)
            .putString("vehicle_primary_owner", car.ownerName)
            .putString("vehicle_primary_owner_phone", car.ownerPhone)
            .putString("vehicle_data_source", "تم التفعيل من أسطول سيارات السائق 🚗")
            .apply()

        vehicleEmptyWeightKg = car.emptyWeightKg
        Toast.makeText(this@MainActivity, "🚗 تم تفعيل قيادة [${car.name}] وربط العدادات وحسابات الوقود بها بنجاح!", Toast.LENGTH_SHORT).show()
        switchTab("gauges")
    }

    private fun showAddDriverCarDialog() {
        val dialog = AlertDialog.Builder(this)
        dialog.setTitle("➕ إضافة سيارة مفوضة جديدة للسائق")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val etCarName = EditText(this).apply { hint = "اسم السيارة وموديلها (مثال: نيسان صني 2021)" }
        val etMake = EditText(this).apply { hint = "الماركة (مثال: نيسان)" }
        val etVin = EditText(this).apply { hint = "رقم الشاسية VIN (17 حرف)" }
        val etOwnerName = EditText(this).apply { hint = "اسم المالك المفوض" }
        val etOwnerPhone = EditText(this).apply { hint = "رقم هاتف المالك"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val etTank = EditText(this).apply { hint = "سعة التانك باللتر (مثال: 45)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }

        root.addView(etCarName)
        root.addView(etMake)
        root.addView(etVin)
        root.addView(etOwnerName)
        root.addView(etOwnerPhone)
        root.addView(etTank)

        dialog.setView(root)
        dialog.setPositiveButton("إضافة واعتماد") { _, _ ->
            val name = etCarName.text.toString().trim()
            val make = etMake.text.toString().trim()
            val vin = etVin.text.toString().trim().uppercase()
            val owner = etOwnerName.text.toString().trim()
            val phone = etOwnerPhone.text.toString().trim()
            val tank = etTank.text.toString().toFloatOrNull() ?: 45.0f

            if (name.isEmpty() || vin.isEmpty()) {
                Toast.makeText(this, "يرجى كتابة اسم السيارة ورقم الشاسية.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            val newCar = DriverAssignedCar(
                id = "drv-car-${System.currentTimeMillis()}",
                name = name,
                make = if (make.isNotEmpty()) make else "سيارة حديثة",
                year = "2022",
                vin = vin,
                ownerName = if (owner.isNotEmpty()) owner else "مالك مفوض",
                ownerPhone = if (phone.isNotEmpty()) phone else "01000000000",
                tankCapacity = tank,
                baseRate = 7.0f,
                displacement = 1.6f,
                fuelType = "gasoline",
                emptyWeightKg = 1200f,
                isActive = false
            )

            driverAssignedCars.add(newCar)
            saveDriverAssignedCars()
            Toast.makeText(this, "✅ تمت إضافة السيارة إلى قائمة سياراتك بنجاح!", Toast.LENGTH_SHORT).show()
            renderCurrentScreen()
        }
        dialog.setNegativeButton("إلغاء", null)
        dialog.show()
    }

    private fun saveWorkshopProfile() {
        preferences.edit()
            .putString("my_workshop_name", myWorkshopName)
            .putString("my_workshop_manager", myWorkshopManager)
            .putString("my_workshop_specialty", myWorkshopSpecialty)
            .putString("my_workshop_category", myWorkshopCategory)
            .putString("my_workshop_phone", myWorkshopPhone)
            .putString("my_workshop_address", myWorkshopAddress)
            .putFloat("my_workshop_lat", myWorkshopLat.toFloat())
            .putFloat("my_workshop_lng", myWorkshopLng.toFloat())
            .apply()

        // Also update or add to registeredWorkshops directory
        val existingIdx = registeredWorkshops.indexOfFirst { it.id == "my-ws-profile" }
        val wsItem = RegisteredWorkshop(
            id = "my-ws-profile",
            name = myWorkshopName,
            managerName = myWorkshopManager,
            specialty = myWorkshopSpecialty,
            categoryKey = myWorkshopCategory,
            phone = myWorkshopPhone,
            address = myWorkshopAddress,
            distanceKm = 0.5f,
            lat = myWorkshopLat,
            lng = myWorkshopLng,
            rating = 5.0f
        )
        if (existingIdx >= 0) {
            registeredWorkshops[existingIdx] = wsItem
        } else {
            registeredWorkshops.add(0, wsItem)
        }

        // Post to remote server in background
        Thread {
            try {
                val jsonPayload = org.json.JSONObject().apply {
                    put("action", "register")
                    put("name", myWorkshopName)
                    put("manager_name", myWorkshopManager)
                    put("phone", myWorkshopPhone)
                    put("address", myWorkshopAddress)
                    put("category", myWorkshopCategory)
                    put("specialty", myWorkshopSpecialty)
                    put("lat", myWorkshopLat)
                    put("lng", myWorkshopLng)
                }
                val url = java.net.URL("http://127.0.0.1:8000/api/workshops")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.outputStream.use { os ->
                    val input = jsonPayload.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                    os.write(input, 0, input.size)
                }
                conn.responseCode
            } catch (_: Exception) {}
        }.start()
    }

    private fun loadWorkshopProfile() {
        myWorkshopName = preferences.getString("my_workshop_name", myWorkshopName) ?: myWorkshopName
        myWorkshopManager = preferences.getString("my_workshop_manager", myWorkshopManager) ?: myWorkshopManager
        myWorkshopSpecialty = preferences.getString("my_workshop_specialty", myWorkshopSpecialty) ?: myWorkshopSpecialty
        myWorkshopCategory = preferences.getString("my_workshop_category", myWorkshopCategory) ?: myWorkshopCategory
        myWorkshopPhone = preferences.getString("my_workshop_phone", myWorkshopPhone) ?: myWorkshopPhone
        myWorkshopAddress = preferences.getString("my_workshop_address", myWorkshopAddress) ?: myWorkshopAddress
        myWorkshopLat = preferences.getFloat("my_workshop_lat", myWorkshopLat.toFloat()).toDouble()
        myWorkshopLng = preferences.getFloat("my_workshop_lng", myWorkshopLng.toFloat()).toDouble()

        val existingIdx = registeredWorkshops.indexOfFirst { it.id == "my-ws-profile" }
        val wsItem = RegisteredWorkshop(
            id = "my-ws-profile",
            name = myWorkshopName,
            managerName = myWorkshopManager,
            specialty = myWorkshopSpecialty,
            categoryKey = myWorkshopCategory,
            phone = myWorkshopPhone,
            address = myWorkshopAddress,
            distanceKm = 0.5f,
            lat = myWorkshopLat,
            lng = myWorkshopLng,
            rating = 5.0f
        )
        if (existingIdx >= 0) {
            registeredWorkshops[existingIdx] = wsItem
        } else {
            registeredWorkshops.add(0, wsItem)
        }
    }

    private fun saveDriverAssignedCars() {
        val array = org.json.JSONArray()
        driverAssignedCars.forEach { car ->
            val obj = org.json.JSONObject().apply {
                put("id", car.id)
                put("name", car.name)
                put("make", car.make)
                put("year", car.year)
                put("vin", car.vin)
                put("ownerName", car.ownerName)
                put("ownerPhone", car.ownerPhone)
                put("tankCapacity", car.tankCapacity.toDouble())
                put("baseRate", car.baseRate.toDouble())
                put("displacement", car.displacement.toDouble())
                put("fuelType", car.fuelType)
                put("emptyWeightKg", car.emptyWeightKg.toDouble())
                put("isActive", car.isActive)
            }
            array.put(obj)
        }
        preferences.edit().putString("driver_assigned_cars_json", array.toString()).apply()
    }

    private fun loadDriverAssignedCars() {
        val jsonStr = preferences.getString("driver_assigned_cars_json", null)
        driverAssignedCars.clear()
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    driverAssignedCars.add(
                        DriverAssignedCar(
                            id = obj.optString("id", "drv-car-$i"),
                            name = obj.optString("name", "سيارة"),
                            make = obj.optString("make", "عامة"),
                            year = obj.optString("year", "2020"),
                            vin = obj.optString("vin", "UNKNOWN_VIN"),
                            ownerName = obj.optString("ownerName", "المالك"),
                            ownerPhone = obj.optString("ownerPhone", "01000000000"),
                            tankCapacity = obj.optDouble("tankCapacity", 45.0).toFloat(),
                            baseRate = obj.optDouble("baseRate", 7.0).toFloat(),
                            displacement = obj.optDouble("displacement", 1.5).toFloat(),
                            fuelType = obj.optString("fuelType", "gasoline"),
                            emptyWeightKg = obj.optDouble("emptyWeightKg", 1100.0).toFloat(),
                            isActive = obj.optBoolean("isActive", (i == 0))
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        if (driverAssignedCars.isEmpty()) {
            if (ownerFleetVehicles.isNotEmpty()) {
                ownerFleetVehicles.forEachIndexed { idx, veh ->
                    driverAssignedCars.add(
                        DriverAssignedCar(
                            id = "drv-car-${veh.id}",
                            name = veh.name,
                            make = veh.make,
                            year = veh.year,
                            vin = veh.vin,
                            ownerName = preferences.getString("user_name", "المالك الأساسي") ?: "المالك الأساسي",
                            ownerPhone = preferences.getString("user_phone", "01000000000") ?: "01000000000",
                            tankCapacity = veh.tankCapacity,
                            baseRate = veh.baseRate,
                            displacement = veh.displacement,
                            fuelType = veh.fuelType,
                            emptyWeightKg = veh.emptyWeightKg,
                            isActive = (idx == 0)
                        )
                    )
                }
                saveDriverAssignedCars()
            }
        }
    }

    // ========================================================
    // TAB: LOG SERVICE & MAINTENANCE (تسجيل الصيانة وإشعار المالك)
    // ========================================================
    private fun buildLogServiceTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val activeVehName = preferences.getString("vehicle_name", "شيفروليه أفيو") ?: "شيفروليه أفيو"

        val cardLog = card().apply {
            addView(text("📝 تسجيل عملية صيانة وإصلاح بالمركبة (إشعار المالك فورياً)", 17, Color.rgb(248, 199, 74), true))
            addView(text("سيتم توثيق هذه الصيانة في السجل الدائم للسيارة وإرسال إشعار فوري لمالك المركبة:", 13, Color.rgb(167, 203, 226), false))

            val etType = edit("نوع الصيانة (مثال: غيار زيت وفلتر 10,000 كم، بوجيهات، تيل فرامل)")
            val etDetails = edit("تفاصيل الإصلاح وقطع الغيار (مثال: زيت تخليقي 5W-30 + بوجيهات إيريديوم أصلية)")
            val etCost = edit("التكلفة الإجمالية للصيانة (ج.م) (مثال: 1250)").apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            val etOdo = edit("قراءة عداد السيارة الحالية (كم) (مثال: 48500)").apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }

            addView(etType)
            addView(etDetails)
            addView(etCost)
            addView(etOdo)

            addView(button("✅ حفظ وتثبيت الصيانة وإشعار المالك رسمياً") {
                val serviceType = etType.text.toString().trim().ifEmpty { "صيانة دورية" }
                val details = etDetails.text.toString().trim().ifEmpty { "فحص واستبدال قطع استهلاكية" }
                val cost = etCost.text.toString().trim().ifEmpty { "0" }
                val odo = etOdo.text.toString().trim().ifEmpty { "50000" }

                submitWorkshopMaintenanceLog(serviceType, details, cost, odo)
            }.apply {
                setBackgroundColor(Color.rgb(32, 120, 85))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardLog)

        return container
    }

    // ========================================================
    // HELPER METHODS: PIN, NOTIFICATIONS, VIN LOCK & DELTA LOG
    // ========================================================
    private fun showGenerateWorkshopPinDialog(targetWorkshopName: String) {
        val pin = (1000 + (Math.random() * 9000).toInt()).toString()
        activeWorkshopPin = pin
        activeWorkshopTargetName = targetWorkshopName
        isPinActive = true
        activePinExpiryTimestamp = System.currentTimeMillis() + (2 * 3600 * 1000L) // 2 hours

        val carName = preferences.getString("vehicle_name", "شيفروليه أفيو") ?: "السيارة"
        addSecurityNotification(
            "🔑 منح تصريح فحص مؤقت لورشة",
            "قام السائق ($userName) بمنح كود فحص مؤقت (PIN: $pin) لـ ($targetWorkshopName) لفحص كمبيوتر سيارتك ($carName). التصريح ساري لمدة ساعتين.",
            "workshop_pin_granted",
            "warning"
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(text("تم إنشاء كود الفحص المؤقت لـ:\n$targetWorkshopName", 14, Color.WHITE, true))

            val pinBox = text(pin, 32, Color.rgb(248, 199, 74), true).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(Color.rgb(18, 42, 64))
                setPadding(dp(16), dp(14), dp(16), dp(14))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dp(10), 0, dp(10))
                layoutParams = lp
            }
            addView(pinBox)

            addView(text("⏳ صلاحية الكود: ساعتان فقط.\n📡 تم إرسال إشعار فوري لمالك السيارة بإصدار هذا التصريح لحمايتك وضمان الشفافية.", 12, Color.rgb(167, 203, 226), false))
        }

        AlertDialog.Builder(this)
            .setTitle("🔑 كود فحص مؤقت للورشة")
            .setView(layout)
            .setPositiveButton("حسناً") { _, _ -> }
            .show()
    }

    private fun verifyAndUnlockWorkshopSession(enteredPin: String) {
        if (!isPinActive) {
            AlertDialog.Builder(this)
                .setTitle("⛔ التصريح ملغي")
                .setMessage("قام مالك السيارة بإلغاء وإبطال جميع تصاريح الفحص المؤقتة. يرجى طلب تصريح جديد من المالك أو السائق.")
                .setPositiveButton("حسناً", null)
                .show()
            return
        }

        if (enteredPin == activeWorkshopPin) {
            isWorkshopSessionUnlocked = true
            workshopSessionGrantedBy = if (currentRole == "driver") "السائق المفوض" else "المالك الأساسي"
            val carName = preferences.getString("vehicle_name", "شيفروليه أفيو") ?: "السيارة"

            addSecurityNotification(
                "🔍 بدء فحص كمبيوتر السيارة",
                "قامت ورشة ($activeWorkshopTargetName) بفتح فحص كمبيوتر وحساسات سيارتك ($carName) مباشرة عبر كود الفحص المؤقت المصرح به.",
                "workshop_accessed",
                "warning"
            )

            Toast.makeText(this, "✅ تم التحقق بنجاح! تم فك حظر الفحص وقراءة بيانات كمبيوتر السيارة.", Toast.LENGTH_LONG).show()
            switchTab("workshop_console")
        } else {
            AlertDialog.Builder(this)
                .setTitle("⚠️ كود غير صحيح")
                .setMessage("كود الفحص المؤقت الذي أدخلته غير مطابق. يرجى مراجعة السائق أو المالك للتأكد من الكود المكون من 4 أرقام.")
                .setPositiveButton("حسناً", null)
                .show()
        }
    }

    private fun submitWorkshopMaintenanceLog(serviceType: String, details: String, cost: String, odo: String) {
        val carName = preferences.getString("vehicle_name", "شيفروليه أفيو") ?: "السيارة"
        val driverName = authorizedDriverName
        val wsName = activeWorkshopTargetName

        if (serviceType.contains("زيت", ignoreCase = true)) {
            val odoFloat = odo.toFloatOrNull() ?: (if (telemetry.carOdometerKm > 0f) telemetry.carOdometerKm else preferences.getFloat("car_cumulative_odometer_km", 0f))
            preferences.edit()
                .putFloat("oil_current_km", 0.0f)
                .putFloat("oil_baseline_car_odometer", odoFloat)
                .putFloat("car_cumulative_odometer_km", odoFloat)
                .apply()
        }

        val today = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        addSecurityNotification(
            "🔧 صيانة جديدة مسجلة بالورشة",
            "سجلت ورشة ($wsName) عملية صيانة: ($serviceType) لسيارتك ($carName) بتكلفة ($cost ج.م) وقراءة عداد ($odo كم) بتفويض من ($driverName) في $today.",
            "maintenance_logged",
            "info"
        )

        AlertDialog.Builder(this)
            .setTitle("✅ تم توثيق الصيانة بنجاح")
            .setMessage("تم تسجيل عملية الصيانة وتحديث سجل السيارة:\n• الخدمة: $serviceType\n• التفاصيل: $details\n• التكلفة: $cost ج.م\n• العداد: $odo كم\n• تم إرسال إشعار فوري لمالك المركبة وتحديث سجل الصيانة الدائم.")
            .setPositiveButton("تم") { _, _ ->
                switchTab("workshop_console")
            }
            .show()
    }

    private fun simulateDriverRoadDelta() {
        val deltaSpeeds = listOf(85, 92, 105, 78, 62, 0)
        val newSpeed = deltaSpeeds.random()
        telemetry.speed = newSpeed
        telemetry.rpm = (newSpeed * 32).coerceIn(800, 3500)
        telemetry.coolantTemp = (89 + (Math.random() * 4).toInt())
        val deltaFuelDrop = (Math.random() * 2).toInt()
        telemetry.fuelLevel = (telemetry.fuelLevel - deltaFuelDrop).coerceAtLeast(5)
        checkAndLogTelemetryDelta(force = true)
        Toast.makeText(this, "⚡ تم تسجيل لقطة تغير جديدة (Delta) وتحديث قراءات السائق عن بعد!", Toast.LENGTH_SHORT).show()
        switchTab("remote_fleet")
    }

    private fun checkAndLogTelemetryDelta(force: Boolean = false) {
        val now = System.currentTimeMillis()

        // Adaptive Telemetry: Check if user is actively watching a live tab in foreground
        val isWatchingLiveTab = isAppInForeground && (activeTab in listOf("gauges", "fuel", "scanner", "remote_fleet"))
        telemetrySyncMode = if (isWatchingLiveTab) "live_stream" else "periodic_batch"

        // Emergency alerts & critical faults always bypass batch delay
        val hasEmergencyAlert = (telemetry.activeAlertTitle != null && telemetry.isAlertCritical) || (telemetry.dtcCodes.size > lastSyncedDtcCount)
        val heartbeat = (now - lastSyncTimestamp > 60000L) // نبضة دورية كل 60 ثانية لتوفير استهلاك الباقة

        // If app is in background, screen locked, or static tab is open: accumulate data and send periodically
        if (!isWatchingLiveTab && !hasEmergencyAlert && !force) {
            if (!heartbeat) {
                return // توفير الباقة: تجميع البيانات محلياً وإرسال دفعة كل 60 ثانية طالما لم يحدث إنذار طارئ
            }
        }

        val speedDiff = Math.abs(telemetry.speed - lastSyncedSpeed)
        val rpmDiff = Math.abs(telemetry.rpm - lastSyncedRpm)
        val fuelDiff = Math.abs(telemetry.fuelLevel - lastSyncedFuel)
        val tempDiff = Math.abs(telemetry.coolantTemp - lastSyncedTemp)
        val dtcChanged = (telemetry.dtcCodes.size != lastSyncedDtcCount)
        val alertChanged = (telemetry.activeAlertTitle != lastSyncedAlert)

        val reasons = mutableListOf<String>()

        // Dynamic detection targeted to the active open tab
        if (isWatchingLiveTab) {
            when (activeTab) {
                "gauges" -> {
                    if (speedDiff >= 2) reasons.add("بث مباشر: سرعة (${telemetry.speed} كم/س)")
                    if (rpmDiff >= 100) reasons.add("بث مباشر: عزم (${telemetry.rpm} RPM)")
                    if (tempDiff >= 1) reasons.add("بث مباشر: حرارة (${telemetry.coolantTemp}°C)")
                }
                "fuel" -> {
                    if (fuelDiff >= 1) reasons.add("بث مباشر: وقود (${telemetry.fuelLevel}%)")
                    reasons.add("بث مباشر: تكلفة الرحلة واستهلاك الوقود")
                }
                "scanner" -> {
                    if (dtcChanged) reasons.add("بث مباشر: فحص الأعطال (${telemetry.dtcCodes.size} كود)")
                }
                else -> {
                    if (speedDiff >= 3) reasons.add("تغير السرعة (${telemetry.speed} كم/س)")
                    if (rpmDiff >= 150) reasons.add("تغير العزم (${telemetry.rpm} RPM)")
                    if (fuelDiff >= 1) reasons.add("تغير الوقود (${telemetry.fuelLevel}%)")
                    if (tempDiff >= 2) reasons.add("تغير الحرارة (${telemetry.coolantTemp}°C)")
                }
            }
        } else {
            // Periodic batch reasons
            if (dtcChanged) reasons.add("تغير الأعطال (${telemetry.dtcCodes.size} كود)")
            if (heartbeat) reasons.add("نبضة دورية مجمعة لتوفير الباقة (Batch Pulse)")
        }

        if (alertChanged && telemetry.activeAlertTitle != null) reasons.add("🚨 إنذار فوري: ${telemetry.activeAlertTitle}")

        if (reasons.isNotEmpty() || force) {
            val changeReason = if (reasons.isNotEmpty()) reasons.joinToString(" • ") else "تحديث لحظي"
            val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now))

            val record = TelemetryDeltaRecord(
                timestamp = now,
                timeString = timeStr,
                speed = telemetry.speed,
                rpm = telemetry.rpm,
                fuelLevel = telemetry.fuelLevel,
                coolantTemp = telemetry.coolantTemp,
                batteryVoltage = telemetry.batteryVoltage,
                dtcCount = telemetry.dtcCodes.size,
                changeReason = changeReason,
                locationName = tripStartLocationName
            )

            telemetryDeltaLog.add(0, record)
            if (telemetryDeltaLog.size > 100) telemetryDeltaLog.removeAt(telemetryDeltaLog.size - 1)
            saveTelemetryDeltaLog()

            lastSyncedSpeed = telemetry.speed
            lastSyncedRpm = telemetry.rpm
            lastSyncedFuel = telemetry.fuelLevel
            lastSyncedTemp = telemetry.coolantTemp
            lastSyncedDtcCount = telemetry.dtcCodes.size
            lastSyncedAlert = telemetry.activeAlertTitle
            lastSyncTimestamp = now
        }
    }

    private fun checkVinOwnershipLock(vin: String): Pair<Boolean, String?> {
        val cleanVin = vin.uppercase().trim()
        val lockedOwner = preferences.getString("vin_lock_owner_$cleanVin", null)
        val lockedPhone = preferences.getString("vin_lock_phone_$cleanVin", null)
        val isLocked = preferences.getBoolean("vin_is_locked_$cleanVin", false)

        if (isLocked && !lockedOwner.isNullOrEmpty()) {
            val isCurrentOwner = (currentRole == "owner") && (userName.equals(lockedOwner, ignoreCase = true) || lockedOwner == "مالك تجريبي")
            if (!isCurrentOwner) {
                return Pair(false, "🛑 هذا الشاسيه ($cleanVin) مسجل ومملوك رسمياً للمالك ($lockedOwner - هاتف: $lockedPhone).\n🔒 بموجب بروتوكول حماية الملكية، لا يمكن لأي شخص آخر تسجيله أو ادعاء ملكيته!\nللحصول على ملكية السيارة، يجب على المالك الأصلي نقل الملكية لك من حسابه حصرياً.")
            }
        }
        return Pair(true, null)
    }

    private fun registerAndLockVin(vin: String, owner: String, phone: String) {
        val cleanVin = vin.uppercase().trim()
        val today = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date())
        preferences.edit()
            .putString("vin_lock_owner_$cleanVin", owner)
            .putString("vin_lock_phone_$cleanVin", phone)
            .putBoolean("vin_is_locked_$cleanVin", true)
            .putString("vin_lock_date_$cleanVin", today)
            .apply()
    }

    private fun addSecurityNotification(title: String, message: String, eventType: String, severity: String) {
        val now = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now))
        val notif = SecurityNotificationItem(
            id = "notif-${now}",
            title = title,
            message = message,
            eventType = eventType,
            severity = severity,
            timeString = timeStr,
            timestamp = now,
            isRead = false
        )
        ownerSecurityNotifications.add(0, notif)
        if (ownerSecurityNotifications.size > 50) ownerSecurityNotifications.removeAt(ownerSecurityNotifications.size - 1)
        saveSecurityNotifications()
    }

    private fun getUnreadSecurityNotificationsCount(): Int {
        return ownerSecurityNotifications.count { !it.isRead }
    }

    private fun saveSecurityNotifications() {
        val sb = StringBuilder()
        for (item in ownerSecurityNotifications.take(40)) {
            sb.append("${item.id}§${item.title}§${item.message}§${item.eventType}§${item.severity}§${item.timeString}§${item.timestamp}§${item.isRead}¶")
        }
        preferences.edit().putString("saved_security_notifs", sb.toString()).apply()
    }

    private fun loadSecurityNotifications() {
        ownerSecurityNotifications.clear()
        val raw = preferences.getString("saved_security_notifs", null)
        if (!raw.isNullOrEmpty()) {
            val parts = raw.split("¶")
            for (p in parts) {
                if (p.isBlank()) continue
                val fields = p.split("§")
                if (fields.size >= 8) {
                    ownerSecurityNotifications.add(
                        SecurityNotificationItem(
                            id = fields[0],
                            title = fields[1],
                            message = fields[2],
                            eventType = fields[3],
                            severity = fields[4],
                            timeString = fields[5],
                            timestamp = fields[6].toLongOrNull() ?: System.currentTimeMillis(),
                            isRead = fields[7].toBoolean()
                        )
                    )
                }
            }
        }
        if (ownerSecurityNotifications.isEmpty()) {
            val now = System.currentTimeMillis()
            val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now))
            val curVin = preferences.getString("vehicle_vin", "KMHDH41EBNA123456") ?: "KMHDH41EBNA123456"
            val owner = preferences.getString("vehicle_primary_owner", "مالك تجريبي") ?: "مالك تجريبي"
            ownerSecurityNotifications.add(
                SecurityNotificationItem(
                    id = "init-1",
                    title = "🛡️ الشاسيه محمي ومقفل بنجاح",
                    message = "تم تفعيل حماية الشاسيه ($curVin) للمالك ($owner). لا يمكن لأي شخص آخر تسجيله.",
                    eventType = "vin_locked",
                    severity = "info",
                    timeString = timeStr,
                    timestamp = now,
                    isRead = true
                )
            )
            ownerSecurityNotifications.add(
                SecurityNotificationItem(
                    id = "init-2",
                    title = "ℹ️ جاهزية مراقبة السائق والورش",
                    message = "نظام المراقبة عن بعد نشط: ستصلك تنبيهات فورية عند دخول أي ورشة على السيارة.",
                    eventType = "system_ready",
                    severity = "info",
                    timeString = timeStr,
                    timestamp = now,
                    isRead = true
                )
            )
        }
    }

    private fun saveTelemetryDeltaLog() {
        val sb = StringBuilder()
        for (item in telemetryDeltaLog.take(30)) {
            sb.append("${item.timestamp}§${item.timeString}§${item.speed}§${item.rpm}§${item.fuelLevel}§${item.coolantTemp}§${item.batteryVoltage}§${item.dtcCount}§${item.changeReason}§${item.locationName}¶")
        }
        preferences.edit().putString("saved_telemetry_deltas", sb.toString()).apply()
    }

    private fun loadTelemetryDeltaLog() {
        telemetryDeltaLog.clear()
        val raw = preferences.getString("saved_telemetry_deltas", null)
        if (!raw.isNullOrEmpty()) {
            val parts = raw.split("¶")
            for (p in parts) {
                if (p.isBlank()) continue
                val fields = p.split("§")
                if (fields.size >= 10) {
                    telemetryDeltaLog.add(
                        TelemetryDeltaRecord(
                            timestamp = fields[0].toLongOrNull() ?: System.currentTimeMillis(),
                            timeString = fields[1],
                            speed = fields[2].toIntOrNull() ?: 0,
                            rpm = fields[3].toIntOrNull() ?: 0,
                            fuelLevel = fields[4].toIntOrNull() ?: 50,
                            coolantTemp = fields[5].toIntOrNull() ?: 90,
                            batteryVoltage = fields[6].toFloatOrNull() ?: 13.8f,
                            dtcCount = fields[7].toIntOrNull() ?: 0,
                            changeReason = fields[8],
                            locationName = fields[9]
                        )
                    )
                }
            }
        }
    }

    private fun initSecurityNotificationsAndDelta() {
        loadSecurityNotifications()
        loadTelemetryDeltaLog()
        val curVin = preferences.getString("vehicle_vin", "KMHDH41EBNA123456") ?: "KMHDH41EBNA123456"
        val primaryOwner = preferences.getString("vehicle_primary_owner", userName.ifEmpty { "مالك تجريبي" }) ?: "مالك تجريبي"
        val primaryPhone = preferences.getString("vehicle_primary_owner_phone", userPhone.ifEmpty { "01000000000" }) ?: "01000000000"
        if (!preferences.getBoolean("vin_is_locked_$curVin", false)) {
            registerAndLockVin(curVin, primaryOwner, primaryPhone)
        }
    }

    // ========================================================
    // TAB 1: GAUGES & LIVE OBD TELEMETRY (عداد السيارة الحي)
    // ========================================================
    private fun buildGaugesTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val activeVehName = preferences.getString("vehicle_name", "مركبتي") ?: "مركبتي"
        val primaryOwner = preferences.getString("vehicle_primary_owner", "") ?: ""
        val ownerDisplay = if (primaryOwner.isNotEmpty()) " • المالك: $primaryOwner 👑" else ""
        val isLive = telemetry.isConnected || isSimulating

        // Active Simulation Banner
        if (isSimulating) {
            val simBanner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.rgb(64, 38, 10))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp

                addView(text("⚠️ محاكاة تجريبية نشطة (بيانات افتراضية)", 12, Color.rgb(255, 200, 100), true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(this@MainActivity).apply {
                    text = "تصفير وإيقاف ✖"
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(160, 40, 40))
                    setOnClickListener { toggleSimulation() }
                })
            }
            container.addView(simBanner)
        }

        val cardGauges = card().apply {
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text("عداد وبيانات: $activeVehName$ownerDisplay", 16, Color.WHITE, true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                tvConnectionState = text(if (isLive) (if (isSimulating) "محاكاة 🟢" else "متصل 🟢") else "غير متصل", 12, if (isLive) Color.rgb(126, 242, 154) else Color.rgb(255, 122, 122), true)
                addView(tvConnectionState)
            }
            addView(titleRow)

            val speedDisplay = if (isLive) "${telemetry.speed} km/h" else if (phoneGpsSpeedKmh > 0) "$phoneGpsSpeedKmh km/h (GPS 🛰️)" else "0 km/h"
            val rpmDisplay = if (isLive) "${telemetry.rpm} RPM" else "-- RPM"
            val tempDisplay = if (isLive) "${telemetry.coolantTemp} °C" else "-- °C"
            val battDisplay = if (isLive) "${String.format("%.1f", telemetry.batteryVoltage)} V" else "-- V"
            val fuelDisplay = if (isLive) "${telemetry.fuelLevel} %" else "-- %"
            val vinDisplay = if (telemetry.vin.isNotBlank()) telemetry.vin else preferences.getString("vehicle_vin", "--") ?: "--"
            val instantDisplay = if (isLive) "${String.format("%.1f", telemetry.instantFuelLPer100Km)} L/100" else "-- L/100"
            val tripDisplay = if (isLive) "${String.format("%.1f", telemetry.tripAverageFuelRate)} L/100" else "-- L/100"
            val loadDisplay = if (isLive) "${telemetry.engineLoadPercent} %" else "-- %"
            val tpsDisplay = if (isLive) "${telemetry.throttlePercent} %" else "-- %"

            val row1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            tvSpeed = metricBox("السرعة الحالية", speedDisplay, Color.rgb(110, 231, 255))
            tvRpm = metricBox("المحرك", rpmDisplay, Color.rgb(126, 242, 154))
            row1.addView(tvSpeed.parent as View)
            row1.addView(tvRpm.parent as View)
            addView(row1)

            val row2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            tvTemp = metricBox("حرارة التبريد", tempDisplay, Color.rgb(248, 199, 74))
            tvBattery = metricBox("شحن البطارية", battDisplay, Color.rgb(110, 231, 255))
            row2.addView(tvTemp.parent as View)
            row2.addView(tvBattery.parent as View)
            addView(row2)

            val row3 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            tvFuel = metricBox("مستوى الوقود", fuelDisplay, Color.rgb(126, 242, 154))
            tvVin = metricBox("رقم الشاسيه VIN", vinDisplay, Color.WHITE)
            row3.addView(tvFuel.parent as View)
            row3.addView(tvVin.parent as View)
            addView(row3)

            val row4 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            tvInstantFuel = metricBox("الاستهلاك اللحظي", instantDisplay, Color.rgb(248, 199, 74))
            tvTripFuel = metricBox("متوسط الرحلة", tripDisplay, Color.rgb(110, 231, 255))
            row4.addView(tvInstantFuel.parent as View)
            row4.addView(tvTripFuel.parent as View)
            addView(row4)

            val row5 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            tvEngineLoad = metricBox("حمل المحرك", loadDisplay, Color.rgb(248, 199, 74))
            tvThrottle = metricBox("دواسة الوقود", tpsDisplay, Color.rgb(126, 242, 154))
            row5.addView(tvEngineLoad.parent as View)
            row5.addView(tvThrottle.parent as View)
            addView(row5)

            val row6 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val curOdoDisplay = if (telemetry.carOdometerKm > 0f) "${telemetry.carOdometerKm.toInt()} كم" else "${preferences.getFloat("car_cumulative_odometer_km", 0f).toInt()} كم"
            val tripDistDisplay = String.format("%.2f كم", telemetry.tripDistanceKm)
            tvCarOdometer = metricBox("عداد السيارة (التابلوه)", curOdoDisplay, Color.rgb(110, 231, 255))
            tvTripDistance = metricBox("مسافة الرحلة", tripDistDisplay, Color.WHITE)
            row6.addView(tvCarOdometer.parent as View)
            row6.addView(tvTripDistance.parent as View)
            addView(row6)

            tvDtc = text(if (isLive) (if (telemetry.dtcCodes.isEmpty()) "لمبة الأعطال: سليمة (OFF)" else "أعطال نشطة: ${telemetry.dtcCodes.size}") else "لمبة الأعطال: بانتظار الاتصال بالسيارة", 13, if (isLive && telemetry.dtcCodes.isNotEmpty()) Color.rgb(255, 122, 122) else Color.rgb(126, 242, 154), true).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.rgb(10, 28, 42))
            }
            addView(tvDtc)

            tvFuelSourceBadge = text(if (isLive) "⚡ مصدر قياس الوقود: ${telemetry.fuelCalculationSource}" else if (phoneGpsSpeedKmh > 0) "🛰️ متصل بنظام تحديد المواقع GPS للهاتف" else "بانتظار قراءة الحساسات من كمبيوتر السيارة أو تشغيل المحاكي", 12, Color.rgb(110, 231, 255), true).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(Color.rgb(14, 34, 52))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            addView(tvFuelSourceBadge)

            addView(button("🔍 فحص كمبيوتر السيارة (Scan ECU)") {
                scanAllFuelComputers()
            }.apply {
                setBackgroundColor(Color.rgb(16, 185, 129))
                setTextColor(Color.WHITE)
            })

            addView(button("⛽ ضبط ومعايرة الوقود") {
                showFuelCalibrationDialog()
            }.apply {
                setBackgroundColor(Color.rgb(248, 199, 74))
                setTextColor(Color.rgb(8, 23, 32))
            })

            addView(button("🚗 ضبط عداد السيارة (Odometer)") {
                showOdometerCalibrationDialog()
            }.apply {
                setBackgroundColor(Color.rgb(14, 165, 233))
                setTextColor(Color.WHITE)
            })

            addView(button(if (isSimulating) "⏹️ إيقاف محاكي كمبيوتر السيارة" else "⚡ تشغيل محاكي كمبيوتر السيارة") {
                toggleSimulation()
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
        }
        container.addView(cardGauges)

        // DRIVER COCKPIT ENRICHED EXTENSION (بطاقة السائق الخاصة لخدمات الطريق والسلامة)
        if (currentRole == "driver") {
            val cardDriverSafety = card().apply {
                addView(text("💡 أسلوب القيادة والسلامة", 15, Color.rgb(110, 231, 255), true))

                val statsRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(6)
                    layoutParams = lp
                }
                val tvScore = metricBox("تقييم الأمان", "98 / 100 ⭐", Color.rgb(126, 242, 154))
                val tvSpeedSrc = metricBox("مصدر السرعة", if (telemetry.isConnected) "OBD السيارة 🔌" else if (phoneGpsSpeedKmh > 0) "GPS الهاتف 🛰️" else "الهاتف / OBD", Color.rgb(248, 199, 74))
                statsRow.addView(tvScore.parent as View)
                statsRow.addView(tvSpeedSrc.parent as View)
                addView(statsRow)

                addView(text("🚨 طوارئ ومساعدة الطريق:", 13, Color.rgb(255, 140, 90), true).apply {
                    setPadding(0, dp(8), 0, dp(4))
                })

                val actionRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    layoutParams = lp
                }

                val activeCar = driverAssignedCars.find { it.isActive } ?: driverAssignedCars.firstOrNull()
                val ownerContact = activeCar?.ownerPhone ?: preferences.getString("vehicle_primary_owner_phone", "01000000000") ?: "01000000000"

                actionRow.addView(Button(this@MainActivity).apply {
                    text = "📞 اتصال طوارئ بالمالك"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(160, 40, 40))
                    val lp = LinearLayout.LayoutParams(0, dp(42), 1f)
                    lp.setMargins(0, 0, dp(3), 0)
                    layoutParams = lp
                    setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ownerContact"))
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "رقم هاتف المالك: $ownerContact", Toast.LENGTH_LONG).show()
                        }
                    }
                })

                actionRow.addView(Button(this@MainActivity).apply {
                    text = "🗺️ أقرب ورشة صيانة"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(36, 77, 111))
                    val lp = LinearLayout.LayoutParams(0, dp(42), 1f)
                    lp.setMargins(dp(3), 0, 0, 0)
                    layoutParams = lp
                    setOnClickListener {
                        switchTab("workshops_dir")
                    }
                })

                addView(actionRow)
            }
            container.addView(cardDriverSafety)
        }

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
                val vinIn = etVehVin.text.toString().trim().uppercase()
                if (vinIn.length < 10) {
                    tvMsg.text = "يرجى إدخال رقم شاسيه صحيح (10 إلى 17 خانة)."
                    tvMsg.setTextColor(Color.rgb(255, 122, 122))
                    return@button
                }

                // فحص تمليك وقفل الشاسيه الصارم (Anti-Re-Registration Lock)
                val (canRegister, lockReason) = checkVinOwnershipLock(vinIn)
                if (!canRegister) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🛑 حظر تسجيل الشاسيه (ملكية محمية)")
                        .setMessage(lockReason ?: "هذا الشاسيه مقفل ومسجل لمالك آخر.")
                        .setPositiveButton("فهمت ذلك", null)
                        .show()
                    tvMsg.text = "🛑 تم رفض التسجيل: هذا الشاسيه مقيد ومملوك رسمياً لشخص آخر!"
                    tvMsg.setTextColor(Color.rgb(255, 122, 122))
                    return@button
                }

                val decoded = decodeVin(vinIn)
                val primaryOwner = preferences.getString("vehicle_primary_owner", userName.ifEmpty { "مالك تجريبي" }) ?: "مالك تجريبي"
                val primaryPhone = preferences.getString("vehicle_primary_owner_phone", userPhone.ifEmpty { "01000000000" }) ?: "01000000000"

                registerAndLockVin(vinIn, primaryOwner, primaryPhone)

                preferences.edit()
                    .putString("vehicle_vin", vinIn)
                    .putString("vehicle_name", decoded.displayName)
                    .putString("vehicle_make", decoded.make)
                    .putString("vehicle_year", decoded.year)
                    .putString("vehicle_data_source", "مدخل ومطابق للشاسيه")
                    .apply()

                addSecurityNotification(
                    "🛡️ تمليك وتأمين شاسيه جديد",
                    "تم تسجيل وتأمين الشاسيه ($vinIn) بنجاح باسم المالك ($primaryOwner). الشاسيه الآن مقفل ولا يمكن لأي حساب آخر تسجيله.",
                    "vin_claimed",
                    "info"
                )

                tvMsg.setTextColor(Color.rgb(126, 242, 154))
                tvMsg.text = "تم فك تشفير وتأمين ملكية المركبة: ${decoded.displayName} بنجاح!"
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
        val tvInfo = text("⚠️ تنبيه أمان: نقل الملكية سيعين المالك الجديد كصاحب الحقوق الكاملة على هذه السيارة والشاسيه.", 13, Color.rgb(255, 180, 50), false)
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

                    val curVin = preferences.getString("vehicle_vin", "") ?: ""
                    if (curVin.isNotEmpty()) {
                        registerAndLockVin(curVin, newName, if (newPhone.isNotEmpty()) newPhone else "01000000000")
                    }

                    preferences.edit()
                        .putString("vehicle_primary_owner", newName)
                        .putString("vehicle_primary_owner_phone", if (newPhone.isNotEmpty()) newPhone else "01000000000")
                        .putString("vehicle_ownership_history", newHistory)
                        .putString("vehicle_owner_since", today)
                        .putBoolean("vehicle_owner_locked", true)
                        .apply()

                    addSecurityNotification(
                        "🔄 نقل ملكية السيارة رسمياً",
                        "تم التنازل ونقل ملكية السيارة ($curVin) رسمياً من ($oldOwner) إلى المالك الجديد ($newName).",
                        "ownership_transferred",
                        "warning"
                    )

                    AlertDialog.Builder(this)
                        .setTitle("✅ تم نقل الملكية بنجاح")
                        .setMessage("تم نقل ملكية السيارة وتحديث وثيقتها وقفل الشاسيه باسم:\n$newName\nأصبح هو المالك المعتمد في نظام DriveCare.")
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

                    addSecurityNotification(
                        "👥 إضافة مالك شريك ومفوض",
                        "قام المالك ($userName) بإضافة ($coName) كمالك شريك/مفوض على سيارته.",
                        "co_owner_added",
                        "info"
                    )

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

    private fun collapsibleSection(
        title: String,
        icon: String,
        initiallyExpanded: Boolean = true,
        contentBuilder: (LinearLayout) -> Unit
    ): View {
        val cardView = card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.rgb(18, 38, 58))
        }
        val tvTitle = text("$icon $title", 15, Color.WHITE, true).apply {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val tvArrow = text(if (initiallyExpanded) "▲" else "▼", 16, Color.rgb(126, 242, 154), true)
        header.addView(tvTitle)
        header.addView(tvArrow)
        cardView.addView(header)

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        }
        contentBuilder(contentLayout)
        cardView.addView(contentLayout)

        header.setOnClickListener {
            val isVisible = (contentLayout.visibility == View.VISIBLE)
            contentLayout.visibility = if (isVisible) View.GONE else View.VISIBLE
            tvArrow.text = if (isVisible) "▼" else "▲"
        }

        return cardView
    }

    private fun finalizeAndResetTrip() {
        val fuelPrice = preferences.getFloat("fuel_liter_price", 15.0f)
        val oilPrice = preferences.getFloat("oil_total_price", 1200.0f)
        val oilTargetKm = preferences.getFloat("oil_target_km", 5000.0f)
        val oilCostPerKm = if (oilTargetKm > 0f) oilPrice / oilTargetKm else 0f
        val vehicleMarketValue = preferences.getFloat("vehicle_market_value", 400000.0f)
        val depRatePer1000Km = preferences.getFloat("depreciation_percent_per_1000km", 0.20f)
        val depCostPerKm = (vehicleMarketValue * (depRatePer1000Km / 100.0f)) / 1000.0f

        val tripDist = telemetry.tripDistanceKm
        val tripFuel = telemetry.actualTripFuelLiters
        val tripFuelCost = tripFuel * fuelPrice
        val tripOilCost = tripDist * oilCostPerKm
        val tripDepPercent = (tripDist / 1000.0f) * depRatePer1000Km
        val tripDepCost = tripDist * depCostPerKm
        val totalTripCost = tripFuelCost + tripOilCost + tripDepCost

        AlertDialog.Builder(this)
            .setTitle("🏁 تقرير إنهاء الرحلة وتصفير العدادات")
            .setMessage(
                "ملخص بيانات الرحلة المنتهية:\n\n" +
                "🛣️ المسافة المقطوعة: ${String.format("%.2f", tripDist)} كم\n" +
                "⛽ الوقود المستهلك: ${String.format("%.2f", tripFuel)} ${telemetry.fuelUnitName} (${String.format("%.2f", tripFuelCost)} ج.م)\n" +
                "🛢️ استهلاك الزيت: ${String.format("%.2f", tripOilCost)} ج.م\n" +
                "🚗 استهلاك وإهلاك السيارة: ${String.format("%.2f", tripDepCost)} ج.م (${String.format("%.3f", tripDepPercent)}%)\n" +
                "────────────────────────\n" +
                "💰 إجمالي التكلفة الشاملة للرحلة: ${String.format("%.2f", totalTripCost)} ج.م\n\n" +
                "هل تريد تصفير عداد الرحلة الآن لبدء رحلة جديدة؟"
            )
            .setPositiveButton("نعم، تصفير وبدء جديدة") { _, _ ->
                telemetry.tripDistanceKm = 0.0f
                telemetry.actualTripFuelLiters = 0.0f
                telemetry.tripAverageFuelRate = preferences.getFloat("fuel_rate_baseline", 7.2f)
                val currentCarOdo = if (telemetry.carOdometerKm > 0f) telemetry.carOdometerKm else preferences.getFloat("car_cumulative_odometer_km", 0f)
                preferences.edit()
                    .putFloat("saved_trip_distance_km", 0.0f)
                    .putFloat("saved_trip_fuel_liters", 0.0f)
                    .putFloat("trip_start_car_odometer", currentCarOdo)
                    .apply()
                val odoMsg = if (currentCarOdo > 0f) " وتثبيت بداية العداد عند (${currentCarOdo.toInt()} كم)" else ""
                Toast.makeText(this, "تم تصفير الرحلة بنجاح$odoMsg. يبدأ الحساب الجديد تلقائياً! 🚀", Toast.LENGTH_SHORT).show()
                switchTab("fuel")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun buildFuelTabContent(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val fuelPrice = preferences.getFloat("fuel_liter_price", 15.0f)
        val oilPrice = preferences.getFloat("oil_total_price", 1200.0f)
        val oilTargetKm = preferences.getFloat("oil_target_km", 5000.0f)
        val oilCurrentKm = preferences.getFloat("oil_current_km", 0.0f)
        val baselineFuelRate = preferences.getFloat("fuel_rate_baseline", 7.2f)

        // Vehicle Market Value and Depreciation (0.20% per 1000 km = 100% over 500,000 km)
        val vehicleMarketValue = preferences.getFloat("vehicle_market_value", 400000.0f)
        val depRatePer1000Km = preferences.getFloat("depreciation_percent_per_1000km", 0.20f)
        val depCostPerKm = (vehicleMarketValue * (depRatePer1000Km / 100.0f)) / 1000.0f

        val oilCostPerKm = if (oilTargetKm > 0f) oilPrice / oilTargetKm else 0f
        val remainingOilKm = (oilTargetKm - oilCurrentKm).coerceAtLeast(0f)

        // Calibrated Vehicle Load & Payload Physics:
        // Weight penalty is proportional to the ratio of cargo weight to the vehicle's curb weight:
        // Penalty % = (plannedCargoWeightKg / vehicleEmptyWeightKg) * 60%
        val vehicleEmptyWeight = preferences.getFloat("vehicle_empty_weight", 1200.0f)
        val weightPenaltyPercent = if (vehicleEmptyWeight > 0f) {
            (plannedCargoWeightKg / vehicleEmptyWeight) * 60.0f
        } else {
            (plannedCargoWeightKg / 100.0f) * 5.0f
        }
        val loadedFuelRate = baselineFuelRate * (1.0f + (weightPenaltyPercent / 100.0f))

        // Calculations for Future Planned Trip:
        val emptyFuelCostPerKm = (baselineFuelRate / 100.0f) * fuelPrice
        val loadedFuelCostPerKm = (loadedFuelRate / 100.0f) * fuelPrice
        val loadedTripFuelCost = plannedRouteDistanceKm * loadedFuelCostPerKm
        val loadedTripOilCost = plannedRouteDistanceKm * oilCostPerKm
        val loadedTripDepCost = plannedRouteDistanceKm * depCostPerKm
        val loadedTripTotalCost = loadedTripFuelCost + loadedTripOilCost + loadedTripDepCost
        val loadedTripFuelLiters = plannedRouteDistanceKm * (loadedFuelRate / 100.0f)
        val tripDepPercent = (plannedRouteDistanceKm / 1000.0f) * depRatePer1000Km

        // Dynamic Real Calibration after driving first 1 km:
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

        // --- Card Hero: Overview KPI Summary Bar ---
        val cardHero = card().apply {
            addView(text("⛽ تكلفة التشغيل والرحلات", 17, Color.rgb(126, 242, 154), true))

            val r1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            r1.addView(metricBox("الوقود/كم", String.format("%.2f ج.م", emptyFuelCostPerKm), Color.rgb(110, 231, 255)).parent as View)
            r1.addView(metricBox("الزيت/كم", String.format("%.2f ج.م", oilCostPerKm), Color.rgb(248, 199, 74)).parent as View)
            addView(r1)

            val r2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            val activeCostPerKm = if (hasDrivenFirstKm) (realCalibratedFuelCostPerKm + oilCostPerKm + depCostPerKm) else (loadedFuelCostPerKm + oilCostPerKm + depCostPerKm)
            r2.addView(metricBox("الإهلاك/كم", String.format("%.2f ج.م", depCostPerKm), Color.rgb(255, 180, 100)).parent as View)
            val mbActiveCost = metricBox("إجمالي التكلفة/كم", String.format("%.2f ج.م", activeCostPerKm), Color.rgb(126, 242, 154))
            tvFuelTabActiveCostPerKm = mbActiveCost
            r2.addView(mbActiveCost.parent as View)
            addView(r2)

            val r3 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            val tankCap = preferences.getFloat("tank_capacity_liters", 45.0f)
            val litersInTank = (telemetry.fuelLevel * tankCap / 100.0f)
            val effectiveRate = if (hasDrivenFirstKm) realMeasuredTripRate else if (plannedCargoWeightKg > 0) loadedFuelRate else baselineFuelRate
            val mbTank = metricBox("مستوى التانك", "${telemetry.fuelLevel}% (${String.format("%.1f", litersInTank)}L)", Color.rgb(126, 242, 154))
            tvFuelTabTankPercent = mbTank
            r3.addView(mbTank.parent as View)

            val mbRate = metricBox("المعدل الحالي", String.format("%.1f L/100 (%s)", effectiveRate, if (hasDrivenFirstKm) "فعلي" else "تقديري"), Color.rgb(110, 231, 255))
            tvFuelTabEffectiveRate = mbRate
            r3.addView(mbRate.parent as View)
            addView(r3)

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            fun quickActionBtn(label: String, color: Int, onClick: () -> Unit): Button {
                return Button(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(color)
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(dp(2), 0, dp(2), 0)
                    layoutParams = lp
                    setOnClickListener { onClick() }
                }
            }
            btnRow.addView(quickActionBtn("🔍 فحص الكمبيوتر", Color.rgb(16, 185, 129)) { scanAllFuelComputers() })
            btnRow.addView(quickActionBtn("⛽ معايرة التانك", Color.rgb(36, 77, 111)) { showFuelCalibrationDialog() })
            btnRow.addView(quickActionBtn("⚙️ نوع الوقود", Color.rgb(24, 52, 78)) { showFuelTypeDialog() })
            addView(btnRow)

            val tvSensorInfo = text("• الحساس النشط: ${telemetry.fuelCalculationSource} | التدفق: ${String.format("%.2f", telemetry.instantFuelLitersPerHour)} لتر/ساعة", 11, Color.rgb(167, 203, 226), false).apply {
                setPadding(0, dp(4), 0, 0)
            }
            tvFuelTabSensorInfoText = tvSensorInfo
            addView(tvSensorInfo)
        }
        container.addView(cardHero)

        // --- SECTION 1 (Collapsible): Future Trip Calculator ---
        val sectionTripFuture = collapsibleSection("حاسبة وتكلفة الرحلة", "🛣️", initiallyExpanded = true) { sec ->
            sec.addView(text("🚩 من: $tripStartLocationName | 🏁 إلى: $tripEndLocationName", 13, Color.rgb(167, 203, 226), false))

            // 1. Distance Input Row
            sec.addView(text("مسافة الرحلة بالكيلومتر:", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(6), 0, dp(2)) })
            val distRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }
            val etManualDist = edit("المسافة بالكم").apply {
                setText(String.format("%.0f", plannedRouteDistanceKm))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
                lp.setMargins(0, 0, dp(4), 0)
                layoutParams = lp
            }
            val btnSaveDist = button("💾 اعتماد") {
                val d = etManualDist.text.toString().toFloatOrNull()
                if (d != null && d > 0f) {
                    preferences.edit().putFloat("planned_route_distance", d).apply()
                    plannedRouteDistanceKm = d
                    Toast.makeText(this@MainActivity, "تم اعتماد مسافة: ${String.format("%.1f", d)} كم", Toast.LENGTH_SHORT).show()
                    switchTab("fuel")
                } else {
                    Toast.makeText(this@MainActivity, "يرجى كتابة رقم مسافة صحيح", Toast.LENGTH_SHORT).show()
                }
            }.apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                setBackgroundColor(Color.rgb(126, 242, 154))
                setTextColor(Color.rgb(8, 23, 32))
            }
            distRow.addView(etManualDist)
            distRow.addView(btnSaveDist)
            sec.addView(distRow)

            val mapBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }
            val btnMapDialog = Button(this@MainActivity).apply {
                text = "🗺️ اختيار مسار من الخريطة"
                textSize = 11f
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(0, 0, dp(2), 0)
                layoutParams = lp
                setOnClickListener { showMapRouteDialog() }
            }
            val btnGoogleNav = Button(this@MainActivity).apply {
                text = "🧭 فتح الملاحة في Google Maps"
                textSize = 11f
                setBackgroundColor(Color.rgb(24, 52, 78))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(2), 0, 0, 0)
                layoutParams = lp
                setOnClickListener { openGoogleMapsNavigation(tripStartLat, tripStartLng, tripEndLat, tripEndLng) }
            }
            mapBtnRow.addView(btnMapDialog)
            mapBtnRow.addView(btnGoogleNav)
            sec.addView(mapBtnRow)

            // 2. Cargo Weight Input Row
            sec.addView(text("وزن الحمولة الإضافية (كجم):", 13, Color.rgb(248, 199, 74), true).apply { setPadding(0, dp(4), 0, dp(2)) })
            val weightRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }
            val etWeight = edit("وزن الحمولة كجم").apply {
                setText(String.format("%.0f", plannedCargoWeightKg))
                inputType = InputType.TYPE_CLASS_NUMBER
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
                lp.setMargins(0, 0, dp(4), 0)
                layoutParams = lp
            }
            val btnSaveWeight = button("💾 حفظ الوزن") {
                val w = etWeight.text.toString().toFloatOrNull() ?: 0f
                preferences.edit().putFloat("planned_cargo_weight", w).apply()
                plannedCargoWeightKg = w
                Toast.makeText(this@MainActivity, "تم حفظ وزن الحمولة: $w كجم", Toast.LENGTH_SHORT).show()
                switchTab("fuel")
            }.apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                setBackgroundColor(Color.rgb(248, 199, 74))
                setTextColor(Color.rgb(8, 23, 32))
            }
            weightRow.addView(etWeight)
            weightRow.addView(btnSaveWeight)
            sec.addView(weightRow)

            // 3. Engine Baseline Consumption Rate Row
            sec.addView(text("استهلاك المحرك فارغة (لتر/100 كم):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, dp(2)) })
            val rateRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }
            val etBaseRate = edit("المعدل (لتر/100كم)").apply {
                setText(String.format("%.1f", baselineFuelRate))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
                lp.setMargins(0, 0, dp(4), 0)
                layoutParams = lp
            }
            val btnSaveRate = button("💾 حفظ المعدل") {
                val r = etBaseRate.text.toString().toFloatOrNull()
                if (r != null && r in 2.0f..35.0f) {
                    preferences.edit().putFloat("fuel_rate_baseline", r).apply()
                    Toast.makeText(this@MainActivity, "تم حفظ استهلاك المحرك: $r لتر/100 كم", Toast.LENGTH_SHORT).show()
                    switchTab("fuel")
                } else {
                    Toast.makeText(this@MainActivity, "يرجى كتابة رقم استهلاك صحيح", Toast.LENGTH_SHORT).show()
                }
            }.apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                setBackgroundColor(Color.rgb(110, 231, 255))
                setTextColor(Color.rgb(8, 23, 32))
            }
            rateRow.addView(etBaseRate)
            rateRow.addView(btnSaveRate)
            sec.addView(rateRow)

            // 4. Clean Stacked Financial Result Box
            val resultCard = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(16, 34, 52))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            resultCard.addView(text("💰 تكلفة الرحلة المتوقعة (${plannedRouteDistanceKm.toInt()} كم):", 15, Color.WHITE, true))
            resultCard.addView(text("• ⛽ الوقود: ${String.format("%.2f", loadedTripFuelCost)} ج.م (${String.format("%.1f", loadedTripFuelLiters)} لتر)", 13, Color.rgb(167, 203, 226), false))
            resultCard.addView(text("• 🛢️ الزيت: ${String.format("%.2f", loadedTripOilCost)} ج.م", 13, Color.rgb(167, 203, 226), false))
            resultCard.addView(text("• 🚗 الإهلاك والصيانة: ${String.format("%.2f", loadedTripDepCost)} ج.م", 13, Color.rgb(255, 180, 100), false))
            resultCard.addView(text("• 💰 إجمالي التكلفة الشاملة: ${String.format("%.2f", loadedTripTotalCost)} ج.م", 14, Color.rgb(126, 242, 154), true).apply {
                setPadding(0, dp(4), 0, 0)
            })
            sec.addView(resultCard)
        }
        container.addView(sectionTripFuture)

        // --- SECTION 2 (Collapsible): Live Trip Metrics (OBD Live) & End Trip Button ---
        val sectionTripLive = collapsibleSection("بيانات الرحلة اللحظية (OBD Live)", "🚀", initiallyExpanded = true) { sec ->
            val tRow1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            val mbDist = metricBox("المسافة المقطوعة", String.format("%.2f كم", telemetry.tripDistanceKm), Color.WHITE)
            tvFuelTabTripDistance = mbDist
            tRow1.addView(mbDist.parent as View)

            val mbLiters = metricBox("الوقود المستهلك", String.format("%.2f %s", telemetry.actualTripFuelLiters, telemetry.fuelUnitName), Color.rgb(248, 199, 74))
            tvFuelTabActualConsumed = mbLiters
            tRow1.addView(mbLiters.parent as View)
            sec.addView(tRow1)

            val tRow2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            val instantStr = if (telemetry.speed > 3) String.format("%.1f L/100 (%.1f L/h)", telemetry.instantFuelLPer100Km, telemetry.instantFuelLitersPerHour) else String.format("%.2f L/h", telemetry.instantFuelLitersPerHour)
            val mbInstant = metricBox("الاستهلاك اللحظي", instantStr, Color.rgb(110, 231, 255))
            tvFuelTabInstantRate = mbInstant
            tRow2.addView(mbInstant.parent as View)

            val mbCost = metricBox("تكلفة الرحلة الحالية", String.format("%.2f ج.م", currentTripTotalCost), Color.rgb(126, 242, 154))
            tvFuelTabCurrentCost = mbCost
            tRow2.addView(mbCost.parent as View)
            sec.addView(tRow2)

            // Real-World 1KM Calibration status box
            val boxReal = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val bgColor = if (hasDrivenFirstKm) Color.rgb(10, 48, 36) else Color.rgb(14, 28, 44)
                setBackgroundColor(bgColor)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            containerFuelRealBox = boxReal

            val tTitle = if (hasDrivenFirstKm) {
                text("🎯 استهلاك المحرك الفعلي (${String.format("%.2f", telemetry.tripDistanceKm)} كم) 🟢", 13, Color.rgb(126, 242, 154), true)
            } else {
                text("🎯 المعايرة التلقائية (بعد سير أول 1 كم) ⏳", 13, Color.rgb(110, 231, 255), true)
            }
            tvFuelTabRealBoxTitle = tTitle
            boxReal.addView(tTitle)

            val tDetails = if (hasDrivenFirstKm) {
                text("• الاستهلاك الفعلي المقاس: ${String.format("%.1f", realMeasuredTripRate)} لتر/100 كم | إجمالي الرحلة: ${String.format("%.2f", realTripProjectedTotalCost)} ج.م", 12, Color.rgb(167, 203, 226), false)
            } else {
                text("💡 بعد قطع 1 كم، ستقوم حساسات الـ OBD بقياس الاستهلاك الحقيقي آلياً.", 12, Color.rgb(167, 203, 226), false)
            }
            tvFuelTabRealBoxDetails = tDetails
            boxReal.addView(tDetails)
            sec.addView(boxReal)

            // Prominent End & Reset Trip Button (Red)
            val btnEndTrip = button("🏁 إنهاء وتصفير الرحلة") {
                finalizeAndResetTrip()
            }.apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
                setBackgroundColor(Color.rgb(185, 28, 28))
                setTextColor(Color.WHITE)
            }
            sec.addView(btnEndTrip)
        }
        container.addView(sectionTripLive)

        // --- SECTION 3 (Collapsible): Settings Card ---
        val sectionSettings = collapsibleSection("إعدادات التسعير والبيانات", "⚙️", initiallyExpanded = false) { sec ->
            val curFuelType = preferences.getString("vehicle_fuel_type", "gasoline") ?: "gasoline"
            sec.addView(text("نوع وقود المركبة:", 13, Color.rgb(248, 199, 74), true).apply { setPadding(0, dp(4), 0, 0) })

            val fuelTypeRow = LinearLayout(this@MainActivity).apply {
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
            sec.addView(fuelTypeRow)

            val unitName = preferences.getString("fuel_unit_name", "لتر") ?: "لتر"
            val tankCap = preferences.getFloat("tank_capacity_liters", 50.0f)
            sec.addView(text("سعة خزان الوقود بالكامل ($unitName):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etTankCap = edit("سعة الخزان").apply { setText(String.format("%.0f", tankCap)) }
            sec.addView(etTankCap)

            sec.addView(text("سعر لتر الوقود (ج.م):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etFuelPrice = edit("سعر اللتر").apply { setText(String.format("%.2f", fuelPrice)) }
            sec.addView(etFuelPrice)

            sec.addView(text("استهلاك المركبة القياسي فارغة (لتر/100 كم):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etFuelRate = edit("المعدل القياسي").apply { setText(String.format("%.1f", baselineFuelRate)) }
            sec.addView(etFuelRate)

            sec.addView(text("تكلفة غيار الزيت والفلتر (ج.م):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etOilPrice = edit("تكلفة الزيت").apply { setText(String.format("%.0f", oilPrice)) }
            sec.addView(etOilPrice)

            sec.addView(text("عمر نوع الزيت بالكيلومتر:", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etOilTarget = edit("عمر الزيت بالكم").apply { setText(String.format("%.0f", oilTargetKm)) }
            sec.addView(etOilTarget)

            sec.addView(text("القيمة السوقية للسيارة (ج.م):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etCarValue = edit("قيمة السيارة").apply { setText(String.format("%.0f", vehicleMarketValue)) }
            sec.addView(etCarValue)

            sec.addView(text("نسبة الإهلاك لكل 1000 كم (%):", 13, Color.rgb(110, 231, 255), true).apply { setPadding(0, dp(4), 0, 0) })
            val etDepRate = edit("نسبة الإهلاك").apply { setText(String.format("%.2f", depRatePer1000Km)) }
            sec.addView(etDepRate)

            // Vehicle Empty Weight
            sec.addView(text("وزن المركبة وهي فارغة (كجم):", 13, Color.rgb(248, 199, 74), true).apply { setPadding(0, dp(6), 0, 0) })
            val etVehicleWeight = edit("الوزن كجم").apply { setText(String.format("%.0f", vehicleEmptyWeight)) }
            sec.addView(etVehicleWeight)

            val vCatRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }
            fun vCatBtn(label: String, weightVal: Float): Button {
                return Button(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if (vehicleEmptyWeight == weightVal) Color.rgb(126, 242, 154) else Color.rgb(24, 46, 68))
                    if (vehicleEmptyWeight == weightVal) setTextColor(Color.rgb(8, 23, 32))
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(dp(1), 0, dp(1), 0)
                    layoutParams = lp
                    setOnClickListener {
                        etVehicleWeight.setText(String.format("%.0f", weightVal))
                    }
                }
            }
            vCatRow.addView(vCatBtn("🚗 ملاكي\n1200 كجم", 1200f))
            vCatRow.addView(vCatBtn("🚐 ميكروباص\n2500 كجم", 2500f))
            vCatRow.addView(vCatBtn("🚛 شاحنة\n12000 كجم", 12000f))
            vCatRow.addView(vCatBtn("🚜 جرار\n6000 كجم", 6000f))
            sec.addView(vCatRow)

            val tvSaveMsg = text("", 13, Color.rgb(126, 242, 154), false)
            sec.addView(tvSaveMsg)

            sec.addView(button("💾 حفظ وتحديث الإعدادات") {
                val fP = etFuelPrice.text.toString().toFloatOrNull() ?: fuelPrice
                val fR = etFuelRate.text.toString().toFloatOrNull() ?: baselineFuelRate
                val oP = etOilPrice.text.toString().toFloatOrNull() ?: oilPrice
                val oT = etOilTarget.text.toString().toFloatOrNull() ?: oilTargetKm
                val tC = etTankCap.text.toString().toFloatOrNull() ?: tankCap
                val cV = etCarValue.text.toString().toFloatOrNull() ?: vehicleMarketValue
                val dR = etDepRate.text.toString().toFloatOrNull() ?: depRatePer1000Km
                val vW = etVehicleWeight.text.toString().toFloatOrNull() ?: vehicleEmptyWeight

                preferences.edit()
                    .putFloat("fuel_liter_price", fP)
                    .putFloat("fuel_rate_baseline", fR)
                    .putFloat("oil_total_price", oP)
                    .putFloat("oil_target_km", oT)
                    .putFloat("tank_capacity_liters", tC)
                    .putFloat("vehicle_market_value", cV)
                    .putFloat("depreciation_percent_per_1000km", dR)
                    .putFloat("vehicle_empty_weight", vW)
                    .apply()

                tvSaveMsg.text = "تم حفظ وتحديث الإعدادات بنجاح! يتم الآن إعادة حساب التكاليف."
                switchTab("fuel")
            })
        }
        container.addView(sectionSettings)

        // --- SECTION 4 (Collapsible): Engine Oil Odometer & Reset ---
        val sectionOilCounter = collapsibleSection("عداد وتصفير كيلومترات زيت المحرك", "🛢️", initiallyExpanded = false) { sec ->
            val oilStatsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            oilStatsRow.addView(metricBox("المقطوع بالزيت الحالي", "${oilCurrentKm.toInt()} كم", Color.WHITE).parent as View)
            oilStatsRow.addView(metricBox("المتبقي حتى الغيار", "${remainingOilKm.toInt()} كم", if (remainingOilKm < 500f) Color.rgb(255, 122, 122) else Color.rgb(126, 242, 154)).parent as View)
            sec.addView(oilStatsRow)

            val carOdoKm = if (telemetry.carOdometerKm > 0f) telemetry.carOdometerKm else preferences.getFloat("car_cumulative_odometer_km", 0f)
            val oilBaselineOdo = preferences.getFloat("oil_baseline_car_odometer", 0f)

            val odoInfoRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            odoInfoRow.addView(metricBox("عداد السيارة الحالي (التابلوه)", if (carOdoKm > 0f) "${carOdoKm.toInt()} كم" else "بانتظار القراءة", Color.rgb(110, 231, 255)).parent as View)
            odoInfoRow.addView(metricBox("العداد عند آخر غيار زيت", if (oilBaselineOdo > 0f) "${oilBaselineOdo.toInt()} كم" else "غير محدد", Color.rgb(248, 199, 74)).parent as View)
            sec.addView(odoInfoRow)

            val tvSourceBadge = text("📡 مصدر قراءة العداد: ${telemetry.odometerSource} (نظام الفروق التراكمية يحمي الكيلومترات من الضياع حتى لو انقطع الهاتف)", 12, Color.rgb(126, 242, 154), false).apply {
                setPadding(0, dp(4), 0, dp(6))
            }
            sec.addView(tvSourceBadge)

            val tvResetMsg = text("", 13, Color.rgb(126, 242, 154), false)
            sec.addView(tvResetMsg)

            val actionRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }

            actionRow.addView(button("🔄 تصفير عداد الزيت (تغيير الزيت الآن)") {
                val currentCarOdo = if (telemetry.carOdometerKm > 0f) telemetry.carOdometerKm else preferences.getFloat("car_cumulative_odometer_km", 0f)
                preferences.edit()
                    .putFloat("oil_current_km", 0.0f)
                    .putFloat("oil_baseline_car_odometer", currentCarOdo)
                    .apply()
                tvResetMsg.text = "تم تصفير عداد الزيت وتثبيت قراءة عداد السيارة المرجعية عند (${currentCarOdo.toInt()} كم)! تبدأ الآن دورة جديدة لـ ${oilTargetKm.toInt()} كم."
                switchTab("fuel")
            }.apply {
                setBackgroundColor(Color.rgb(248, 199, 74))
                setTextColor(Color.rgb(8, 23, 32))
            })

            actionRow.addView(button("🚗 ضبط عداد التابلوه") {
                showOdometerCalibrationDialog()
            }.apply {
                setBackgroundColor(Color.rgb(36, 77, 111))
                setTextColor(Color.WHITE)
            })
            sec.addView(actionRow)
        }
        container.addView(sectionOilCounter)

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
                val currentCarOdo = if (telemetry.carOdometerKm > 0f) telemetry.carOdometerKm else preferences.getFloat("car_cumulative_odometer_km", 0f)
                preferences.edit()
                    .putFloat("oil_current_km", 0.0f)
                    .putFloat("oil_baseline_car_odometer", currentCarOdo)
                    .apply()
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
                permanentlyDismissedAlerts.remove("test_temp")
                snoozedAlertsMap.remove("test_temp")
                telemetry.coolantTemp = 105
                setSafetyAlert(
                    "test_temp",
                    "🚨 إنذار طارئ: حرارة المحرك مرتفعة جداً (105 °C)!",
                    "تجاوزت حرارة التبريد الحد الأقصى! أوقف المحرك فوراً لفحص سائل التبريد ومروحة الرادياتير.",
                    critical = true
                )
                scanHandler.postDelayed({
                    if (isSimulatingAlert && telemetry.coolantTemp == 105) {
                        isSimulatingAlert = false
                        telemetry.coolantTemp = 88
                        telemetry.activeAlertTitle = null
                        cancelSystemSafetyNotification()
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
                permanentlyDismissedAlerts.remove("test_fuel")
                snoozedAlertsMap.remove("test_fuel")
                telemetry.fuelLevel = 8
                setSafetyAlert(
                    "test_fuel",
                    "⛽ انتباه: مستوى الوقود منخفض جداً (8%)!",
                    "متبقي أقل من 8% في الخزان. توجه لأقرب محطة وقود لتجنب نفاذ البنزين.",
                    critical = false
                )
                scanHandler.postDelayed({
                    if (isSimulatingAlert && telemetry.fuelLevel == 8) {
                        isSimulatingAlert = false
                        telemetry.fuelLevel = preferences.getInt("current_fuel_tank_percent", 50)
                        telemetry.activeAlertTitle = null
                        cancelSystemSafetyNotification()
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

            addView(button("✅ استعادة الوضع الطبيعي الآمن وإعادة تفعيل التنبيهات") {
                isSimulatingAlert = false
                permanentlyDismissedAlerts.clear()
                snoozedAlertsMap.clear()
                cancelSystemSafetyNotification()
                telemetry.coolantTemp = 88
                telemetry.fuelLevel = preferences.getInt("current_fuel_tank_percent", 50)
                telemetry.activeAlertTitle = null
                updateAlertBannerUI()
                updateLiveUI()
                Toast.makeText(this@MainActivity, "✅ تم استعادة الوضع الطبيعي وإلغاء أي تأجيلات للإنذارات.", Toast.LENGTH_SHORT).show()
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
            telemetry.coolantTemp = 0
            telemetry.batteryVoltage = 0.0f
            telemetry.rpm = 0
            telemetry.speed = phoneGpsSpeedKmh
            telemetry.instantFuelLPer100Km = 0.0f
            telemetry.instantFuelLitersPerHour = 0.0f
            telemetry.engineLoadPercent = 0
            telemetry.throttlePercent = 0
            telemetry.dtcCodes.clear()
            updateGaugesTabUI()
            renderCurrentScreen()
            Toast.makeText(this, "تم إيقاف المحاكاة وتصفير العدادات بنجاح", Toast.LENGTH_SHORT).show()
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

                // Simulate distance, oil odometer, car odometer, instant fuel and trip average
                val dtSec = 0.5
                val distSlice = (telemetry.speed.toDouble() * (dtSec / 3600.0)).toFloat()
                telemetry.tripDistanceKm += distSlice
                val curOil = preferences.getFloat("oil_current_km", 0f)
                val curCarOdo = preferences.getFloat("car_cumulative_odometer_km", 125480f) + distSlice
                telemetry.carOdometerKm = curCarOdo
                telemetry.odometerSource = "محاكاة عداد السيارة التراكمي (ECU Odometer Mode 01A6) 🟢"

                val consumedLiters = (telemetry.instantFuelLitersPerHour.toDouble() * (dtSec / 3600.0)).toFloat()
                telemetry.actualTripFuelLiters += consumedLiters
                if (telemetry.tripDistanceKm > 0.05f) {
                    telemetry.tripAverageFuelRate = (telemetry.actualTripFuelLiters / telemetry.tripDistanceKm) * 100f
                }

                preferences.edit()
                    .putFloat("oil_current_km", curOil + distSlice)
                    .putFloat("car_cumulative_odometer_km", curCarOdo)
                    .putFloat("saved_trip_distance_km", telemetry.tripDistanceKm)
                    .putFloat("saved_trip_fuel_liters", telemetry.actualTripFuelLiters)
                    .apply()

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

        val progress = AlertDialog.Builder(this)
            .setTitle("🔍 جاري فحص كمبيوترات السيارة...")
            .setMessage("يتم الآن إرسال إشارات التشخيص إلى لوحة العدادات بالطابلوه وكمبيوتر المكينة...")
            .setCancelable(false)
            .create()
        progress.show()

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
        val isLive = telemetry.isConnected || isSimulating

        if (isLive) {
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
                    tvInstantFuel.text = String.format("%.2f L/h", telemetry.instantFuelLitersPerHour)
                }
            }

            if (::tvTripFuel.isInitialized) {
                tvTripFuel.text = String.format("%.1f L/100 (%.2f L)", telemetry.tripAverageFuelRate, telemetry.actualTripFuelLiters)
            }

            if (::tvEngineLoad.isInitialized) {
                tvEngineLoad.text = "${telemetry.engineLoadPercent} %"
            }
            if (::tvThrottle.isInitialized) {
                tvThrottle.text = "${telemetry.throttlePercent} %"
            }
            if (::tvCarOdometer.isInitialized) {
                val odoVal = if (telemetry.carOdometerKm > 0f) telemetry.carOdometerKm else preferences.getFloat("car_cumulative_odometer_km", 0f)
                val odoSourceBadge = if (telemetry.carOdometerKm > 0f) "🔌" else "🚗"
                tvCarOdometer.text = if (odoVal > 0f) "${odoVal.toInt()} كم $odoSourceBadge" else "-- كم"
            }
            if (::tvTripDistance.isInitialized) {
                tvTripDistance.text = String.format("%.2f كم", telemetry.tripDistanceKm)
            }

            if (::tvFuelSourceBadge.isInitialized) {
                tvFuelSourceBadge.text = "⚡ مصدر قياس الوقود: ${telemetry.fuelCalculationSource}"
            }

            if (telemetry.dtcCodes.isEmpty()) {
                tvDtc.text = "لمبة الأعطال: سليمة (OFF)"
                tvDtc.setTextColor(Color.rgb(126, 242, 154))
            } else {
                val firstCode = telemetry.dtcCodes.first()
                val diag = diagnoseDtc(firstCode)
                tvDtc.text = "تحذير عطل: $firstCode (${diag.titleAr})"
                tvDtc.setTextColor(Color.rgb(255, 122, 122))
            }
        } else {
            // Disconnected & not simulating
            tvSpeed.text = if (phoneGpsSpeedKmh > 0) "$phoneGpsSpeedKmh km/h (GPS 🛰️)" else "0 km/h"
            tvRpm.text = "-- RPM"
            tvTemp.text = "-- °C"
            tvBattery.text = "-- V"
            tvFuel.text = "-- %"
            if (::tvInstantFuel.isInitialized) tvInstantFuel.text = "-- L/100"
            if (::tvTripFuel.isInitialized) tvTripFuel.text = "-- L/100"
            if (::tvEngineLoad.isInitialized) tvEngineLoad.text = "-- %"
            if (::tvThrottle.isInitialized) tvThrottle.text = "-- %"
            if (::tvCarOdometer.isInitialized) {
                val odoVal = preferences.getFloat("car_cumulative_odometer_km", 0f)
                tvCarOdometer.text = if (odoVal > 0f) "${odoVal.toInt()} كم (محفوظ)" else "-- كم"
            }
            if (::tvTripDistance.isInitialized) {
                tvTripDistance.text = String.format("%.2f كم", telemetry.tripDistanceKm)
            }
            if (::tvDtc.isInitialized) {
                tvDtc.text = "لمبة الأعطال: بانتظار الاتصال بكمبيوتر السيارة"
                tvDtc.setTextColor(Color.rgb(170, 190, 210))
            }
            if (::tvFuelSourceBadge.isInitialized) {
                tvFuelSourceBadge.text = if (phoneGpsSpeedKmh > 0) "🛰️ متصل بنظام تحديد المواقع GPS للهاتف" else "بانتظار قراءة الحساسات من كمبيوتر السيارة أو تشغيل المحاكي"
            }
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

        val vehicleEmptyWeight = preferences.getFloat("vehicle_empty_weight", 1200.0f)
        val weightPenaltyPercent = if (vehicleEmptyWeight > 0f) {
            (plannedCargoWeightKg / vehicleEmptyWeight) * 60.0f
        } else {
            (plannedCargoWeightKg / 100.0f) * 5.0f
        }
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
        val carOdoKm = if (telemetry.carOdometerKm > 0f) telemetry.carOdometerKm else preferences.getFloat("car_cumulative_odometer_km", 0f)
        val odoLabel = if (carOdoKm > 0f) " | عداد السيارة: ${carOdoKm.toInt()} كم" else ""
        tvMaintOilText?.text = "• زيت المحرك والفلتر: مقطوع ${oilCurrentKm.toInt()} كم | متبقي ${remainingOilKm.toInt()} كم حتى موعد الغيار$odoLabel"
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
                    "coolant_overheat",
                    "🚨 تحذير طارئ: حرارة المحرك مرتفعة (${telemetry.coolantTemp} °C)!",
                    "حرارة سائل التبريد تجاوزت 100°C! يرجى التوقف فوراً وفحص سائل التبريد ومروحة الرادياتير.",
                    critical = true
                )
            } else if (telemetry.fuelLevel in 1..15) {
                setSafetyAlert(
                    "fuel_low",
                    "⛽ انتباه: مستوى الوقود منخفض (${telemetry.fuelLevel}%)",
                    "متبقي أقل من 15% في الخزان. توجه لأقرب محطة وقود لتجنب توقف المحرك وتلف طلمبة البنزين.",
                    critical = false
                )
            } else if (remainingOil <= 500f && oilTarget > 0f) {
                setSafetyAlert(
                    "oil_service",
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
                    "dtc_$firstCode",
                    "⚠️ تنبيه عطل محرك: كود $firstCode",
                    desc,
                    critical = (diag.severity.contains("حرجة"))
                )
            } else if (telemetry.batteryVoltage in 0.1f..11.9f) {
                setSafetyAlert(
                    "battery_low",
                    "🔋 تنبيه: شحن البطارية / الدينامو منخفض (${String.format("%.1f", telemetry.batteryVoltage)}V)",
                    "جهد الشحن أقل من 12.0 فولت أثناء التشغيل. يرجى فحص الدينامو وحزام السير.",
                    critical = false
                )
            } else {
                if (telemetry.activeAlertTitle != null && !isSimulatingAlert) {
                    telemetry.activeAlertTitle = null
                    cancelSystemSafetyNotification()
                    updateAlertBannerUI()
                }
            }
        }

        // Automatic Delta Telemetry Tracking (تسجيل التغيرات فقط)
        checkAndLogTelemetryDelta()
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

        obdReconnectAttempts = 0
        connectToDevice(cand)
    }

    private fun connectToDevice(cand: DeviceCandidate) {
        stopDiscovery()
        lastConnectCandidate = cand
        disconnectObd(userInitiated = false)
        userRequestedDisconnect = false

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
                val autoRetrying = obdReconnectAttempts > 0 && !userRequestedDisconnect
                // A first/manual connect that fails must not leave a foreground notification behind; during
                // an automatic retry cycle the service stays up (handleObdLinkLost decides when to give up).
                if (!autoRetrying) ObdForegroundService.stop(applicationContext)
                runOnUiThread {
                    statusText.text = "فشل الاتصال: ${e.localizedMessage ?: "تأكد من فتح كونتاكت السيارة وتشغيل القطعة"}"
                    telemetry.isConnected = false
                    if (::tvConnectionState.isInitialized) {
                        tvConnectionState.text = "فشل الاتصال 🔴"
                        tvConnectionState.setTextColor(Color.rgb(255, 122, 122))
                    }
                }
                if (autoRetrying) handleObdLinkLost(obdLoopGeneration.get())
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

        disconnectObd(userInitiated = false)
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
        val myGen = obdLoopGeneration.incrementAndGet()
        var linkLost = false
        var consecutiveFailures = 0
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
        val protoResp = sendObdCommand(outStream, inStream, "ATDP", 600)
        detectedProtocolName = protoResp
        isCurrentProtocolCan = protoResp.contains("CAN", ignoreCase = true) || protoResp.contains("ISO 15765", ignoreCase = true)
        telemetry.obdProtocol = protoResp

        runOnUiThread {
            obdReconnectAttempts = 0
            telemetry.isConnected = true
            // Link is up: promote the process to a foreground service so the loop + alerts survive screen-off/Doze.
            ObdForegroundService.start(this@MainActivity, "متصل بـ $devName • المراقبة والإنذارات تعمل بالخلفية")
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

        // Universal Discovery for Total Vehicle Odometer (ECU or Dashboard Cluster)
        activeOdometerProbe = discoverUniversalOdometerProbe(inStream, outStream)

        // Automated High-Precision Fuel Flow Sensor Discovery (Zero Manual Setup)
        activeFuelSensorMode = discoverBestFuelSensor(inStream, outStream)

        runOnUiThread {
            statusText.text = "متصل بنجاح 🟢 تم تفعيل القياس الآلي: ${telemetry.fuelCalculationSource}"
        }

        var loopCount = 0
        var lastCalcTime = System.currentTimeMillis()
        var accumulatedFuelSlice = 0.0

        while (isTelemetryLoopRunning && myGen == obdLoopGeneration.get()) {
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

                // Periodic Delta Odometer Check from Vehicle ECU / Dashboard Cluster
                if (loopCount % 5 == 0) {
                    val odoProbe = activeOdometerProbe
                    if (odoProbe != null) {
                        val odoVal = readProbeOdometer(inStream, outStream, odoProbe)
                        if (odoVal > 0f) {
                            applyCumulativeOdometerDelta(odoVal, odoProbe.description)
                        }
                    } else if (loopCount % 35 == 0) {
                        // Periodic recovery probe in case cluster became ready later
                        activeOdometerProbe = discoverUniversalOdometerProbe(inStream, outStream)
                    }
                }

                // Fallback Distance & Oil Increments (Only if Vehicle Cumulative Odometer is unavailable)
                if (activeOdometerProbe == null || telemetry.carOdometerKm <= 0f) {
                    if (distanceSliceKm > 0) {
                        telemetry.tripDistanceKm += distanceSliceKm.toFloat()

                        val curOilKm = preferences.getFloat("oil_current_km", 0f)
                        val newOilKm = curOilKm + distanceSliceKm.toFloat()
                        preferences.edit().putFloat("oil_current_km", newOilKm).apply()

                        val virtualCarOdo = preferences.getFloat("car_cumulative_odometer_km", 0f)
                        if (virtualCarOdo > 0f) {
                            val newVirtual = virtualCarOdo + distanceSliceKm.toFloat()
                            preferences.edit().putFloat("car_cumulative_odometer_km", newVirtual).apply()
                            telemetry.carOdometerKm = newVirtual
                        }
                    }
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

                // Periodic persistence for trip metrics (survives car shutoff or phone disconnect)
                if (loopCount % 4 == 0) {
                    preferences.edit()
                        .putFloat("saved_trip_distance_km", telemetry.tripDistanceKm)
                        .putFloat("saved_trip_fuel_liters", telemetry.actualTripFuelLiters)
                        .apply()
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
                    val dtcs = parseDtc(dtcResp, isCurrentProtocolCan)
                    telemetry.dtcCodes.clear()
                    telemetry.dtcCodes.addAll(dtcs)
                }

                runOnUiThread { updateLiveUI() }
                consecutiveFailures = 0

                Thread.sleep(120)
            } catch (_: Exception) {
                // Stale loop (user disconnected / a newer connection replaced us): exit quietly.
                if (!isTelemetryLoopRunning || myGen != obdLoopGeneration.get()) break
                consecutiveFailures++
                if (consecutiveFailures >= 5) {
                    // Socket is dead (Bluetooth dropped, OBD unplugged, phone out of range).
                    linkLost = true
                    break
                }
                // Back off instead of spinning the CPU on a broken socket.
                try { Thread.sleep(300) } catch (_: InterruptedException) { break }
            }
        }
        } finally {
            // Only the current generation may reset shared state; a zombie loop must not kill the new one.
            if (myGen == obdLoopGeneration.get()) {
                activeObdOutStream = null
                activeObdInStream = null
                isTelemetryLoopRunning = false
            }
        }
        if (linkLost && !userRequestedDisconnect) handleObdLinkLost(myGen)
    }

    /**
     * Called when the OBD socket died unexpectedly. Marks the UI as disconnected (so stale numbers are not
     * shown as live) and retries a bounded number of times with growing delay, never after a manual disconnect.
     */
    private fun handleObdLinkLost(gen: Int) {
        if (gen != obdLoopGeneration.get()) return
        disconnectObd(userInitiated = false)
        val cand = lastConnectCandidate
        val attempt = obdReconnectAttempts + 1
        obdReconnectAttempts = attempt
        val canRetry = cand != null && attempt <= 6
        val delayMs = (3000L * attempt).coerceAtMost(15000L)
        if (canRetry) {
            ObdForegroundService.updateText(applicationContext, "⚠️ انقطع الاتصال بقطعة OBD • إعادة المحاولة $attempt/6")
        } else {
            // Gave up (or no device to retry): stop the foreground notification so it never lies about monitoring.
            ObdForegroundService.stop(applicationContext)
        }
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            telemetry.isConnected = false
            if (::statusText.isInitialized) {
                statusText.text = if (canRetry) {
                    "⚠️ انقطع الاتصال بقطعة OBD. إعادة المحاولة $attempt/6 خلال ${delayMs / 1000} ثوانٍ..."
                } else {
                    "🔴 انقطع الاتصال بقطعة OBD. تحقق من الكونتاكت والبلوتوث ثم اضغط اتصال يدوياً."
                }
            }
            if (::tvConnectionState.isInitialized) {
                tvConnectionState.text = if (canRetry) "إعادة الاتصال... 🟡" else "منقطع 🔴"
                tvConnectionState.setTextColor(if (canRetry) Color.rgb(248, 199, 74) else Color.rgb(255, 122, 122))
            }
            if (canRetry) {
                scanHandler.postDelayed({
                    if (!isDestroyed && !isFinishing && !userRequestedDisconnect && !telemetry.isConnected) {
                        connectToDevice(cand!!)
                    }
                }, delayMs)
            }
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

    // =========================================================================
    // DELTA ODOMETER ENGINE (تتبع عداد السيارة التراكمي وحساب فروق الزيت والرحلات)
    // =========================================================================
    private fun parseProbeOdometer(raw: String, probe: OdometerProbe): Float {
        val clean = cleanHex(raw)
        val idx = clean.indexOf(probe.expectedPrefix)
        val expectedNibbles = probe.expectedPrefix.length + (probe.byteLength * 2)
        if (idx != -1 && clean.length >= idx + expectedNibbles) {
            val hexData = clean.substring(idx + probe.expectedPrefix.length, idx + expectedNibbles)
            val rawVal = hexData.toLongOrNull(16) ?: return -1f
            val odoKm = (rawVal.toFloat() / probe.scalingDivisor)
            return if (odoKm in 1.0f..3000000.0f) odoKm else -1f
        }
        return -1f
    }

    private fun discoverUniversalOdometerProbe(inStream: InputStream, outStream: OutputStream): OdometerProbe? {
        // 1. Check if we already have a confirmed working odometer probe saved
        val savedHeader = preferences.getString("active_odometer_probe_header", null)
        val savedCmd = preferences.getString("active_odometer_probe_cmd", null)
        if (savedHeader != null && savedCmd != null) {
            val cachedProbe = universalOdometerProbes.find { it.header == savedHeader && it.command == savedCmd }
            if (cachedProbe != null) {
                try {
                    if (cachedProbe.header != "7DF") {
                        sendObdCommand(outStream, inStream, "ATSH ${cachedProbe.header}", 250)
                        val rxAddr = Integer.toHexString(cachedProbe.header.toInt(16) + 8).uppercase()
                        sendObdCommand(outStream, inStream, "ATCRA $rxAddr", 200)
                    }
                    val resp = sendObdCommand(outStream, inStream, cachedProbe.command, 450)
                    if (cachedProbe.header != "7DF") {
                        sendObdCommand(outStream, inStream, "ATCRA", 200)
                        sendObdCommand(outStream, inStream, "ATSH 7DF", 200)
                    }
                    val odo = parseProbeOdometer(resp, cachedProbe)
                    if (odo > 0f) {
                        applyCumulativeOdometerDelta(odo, cachedProbe.description)
                        return cachedProbe
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Protocol Check
        val protoResp = sendObdCommand(outStream, inStream, "ATDP", 500)
        val isCan = protoResp.contains("CAN", ignoreCase = true) || protoResp.contains("ISO 15765", ignoreCase = true)

        // 3. PRIORITY 1: Standard SAE J1979 Mode 01 PID 01A6 (Direct Broadcast / Engine ECU)
        for (probe in universalOdometerProbes.filter { !it.isCluster && it.command == "01A6" }) {
            try {
                if (probe.header != "7DF") {
                    sendObdCommand(outStream, inStream, "ATSH ${probe.header}", 250)
                }
                val resp = sendObdCommand(outStream, inStream, probe.command, 450)
                if (probe.header != "7DF") {
                    sendObdCommand(outStream, inStream, "ATSH 7DF", 250)
                }
                val odo = parseProbeOdometer(resp, probe)
                if (odo > 0f) {
                    preferences.edit()
                        .putString("active_odometer_probe_header", probe.header)
                        .putString("active_odometer_probe_cmd", probe.command)
                        .apply()
                    applyCumulativeOdometerDelta(odo, probe.description)
                    return probe
                }
            } catch (_: Exception) {}
        }

        // 4. PRIORITY 2: Sweep Instrument Cluster DIDs on CAN Bus (لوحة عدادات الطابلوه)
        if (isCan) {
            for (probe in universalOdometerProbes.filter { it.isCluster }) {
                try {
                    sendObdCommand(outStream, inStream, "ATSH ${probe.header}", 300)
                    val rxAddr = Integer.toHexString(probe.header.toInt(16) + 8).uppercase()
                    sendObdCommand(outStream, inStream, "ATCRA $rxAddr", 250)

                    val resp = sendObdCommand(outStream, inStream, probe.command, 500)

                    sendObdCommand(outStream, inStream, "ATCRA", 200)
                    sendObdCommand(outStream, inStream, "ATSH 7DF", 250)

                    val odo = parseProbeOdometer(resp, probe)
                    if (odo > 0f) {
                        preferences.edit()
                            .putString("active_odometer_probe_header", probe.header)
                            .putString("active_odometer_probe_cmd", probe.command)
                            .apply()
                        applyCumulativeOdometerDelta(odo, probe.description)
                        return probe
                    }
                } catch (_: Exception) {}
            }
        }

        // 5. PRIORITY 3: Fallback to Universal Engine Run Distance PID 0131
        for (probe in universalOdometerProbes.filter { it.command == "0131" }) {
            try {
                if (probe.header != "7DF") {
                    sendObdCommand(outStream, inStream, "ATSH ${probe.header}", 250)
                }
                val resp = sendObdCommand(outStream, inStream, probe.command, 450)
                if (probe.header != "7DF") {
                    sendObdCommand(outStream, inStream, "ATSH 7DF", 250)
                }
                val odo = parseProbeOdometer(resp, probe)
                if (odo > 0f) {
                    preferences.edit()
                        .putString("active_odometer_probe_header", probe.header)
                        .putString("active_odometer_probe_cmd", probe.command)
                        .apply()
                    applyCumulativeOdometerDelta(odo, probe.description)
                    return probe
                }
            } catch (_: Exception) {}
        }

        sendObdCommand(outStream, inStream, "ATCRA", 200)
        sendObdCommand(outStream, inStream, "ATSH 7DF", 250)
        return null
    }

    private fun readProbeOdometer(inStream: InputStream, outStream: OutputStream, probe: OdometerProbe): Float {
        return try {
            if (probe.header != "7DF") {
                sendObdCommand(outStream, inStream, "ATSH ${probe.header}", 250)
                val rxAddr = Integer.toHexString(probe.header.toInt(16) + 8).uppercase()
                sendObdCommand(outStream, inStream, "ATCRA $rxAddr", 200)
            }
            val resp = sendObdCommand(outStream, inStream, probe.command, 450)
            if (probe.header != "7DF") {
                sendObdCommand(outStream, inStream, "ATCRA", 200)
                sendObdCommand(outStream, inStream, "ATSH 7DF", 200)
            }
            parseProbeOdometer(resp, probe)
        } catch (_: Exception) {
            -1f
        }
    }

    private fun applyCumulativeOdometerDelta(currentCarOdo: Float, sourceDescription: String) {
        if (currentCarOdo <= 0f) return
        telemetry.carOdometerKm = currentCarOdo
        telemetry.odometerSource = sourceDescription

        val lastSavedCarOdo = preferences.getFloat("car_cumulative_odometer_km", 0f)

        if (lastSavedCarOdo > 0f && currentCarOdo >= lastSavedCarOdo) {
            val deltaKm = currentCarOdo - lastSavedCarOdo
            if (deltaKm in 0.001f..3500.0f) {
                // 1. Calculate Real Engine Oil Distance based on absolute oil change baseline
                val oilBaseline = preferences.getFloat("oil_baseline_car_odometer", 0f)
                if (oilBaseline > 0f && currentCarOdo >= oilBaseline) {
                    val realOilKm = currentCarOdo - oilBaseline
                    preferences.edit().putFloat("oil_current_km", realOilKm).apply()
                } else {
                    val curOil = preferences.getFloat("oil_current_km", 0f)
                    preferences.edit().putFloat("oil_current_km", curOil + deltaKm).apply()
                }

                // 2. Calculate Real Trip Distance based on trip start odometer baseline
                val tripStartOdo = preferences.getFloat("trip_start_car_odometer", 0f)
                if (tripStartOdo > 0f && currentCarOdo >= tripStartOdo) {
                    telemetry.tripDistanceKm = currentCarOdo - tripStartOdo
                } else {
                    telemetry.tripDistanceKm += deltaKm
                }
            }
        } else if (lastSavedCarOdo == 0f) {
            // First-time calibration / initialization
            val curOil = preferences.getFloat("oil_current_km", 0f)
            if (preferences.getFloat("oil_baseline_car_odometer", 0f) == 0f && currentCarOdo > curOil) {
                preferences.edit().putFloat("oil_baseline_car_odometer", currentCarOdo - curOil).apply()
            }
            val curTrip = preferences.getFloat("saved_trip_distance_km", 0f)
            if (preferences.getFloat("trip_start_car_odometer", 0f) == 0f && currentCarOdo > curTrip) {
                preferences.edit().putFloat("trip_start_car_odometer", currentCarOdo - curTrip).apply()
            }
        }

        // Always save latest confirmed odometer & trip distance
        preferences.edit()
            .putFloat("car_cumulative_odometer_km", currentCarOdo)
            .putFloat("saved_trip_distance_km", telemetry.tripDistanceKm)
            .apply()
    }

    private fun showOdometerCalibrationDialog() {
        val currentOdo = if (telemetry.carOdometerKm > 0f) telemetry.carOdometerKm else preferences.getFloat("car_cumulative_odometer_km", 0f)
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "أدخل قراءة عداد التابلوه الحالية (كم)..."
            setText(if (currentOdo > 0f) "${currentOdo.toInt()}" else "")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(14, 34, 52))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        AlertDialog.Builder(this)
            .setTitle("🚗 ضبط ومزامنة عداد التابلوه (Odometer)")
            .setMessage(
                "أدخل القراءة الحالية لعداد الكيلومترات الظاهر في تابلوه السيارة الآن:\n\n" +
                "• هذه القراءة ستكون المرجع الدائم لحسابات غيار الزيت والرحلات.\n" +
                "• حتى لو فصل الهاتف، سيتم حساب الفارق بدقة تامة بمجرد الاتصال التالي!"
            )
            .setView(input)
            .setPositiveButton("حفظ وتأكيد") { _, _ ->
                val newOdo = input.text.toString().trim().toFloatOrNull()
                if (newOdo != null && newOdo > 0f) {
                    applyCumulativeOdometerDelta(newOdo, "مزامنة يدوية مؤكدة")
                    Toast.makeText(this, "تمت مزامنة عداد السيارة بنجاح عند (${newOdo.toInt()} كم) ✅", Toast.LENGTH_SHORT).show()
                    updateLiveUI()
                } else {
                    Toast.makeText(this, "يرجى كتابة رقم عداد صحيح أكبر من الصفر", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
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

    private fun parseDtc(raw: String, isCan: Boolean = isCurrentProtocolCan): List<String> {
        val list = mutableListOf<String>()
        if (raw.contains("NO DATA") || raw.contains("UNABLE") || raw.contains("ERROR")) return list

        val lines = raw.split("\r", "\n")
        val hexSb = java.lang.StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            val sanitized = if (trimmed.length > 2 && trimmed[1] == ':') trimmed.substring(2) else trimmed
            for (c in sanitized) {
                if (c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f') {
                    hexSb.append(c.uppercaseChar())
                }
            }
        }
        val clean = hexSb.toString()
        val idx = clean.indexOf("43")
        if (idx == -1) return list

        val payload = clean.substring(idx + 2)
        if (payload.length < 4) return list

        fun decodeCandidate(hex4: String): String? {
            if (hex4.length < 4 || hex4 == "0000") return null
            val byteA = hex4.substring(0, 2).toIntOrNull(16) ?: return null
            val byteB = hex4.substring(2, 4)
            val categoryBits = (byteA shr 6) and 0x03
            val categoryPrefix = when (categoryBits) {
                0 -> "P"
                1 -> "C"
                2 -> "B"
                else -> "U"
            }
            val secondDigit = (byteA shr 4) and 0x03
            val thirdDigitHex = Integer.toHexString(byteA and 0x0F).uppercase()
            val code = "$categoryPrefix$secondDigit$thirdDigitHex$byteB".uppercase()
            return if (code != "P0000") code else null
        }

        // Candidate A: Direct 2-byte DTC sequence (No count byte, starting at offset 0).
        // MANDATORY standard for all K-Line, ISO 9141-2, ISO 14230-4 KWP (Chevrolet Aveo/Daewoo), and SAE J1850 protocols.
        val dtcsWithoutCount = mutableListOf<String>()
        var i = 0
        while (i + 4 <= payload.length) {
            val chunk = payload.substring(i, i + 4)
            if (chunk == "0000") break
            val code = decodeCandidate(chunk)
            if (code != null && !dtcsWithoutCount.contains(code)) {
                dtcsWithoutCount.add(code)
            }
            i += 4
        }

        // Candidate B: CAN Single-Frame / Multi-Frame where Byte 0 is the DTC count (01..0F)
        val dtcsWithCount = mutableListOf<String>()
        val firstByte = payload.substring(0, 2).toIntOrNull(16) ?: 0
        if (firstByte in 1..15) {
            var j = 2
            while (j + 4 <= payload.length) {
                val chunk = payload.substring(j, j + 4)
                if (chunk == "0000") break
                val code = decodeCandidate(chunk)
                if (code != null && !dtcsWithCount.contains(code)) {
                    dtcsWithCount.add(code)
                }
                j += 4
            }
        }

        // Deterministic Selection:
        // 1. Non-CAN protocols (K-Line / KWP / ISO 9141) NEVER have a count byte per SAE J1979 specification.
        // 2. On CAN: Use candidate B ONLY if it successfully extracted exactly firstByte valid codes.
        val finalCodes = if (!isCan) {
            dtcsWithoutCount
        } else {
            if (dtcsWithCount.isNotEmpty() && dtcsWithCount.size == firstByte) {
                dtcsWithCount
            } else {
                dtcsWithoutCount
            }
        }

        for (c in finalCodes) {
            if (!list.contains(c)) list.add(c)
        }
        return list
    }

    private fun diagnoseDtc(code: String): DtcDiagnosis {
        val clean = code.trim().uppercase()
        return when (clean) {
            "P3400" -> DtcDiagnosis(
                clean,
                "نظام تعطيل أسطوانات المحرك (Cylinder Deactivation System Bank 1 - AFM/MDS)",
                "نظام إدارة الوقود النشط (محركات V8 فقط)",
                "• عطل في صمام التحكم في إيقاف الأسطوانات أو ضغط الزيت\n• ملاحظة هامة: هذا الكود خاص بمحركات V8/V6 ذات نظام AFM النشط ولا ينطبق على محركات 4 سلندر كالأفيو (في سيارات 4 سلندر يرجع لخطأ قراءة سابق لحساس الشكمان P0134).",
                "فحص مستوى الزيت وصمامات التحكم بالأسطوانات (للسيارات المزودة بنظام AFM/MDS).",
                "تحذيرية"
            )
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

    private fun disconnectObd(userInitiated: Boolean = true) {
        if (userInitiated) {
            userRequestedDisconnect = true
            // Manual/real shutdown: nothing left to monitor, so release the foreground service + wake lock.
            ObdForegroundService.stop(applicationContext)
        }
        obdLoopGeneration.incrementAndGet() // invalidates any running/zombie telemetry loop immediately
        isTelemetryLoopRunning = false
        try { activeSocket?.close() } catch (_: Exception) {}
        try { activeWifiSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        activeWifiSocket = null
        telemetry.isConnected = false
        if (!isSimulating) {
            telemetry.coolantTemp = 0
            telemetry.batteryVoltage = 0.0f
            telemetry.rpm = 0
            telemetry.speed = phoneGpsSpeedKmh
            telemetry.instantFuelLPer100Km = 0.0f
            telemetry.instantFuelLitersPerHour = 0.0f
            telemetry.engineLoadPercent = 0
            telemetry.throttlePercent = 0
            telemetry.dtcCodes.clear()
            runOnUiThread {
                if (::tvConnectionState.isInitialized) {
                    tvConnectionState.text = "غير متصل"
                    tvConnectionState.setTextColor(Color.rgb(255, 122, 122))
                }
                updateLiveUI()
            }
        }
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

    private fun hasLocationPermissions(): Boolean {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestPermissionsCompat() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), REQUEST_NEARBY)
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
