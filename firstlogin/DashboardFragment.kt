package com.example.firstlogin

import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.firstlogin.databinding.FragmentDashboardBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class DashboardFragment : Fragment() {

    // ===== VIEW REFERENCES =====
    private var binding: FragmentDashboardBinding? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null

    // ===== FIREBASE PATHS =====
    private val sensorsRef by lazy {
        FirebaseDatabase.getInstance().getReference("sensors")
    }
    private val logsRef by lazy {
        FirebaseDatabase.getInstance().getReference("trend")
    }
    private val statusRef by lazy {
        FirebaseDatabase.getInstance().getReference("status")
    }
    private val lightRef by lazy {
        FirebaseDatabase.getInstance().getReference("controls/light")
    }
    private var sensorsListener: ValueEventListener? = null
    private var statusListener: ValueEventListener? = null
    private var lightListener: ValueEventListener? = null

    // ===== TREND HISTORY BUFFERS =====
    private val tempHistory = ArrayDeque<Float>()
    private val phHistory = ArrayDeque<Float>()
    private val humidityHistory = ArrayDeque<Float>()
    private val waterHistory = ArrayDeque<Float>()
    private val turbidityHistory = ArrayDeque<Float>()
    private val maxHistoryPoints = 300
    private val logTag = "DashboardFragment"
    private var lastDataAtMillis: Long? = null
    private var lastSeenSeconds: Long? = null
    private var isOnline: Boolean? = null

    // ===== TREND TICKER (UI SMOOTHING) =====
    private val trendHandler = Handler(Looper.getMainLooper())
    private var trendTicker: Runnable? = null
    private val trendUiIntervalMs = 1000L
    private var latestTemp: Float? = null
    private var latestHumidity: Float? = null
    private var latestWater: Float? = null
    private var latestPh: Float? = null
    private var latestTurbidity: Float? = null
    private var trendTemp: Float? = null
    private var trendHumidity: Float? = null
    private var trendWater: Float? = null
    private var trendPh: Float? = null
    private var trendTurbidity: Float? = null
    private var tvTrendTurbidityValueDynamic: TextView? = null
    private var sparkTurbidityDynamic: SparklineView? = null

    // ===== LIGHT CONTROL STATE =====
    private var lightOn = false
    private var lightSyncing = false
    private val appLightControlEnabled = true
    private val lightControlHandler = Handler(Looper.getMainLooper())
    private val lightControlCooldownMs = 2000L

    // ===== FEEDER CONTROL STATE =====
    private var feederState = "Ready"
    private var feederBusy = false
    private var feedSyncing = false
    private var feedRequestPending = false
    private var lastFeedAtSeconds: Long? = null
    private val feedControlHandler = Handler(Looper.getMainLooper())
    private val feedControlCooldownMs = 700L
    private val feedPendingTimeoutMs = 4500L
    private var feedCooldownUntilMs = 0L
    private val deviceOfflineGraceSeconds = 90L

    // Inflate fragment root and wrap it in SwipeRefreshLayout.
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val fragmentBinding = FragmentDashboardBinding.inflate(inflater, container, false)
        binding = fragmentBinding
        val refreshLayout = SwipeRefreshLayout(requireContext()).apply {
            setColorSchemeResources(
                R.color.stat_accent,
                R.color.stat_accent_humidity,
                R.color.stat_accent_water
            )
            addView(
                fragmentBinding.root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnRefreshListener {
                refreshDashboard()
            }
        }
        swipeRefreshLayout = refreshLayout
        return refreshLayout
    }

    // Configure chart colors, navigation, and control button behavior.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val fragmentBinding = binding ?: return

        fragmentBinding.sparkTemp.setLineColor(
            ContextCompat.getColor(requireContext(), R.color.stat_accent)
        )
        fragmentBinding.sparkPh.setLineColor(
            ContextCompat.getColor(requireContext(), R.color.stat_accent_alt)
        )
        fragmentBinding.sparkHumidity.setLineColor(
            ContextCompat.getColor(requireContext(), R.color.stat_accent_humidity)
        )
        fragmentBinding.sparkWater.setLineColor(
            ContextCompat.getColor(requireContext(), R.color.stat_accent_water)
        )
        ensureTurbidityTrendRow(fragmentBinding)

        fragmentBinding.arrow.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, StatsFragment())
                .addToBackStack(null)
                .commit()
        }

        if (appLightControlEnabled) {
            fragmentBinding.btnLightToggle.setOnClickListener {
                if (!isInternetAvailable()) {
                    Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
                    updateStatus(connected = false, message = "App offline: No internet")
                    return@setOnClickListener
                }
                if (lightSyncing) return@setOnClickListener

                val requestedState = !lightOn
                lightOn = requestedState
                lightSyncing = true
                updateLightUi(fragmentBinding)
                lightRef.setValue(requestedState).addOnFailureListener { error ->
                    Log.e(logTag, "Light control failed: ${error.message}")
                }.addOnCompleteListener {
                    lightControlHandler.postDelayed({
                        lightSyncing = false
                        binding?.let { updateLightUi(it) }
                    }, lightControlCooldownMs)
                }
            }
        } else {
            fragmentBinding.btnLightToggle.setOnClickListener(null)
            updateLightUi(fragmentBinding)
        }

        fragmentBinding.btnFeed.setOnClickListener {
            if (!isInternetAvailable()) {
                Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
                updateStatus(connected = false, message = "App offline: No internet")
                return@setOnClickListener
            }
            val now = System.currentTimeMillis()
            if (feedSyncing || feederBusy || now < feedCooldownUntilMs) return@setOnClickListener

            val requestId = ((System.currentTimeMillis() / 10L) % 2_000_000_000L).toInt()
            Log.d(logTag, "Feed button pressed -> requestId=$requestId")

            feedSyncing = true
            feedRequestPending = true
            feedCooldownUntilMs = now + feedControlCooldownMs
            feederState = "Requesting"
            updateFeederUi(fragmentBinding)

            val updates = hashMapOf<String, Any>(
                "/controls/FeedRequestId" to requestId
            )
            FirebaseDatabase.getInstance().reference.updateChildren(updates)
                .addOnFailureListener { error ->
                    Log.e(logTag, "Feed request failed: ${error.message}")
                    feedSyncing = false
                    feedRequestPending = false
                    feederState = "Request failed"
                    binding?.let { updateFeederUi(it) }
                }
                .addOnCompleteListener {
                    feedControlHandler.postDelayed({
                        feedSyncing = false
                        if (feedRequestPending && !feederBusy) {
                            feederState = "Waiting for ESP32"
                        }
                        binding?.let { updateFeederUi(it) }
                    }, 250L)

                    feedControlHandler.postDelayed({
                        if (feedRequestPending && !feederBusy) {
                            feedRequestPending = false
                            feederState = "Ready"
                        }
                        binding?.let { updateFeederUi(it) }
                    }, feedPendingTimeoutMs)
                }
        }

        updateFeederUi(fragmentBinding)
    }

    // Attach live listeners and start trend animation when fragment is visible.
    override fun onStart() {
        super.onStart()
        if (!isInternetAvailable()) {
            updateStatus(connected = false, message = "App offline: No internet")
        }
        attachListener()
        if (appLightControlEnabled) {
            attachLightListener()
        } else {
            binding?.let { updateLightUi(it) }
        }
        loadTrendsFromTrend()
        startTrendTicker()
    }

    // Detach listeners/tickers when leaving the screen.
    override fun onStop() {
        super.onStop()
        detachListener()
        if (appLightControlEnabled) {
            detachLightListener()
        }
        stopTrendTicker()
    }

    // Release handlers/references to avoid leaks.
    override fun onDestroyView() {
        super.onDestroyView()
        lightControlHandler.removeCallbacksAndMessages(null)
        feedControlHandler.removeCallbacksAndMessages(null)
        swipeRefreshLayout = null
        binding = null
    }

    // Refresh all dashboard listeners and trend data from Firebase.
    private fun refreshDashboard() {
        if (!isInternetAvailable()) {
            swipeRefreshLayout?.isRefreshing = false
            updateStatus(connected = false, message = "App offline: No internet")
            Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
            return
        }
        swipeRefreshLayout?.isRefreshing = true
        detachListener()
        if (appLightControlEnabled) {
            detachLightListener()
        }
        attachListener()
        if (appLightControlEnabled) {
            attachLightListener()
        } else {
            binding?.let { updateLightUi(it) }
        }
        loadTrendsFromTrend()
        trendHandler.postDelayed({
            swipeRefreshLayout?.isRefreshing = false
        }, 1200L)
    }

    private fun attachListener() {
        if (sensorsListener != null) return

        if (statusListener == null) {
            val connListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val fragmentBinding = binding ?: return
                    val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java)
                        ?: snapshot.child("lastSeen").getValue(Double::class.java)?.toLong()
                    val online = snapshot.child("online").getValue(Boolean::class.java)
                    val feedState = snapshot.child("feederState").getValue(String::class.java)
                    val feedBusy = snapshot.child("feederBusy").getValue(Boolean::class.java) == true
                    val lastFeedAt = readLong(snapshot, "lastFeedAt")
                    lastSeenSeconds = lastSeen
                    isOnline = online
                    feederBusy = feedBusy
                    feederState = feedState ?: if (feedBusy) "Feeding" else "Ready"
                    if (feedBusy || feederState.equals("Ready", ignoreCase = true)) {
                        feedRequestPending = false
                    }
                    lastFeedAtSeconds = lastFeedAt
                    requireActivity().runOnUiThread {
                        updateDeviceStatus(fragmentBinding)
                        updateFeederUi(fragmentBinding)
                        swipeRefreshLayout?.isRefreshing = false
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(logTag, "Status listener cancelled: ${error.message}")
                    swipeRefreshLayout?.isRefreshing = false
                }
            }
            statusListener = connListener
            statusRef.addValueEventListener(connListener)
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val fragmentBinding = binding ?: return
                val temperature = readDouble(snapshot, "temperature")
                val humidity = readDouble(snapshot, "humidity")
                val waterLevel = normalizeWaterLevel(readDouble(snapshot, "waterLevel"))
                val phLevel = readDouble(snapshot, "ph")
                val turbidity = normalizeTurbidity(readDouble(snapshot, "turbidity"))

                Log.d(
                    logTag,
                    "DataChange -> temp=$temperature hum=$humidity ph=$phLevel water=$waterLevel"
                )

                lastDataAtMillis = System.currentTimeMillis()
                latestTemp = temperature?.toFloat()
                latestHumidity = humidity?.toFloat()
                latestWater = waterLevel?.toFloat()
                latestPh = phLevel?.toFloat()
                latestTurbidity = turbidity?.toFloat()

                requireActivity().runOnUiThread {
                    updateDeviceStatus(fragmentBinding)
                    fragmentBinding.tvLastUpdated.text =
                        "Last update: ${DateFormat.format("HH:mm:ss", Date())}"
                    fragmentBinding.tvSyncStatus.text =
                        "Last sync: ${DateFormat.format("HH:mm:ss", Date())}"

                    if (temperature != null) {
                        val value = String.format("%.1f \u00B0C", temperature)
                        fragmentBinding.tvTempValue.text = value
                        fragmentBinding.tvTrendTempValue.text = value
                    }

                    if (humidity != null) {
                        val value = String.format("%.0f %%", humidity)
                        fragmentBinding.tvHumidityValue.text = value
                        fragmentBinding.tvTrendHumidityValue.text = value
                    }

                    if (waterLevel != null) {
                        val value = String.format("%.0f", waterLevel)
                        fragmentBinding.tvWaterValue.text = value
                        fragmentBinding.tvTrendWaterValue.text = value
                    }

                    if (phLevel != null) {
                        val value = String.format("%.2f", phLevel)
                        fragmentBinding.tvTrendPhValue.text = value
                    }
                    if (turbidity != null) {
                        tvTrendTurbidityValueDynamic?.text = String.format("%.1f NTU", turbidity)
                    }

                    if (tempHistory.isEmpty()) {
                        latestTemp?.let { addPoint(tempHistory, it) }
                        latestHumidity?.let { addPoint(humidityHistory, it) }
                        latestWater?.let { addPoint(waterHistory, it) }
                        latestPh?.let { addPoint(phHistory, it) }
                        latestTurbidity?.let { addPoint(turbidityHistory, it) }

                        fragmentBinding.sparkTemp.setData(tempHistory.toList())
                        fragmentBinding.sparkHumidity.setData(humidityHistory.toList())
                        fragmentBinding.sparkWater.setData(waterHistory.toList())
                        fragmentBinding.sparkPh.setData(phHistory.toList())
                        sparkTurbidityDynamic?.setData(turbidityHistory.toList())
                    }
                    swipeRefreshLayout?.isRefreshing = false
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(logTag, "Firebase cancelled: ${error.message}")
                updateStatus(connected = false, message = "ESP32: Firebase error")
                swipeRefreshLayout?.isRefreshing = false
            }
        }

        sensorsListener = listener
        sensorsRef.addValueEventListener(listener)
    }

    private fun detachListener() {
        sensorsListener?.let { sensorsRef.removeEventListener(it) }
        sensorsListener = null
        statusListener?.let { statusRef.removeEventListener(it) }
        statusListener = null
    }

    private fun attachLightListener() {
        if (lightListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lightOn = snapshot.getValue(Boolean::class.java) == true
                val fragmentBinding = binding ?: return
                requireActivity().runOnUiThread {
                    updateLightUi(fragmentBinding)
                    swipeRefreshLayout?.isRefreshing = false
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(logTag, "Light listener cancelled: ${error.message}")
                swipeRefreshLayout?.isRefreshing = false
            }
        }

        lightListener = listener
        lightRef.addValueEventListener(listener)
    }

    private fun detachLightListener() {
        lightListener?.let { lightRef.removeEventListener(it) }
        lightListener = null
    }

    private fun updateStatus(connected: Boolean, message: String) {
        val fragmentBinding = binding ?: return
        requireActivity().runOnUiThread {
            fragmentBinding.tvDeviceStatus.text = message
            fragmentBinding.tvLastUpdated.text =
                "Last update: ${DateFormat.format("HH:mm:ss", Date())}"
            fragmentBinding.tvSyncStatus.text =
                if (lastDataAtMillis == null) "Last sync: --"
                else "Last sync: ${DateFormat.format("HH:mm:ss", Date(lastDataAtMillis!!))}"

            if (!connected) {
                fragmentBinding.tvFeedStatus.text = "Status: Waiting for ESP32"
                fragmentBinding.btnFeed.isEnabled = false
                fragmentBinding.btnFeed.alpha = 0.65f
                if (!hasSensorDataCached()) {
                    clearSensorDisplay(fragmentBinding)
                } else {
                    fragmentBinding.tvSyncStatus.text = if (lastDataAtMillis == null) {
                        "Last sync: --"
                    } else {
                        "Last sync: ${DateFormat.format("HH:mm:ss", Date(lastDataAtMillis!!))} (cached)"
                    }
                }
            }
        }
    }

    private fun readDouble(snapshot: DataSnapshot, key: String): Double? {
        val value = snapshot.child(key).value
        return (value as? Number)?.toDouble()
    }

    private fun readLong(snapshot: DataSnapshot, key: String): Long? {
        return snapshot.child(key).getValue(Long::class.java)
            ?: snapshot.child(key).getValue(Int::class.java)?.toLong()
            ?: snapshot.child(key).getValue(Double::class.java)?.toLong()
    }

    private fun loadTrendsFromTrend() {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        logsRef.child(dateKey).orderByKey().limitToLast(maxHistoryPoints)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    val fragmentBinding = binding ?: return

                    if (tempHistory.isEmpty() && phHistory.isEmpty()
                        && humidityHistory.isEmpty() && waterHistory.isEmpty()
                    ) {
                        snapshot.children.forEach { entry ->
                            readNumber(entry, "temperature")?.let { addPoint(tempHistory, it) }
                            readNumber(entry, "ph")?.let { addPoint(phHistory, it) }
                            readNumber(entry, "humidity")?.let { addPoint(humidityHistory, it) }
                            normalizeWaterLevel(readNumber(entry, "waterLevel"))
                                ?.let { addPoint(waterHistory, it) }
                            normalizeTurbidity(readNumber(entry, "turbidity"))
                                ?.let { addPoint(turbidityHistory, it) }
                        }

                        requireActivity().runOnUiThread {
                            fragmentBinding.sparkTemp.setData(tempHistory.toList())
                            fragmentBinding.sparkPh.setData(phHistory.toList())
                            fragmentBinding.sparkHumidity.setData(humidityHistory.toList())
                            fragmentBinding.sparkWater.setData(waterHistory.toList())
                            sparkTurbidityDynamic?.setData(turbidityHistory.toList())
                            swipeRefreshLayout?.isRefreshing = false
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(logTag, "Trend load cancelled: ${error.message}")
                    swipeRefreshLayout?.isRefreshing = false
                }
            })
    }

    private fun readNumber(snapshot: DataSnapshot, key: String): Float? {
        val value = snapshot.child(key).value
        return (value as? Number)?.toFloat()
    }

    private fun normalizeWaterLevel(value: Double?): Double? {
        if (value == null) return null
        if (value < 0) return null
        return value.coerceIn(0.0, 300.0)
    }

    private fun normalizeWaterLevel(value: Float?): Float? {
        if (value == null) return null
        if (value < 0f) return null
        return value.coerceIn(0f, 300f)
    }

    private fun normalizeTurbidity(value: Double?): Double? {
        if (value == null) return null
        if (value < 0) return null
        return value.coerceIn(0.0, 300.0)
    }

    private fun normalizeTurbidity(value: Float?): Float? {
        if (value == null) return null
        if (value < 0f) return null
        return value.coerceIn(0f, 300f)
    }

    private fun updateDeviceStatus(fragmentBinding: FragmentDashboardBinding) {
        val lastSeen = lastSeenSeconds
        val online = isOnline
        if (!isInternetAvailable()) {
            fragmentBinding.tvDeviceStatus.text = "App offline: No internet"
            fragmentBinding.tvSyncStatus.text = if (lastDataAtMillis == null) {
                "Last sync: --"
            } else {
                "Last sync: ${DateFormat.format("HH:mm:ss", Date(lastDataAtMillis!!))} (cached)"
            }
            if (!hasSensorDataCached()) {
                clearSensorDisplay(fragmentBinding)
            }
            return
        }
        if (lastSeen == null) {
            fragmentBinding.tvDeviceStatus.text = "ESP32: No data"
            fragmentBinding.tvSyncStatus.text = if (lastDataAtMillis == null) {
                "Last sync: --"
            } else {
                "Last sync: ${DateFormat.format("HH:mm:ss", Date(lastDataAtMillis!!))} (cached)"
            }
            if (!hasSensorDataCached()) {
                clearSensorDisplay(fragmentBinding)
            }
            return
        }

        val nowSeconds = System.currentTimeMillis() / 1000
        val diff = nowSeconds - lastSeen
        val isDeviceOnline = online != false && diff <= deviceOfflineGraceSeconds
        if (online == false) {
            fragmentBinding.tvDeviceStatus.text = "ESP32: Offline (last seen ${formatAge(diff)} ago)"
        } else if (diff <= deviceOfflineGraceSeconds) {
            fragmentBinding.tvDeviceStatus.text = "ESP32: Connected"
        } else {
            fragmentBinding.tvDeviceStatus.text = "ESP32: Offline (last seen ${formatAge(diff)} ago)"
        }
        fragmentBinding.tvSyncStatus.text = if (isDeviceOnline) {
            "Last sync: ${DateFormat.format("HH:mm:ss", Date(lastSeen * 1000))}"
        } else {
            "Last sync: ${DateFormat.format("HH:mm:ss", Date(lastSeen * 1000))} (stale)"
        }
        if (!isDeviceOnline) {
            if (!hasSensorDataCached()) {
                clearSensorDisplay(fragmentBinding)
            }
        }
    }

    private fun hasSensorDataCached(): Boolean {
        return latestTemp != null ||
            latestHumidity != null ||
            latestWater != null ||
            latestPh != null ||
            latestTurbidity != null ||
            tempHistory.isNotEmpty() ||
            humidityHistory.isNotEmpty() ||
            waterHistory.isNotEmpty() ||
            phHistory.isNotEmpty() ||
            turbidityHistory.isNotEmpty()
    }

    private fun formatAge(seconds: Long): String {
        return when {
            seconds < 60L -> "${seconds.coerceAtLeast(0)}s"
            else -> "${(seconds / 60L).coerceAtLeast(1)}m"
        }
    }

    private fun clearSensorDisplay(fragmentBinding: FragmentDashboardBinding) {
        fragmentBinding.tvTempValue.text = "-- \u00B0C"
        fragmentBinding.tvHumidityValue.text = "-- %"
        fragmentBinding.tvWaterValue.text = "--"
        fragmentBinding.tvTrendTempValue.text = "-- \u00B0C"
        fragmentBinding.tvTrendPhValue.text = "--"
        fragmentBinding.tvTrendHumidityValue.text = "-- %"
        fragmentBinding.tvTrendWaterValue.text = "--"
        tvTrendTurbidityValueDynamic?.text = "--"

        latestTemp = null
        latestHumidity = null
        latestWater = null
        latestPh = null
        latestTurbidity = null
        trendTemp = null
        trendHumidity = null
        trendWater = null
        trendPh = null
        trendTurbidity = null
        lastDataAtMillis = null

        tempHistory.clear()
        phHistory.clear()
        humidityHistory.clear()
        waterHistory.clear()
        turbidityHistory.clear()
        fragmentBinding.sparkTemp.setData(emptyList())
        fragmentBinding.sparkPh.setData(emptyList())
        fragmentBinding.sparkHumidity.setData(emptyList())
        fragmentBinding.sparkWater.setData(emptyList())
        sparkTurbidityDynamic?.setData(emptyList())
    }

    private fun addPoint(history: ArrayDeque<Float>, value: Float) {
        if (history.size >= maxHistoryPoints) {
            history.removeFirst()
        }
        history.addLast(value)
    }

    // UI-only smoothing so trends feel live every second without changing DB/SD logging cadence.
    private fun advanceTrendValue(current: Float?, target: Float?): Float? {
        if (target == null) return current
        if (current == null) return target
        val delta = target - current
        if (abs(delta) < 0.005f) return target
        return current + (delta * 0.35f)
    }

    private fun updateLightUi(fragmentBinding: FragmentDashboardBinding) {
        if (!appLightControlEnabled) {
            fragmentBinding.tvLightStatus.text = "Status: Voice only"
            fragmentBinding.btnLightToggle.isEnabled = false
            fragmentBinding.btnLightToggle.alpha = 0.45f
            fragmentBinding.btnLightToggle.text = "VOICE ONLY"
            return
        }
        fragmentBinding.tvLightStatus.text = if (lightOn) "Status: ON" else "Status: OFF"
        fragmentBinding.btnLightToggle.isEnabled = !lightSyncing
        fragmentBinding.btnLightToggle.alpha = if (lightSyncing) 0.65f else 1f
        fragmentBinding.btnLightToggle.text = when {
            lightSyncing -> "SYNCING"
            lightOn -> "TURN OFF"
            else -> "TURN ON"
        }
    }

    private fun updateFeederUi(fragmentBinding: FragmentDashboardBinding) {
        val now = System.currentTimeMillis()
        val cooldownActive = now < feedCooldownUntilMs
        val cooldownSeconds = ((feedCooldownUntilMs - now + 999L) / 1000L).coerceAtLeast(0)
        val statusText = when {
            feederBusy -> "Status: Feeding..."
            feedSyncing -> "Status: Sending request..."
            feedRequestPending -> "Status: Waiting for ESP32..."
            cooldownActive -> "Status: Cooldown ${cooldownSeconds}s"
            feederState.isNotBlank() -> "Status: $feederState"
            else -> "Status: Ready"
        }

        val lastFeedText = lastFeedAtSeconds?.let {
            " • Last: ${DateFormat.format("HH:mm:ss", Date(it * 1000))}"
        }.orEmpty()

        fragmentBinding.tvFeedStatus.text = if (feederBusy || feedSyncing) {
            statusText
        } else {
            statusText + lastFeedText
        }

        fragmentBinding.btnFeed.isEnabled = !feedSyncing && !feederBusy && !cooldownActive && !feedRequestPending
        fragmentBinding.btnFeed.alpha = if (feedSyncing || feederBusy || cooldownActive || feedRequestPending) 0.65f else 1f
        fragmentBinding.btnFeed.text = when {
            feedSyncing -> "SYNCING"
            feederBusy -> "FEEDING"
            feedRequestPending -> "WAITING"
            cooldownActive -> "WAIT ${cooldownSeconds}s"
            else -> "FEED"
        }
    }

    private fun startTrendTicker() {
        if (trendTicker != null) return
        trendTicker = object : Runnable {
            override fun run() {
                val fragmentBinding = binding
                val lastData = lastDataAtMillis
                if (fragmentBinding != null && lastData != null) {
                    val stale = System.currentTimeMillis() - lastData > 2 * 60 * 1000L
                    if (!stale) {
                        trendTemp = advanceTrendValue(trendTemp, latestTemp)
                        trendHumidity = advanceTrendValue(trendHumidity, latestHumidity)
                        trendWater = advanceTrendValue(trendWater, latestWater)
                        trendPh = advanceTrendValue(trendPh, latestPh)
                        trendTurbidity = advanceTrendValue(trendTurbidity, latestTurbidity)

                        trendTemp?.let { addPoint(tempHistory, it) }
                        trendHumidity?.let { addPoint(humidityHistory, it) }
                        trendWater?.let { addPoint(waterHistory, it) }
                        trendPh?.let { addPoint(phHistory, it) }
                        trendTurbidity?.let { addPoint(turbidityHistory, it) }

                        fragmentBinding.sparkTemp.setData(tempHistory.toList())
                        fragmentBinding.sparkHumidity.setData(humidityHistory.toList())
                        fragmentBinding.sparkWater.setData(waterHistory.toList())
                        fragmentBinding.sparkPh.setData(phHistory.toList())
                        sparkTurbidityDynamic?.setData(turbidityHistory.toList())
                    }
                }
                if (fragmentBinding != null) {
                    updateFeederUi(fragmentBinding)
                }
                trendHandler.postDelayed(this, trendUiIntervalMs)
            }
        }
        trendHandler.postDelayed(trendTicker!!, trendUiIntervalMs)
    }

    private fun stopTrendTicker() {
        trendTicker?.let { trendHandler.removeCallbacks(it) }
        trendTicker = null
    }

    private fun isInternetAvailable(): Boolean {
        val context = context ?: return false
        return NetworkState.isInternetAvailable(context)
    }

    private fun ensureTurbidityTrendRow(fragmentBinding: FragmentDashboardBinding) {
        if (tvTrendTurbidityValueDynamic != null && sparkTurbidityDynamic != null) return
        val container = fragmentBinding.root.findViewById<LinearLayout>(R.id.trendsContainer) ?: return
        val context = requireContext()
        val dp = resources.displayMetrics.density

        val row = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt() }
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }

        val labelCol = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(context).apply {
            text = "Turbidity stats"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.stat_text_secondary))
            maxLines = 1
        }
        val value = TextView(context).apply {
            text = "--"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.stat_text_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        val spark = SparklineView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                (56 * dp).toInt(),
                1f
            ).apply { marginStart = (12 * dp).toInt() }
            setLineColor(ContextCompat.getColor(context, R.color.stat_accent_water))
        }

        labelCol.addView(title)
        labelCol.addView(value)
        row.addView(labelCol)
        row.addView(spark)
        container.addView(row, container.childCount - 1)

        tvTrendTurbidityValueDynamic = value
        sparkTurbidityDynamic = spark
    }
}

