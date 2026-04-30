package com.example.firstlogin.ui.login

import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.firstlogin.NetworkState
import com.example.firstlogin.R
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Date

class notificationFragment : Fragment() {

    // Bundle of views for one alert card row.
    private data class AlertCardViews(
        val title: TextView,
        val message: TextView,
        val time: TextView
    )

    // ===== FIREBASE PATHS =====
    private val alertsRef by lazy {
        FirebaseDatabase.getInstance().getReference("alerts")
    }
    private val statusRef by lazy {
        FirebaseDatabase.getInstance().getReference("status")
    }
    private val controlsRef by lazy {
        FirebaseDatabase.getInstance().getReference("controls")
    }
    private var alertsListener: ValueEventListener? = null
    private var statusListener: ValueEventListener? = null
    private var controlsListener: ValueEventListener? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private val alertCards = mutableMapOf<String, AlertCardViews>()

    // ===== DEVICE STATUS CACHE =====
    private var espOnline: Boolean? = null
    private var espLastSeen: Long? = null
    private var latestLightState: Boolean? = null
    private var latestLightAtMillis: Long? = null
    private var latestFeedAtEpoch: Long? = null

    // ===== PULL-UP REFRESH GESTURE =====
    private var pullStartY = 0f
    private var canTriggerPullUpRefresh = false
    private var lastPullUpRefreshMs = 0L
    private val pullUpRefreshThresholdPx = 72f
    private val pullUpRefreshCooldownMs = 1200L
    private val deviceOfflineGraceSeconds = 90L

    // Inflate notifications screen and wrap with SwipeRefreshLayout.
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_notification, container, false)
        val scrollView = (view as? ViewGroup)?.findScrollViewChild()
        addAlertCards(view)
        setupPullUpRefresh(view)
        view.findViewById<ImageView>(R.id.btnBell)?.setOnClickListener {
            swipeRefreshLayout?.isRefreshing = true
            refreshAlerts()
        }
        val refreshLayout = SwipeRefreshLayout(requireContext()).apply {
            setColorSchemeResources(
                R.color.stat_accent_water,
                R.color.stat_accent,
                R.color.stat_accent_alt
            )
            // Require a deliberate pull before triggering refresh.
            setDistanceToTriggerSync(120)
            addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // Prevent pull-to-refresh while user is scrolling through notifications.
            setOnChildScrollUpCallback { _, _ ->
                scrollView?.let { !isAtTop(it) } ?: false
            }
            setOnRefreshListener {
                refreshAlerts()
            }
        }
        swipeRefreshLayout = refreshLayout
        return refreshLayout
    }

    // Start Firebase listeners only when screen is active.
    override fun onStart() {
        super.onStart()
        if (!isInternetAvailable()) {
            showNoInternetOnCards()
            return
        }
        attachStatusListener()
        attachAlertsListener()
        attachControlsListener()
    }

    // Remove listeners to prevent duplicate callbacks.
    override fun onStop() {
        super.onStop()
        alertsListener?.let { alertsRef.removeEventListener(it) }
        alertsListener = null
        statusListener?.let { statusRef.removeEventListener(it) }
        statusListener = null
        controlsListener?.let { controlsRef.removeEventListener(it) }
        controlsListener = null
    }

    // Clear local view map when view is destroyed.
    override fun onDestroyView() {
        super.onDestroyView()
        swipeRefreshLayout = null
        alertCards.clear()
    }

    // Build fixed alert card list in display order.
    private fun addAlertCards(root: View) {
        val list = (root as? ViewGroup)?.findLinearLayoutChild() ?: return
        list.removeAllViews()
        addAlertCard(list, "waterLevel", "Water level monitor", "Waiting for ESP32 water-level data...")
        addAlertCard(list, "temperature", "Temperature monitor", "Waiting for ESP32 temperature data...")
        addAlertCard(list, "humidity", "Humidity monitor", "Waiting for ESP32 humidity data...")
        addAlertCard(list, "ph", "pH monitor", "Waiting for ESP32 pH data...")
        addAlertCard(list, "turbidity", "Turbidity monitor", "Waiting for ESP32 turbidity data...")
        addAlertCard(list, "light", "Light monitor", "Waiting for light status...")
        addAlertCard(list, "feeder", "Feeder monitor", "Waiting for feeder status...")
    }

    // Create one reusable alert card row.
    private fun addAlertCard(
        list: LinearLayout,
        key: String,
        defaultTitle: String,
        defaultMessage: String
    ) {
        val context = requireContext()
        val card = CardView(context).apply {
            radius = 16f
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.stat_card_bg))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dp()
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.alert)
            setColorFilter(ContextCompat.getColor(context, alertColor(key)))
            layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp())
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dp()
            }
        }

        val title = TextView(context).apply {
            text = defaultTitle
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.stat_text_primary))
            setTypeface(typeface, Typeface.BOLD)
        }

        val message = TextView(context).apply {
            text = defaultMessage
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.stat_text_secondary))
        }

        val time = TextView(context).apply {
            text = "Last update: --"
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.stat_text_secondary))
        }

        textColumn.addView(title)
        textColumn.addView(message)
        textColumn.addView(time)
        row.addView(icon)
        row.addView(textColumn)
        card.addView(row)
        list.addView(card, alertCards.size)
        alertCards[key] = AlertCardViews(title, message, time)
    }

    // Listen to /alerts path and refresh cards in real-time.
    private fun attachAlertsListener() {
        if (alertsListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                updateAlertCard("waterLevel", snapshot.child("waterLevel"))
                updateAlertCard("temperature", snapshot.child("temperature"))
                updateAlertCard("humidity", snapshot.child("humidity"))
                updateAlertCard("ph", snapshot.child("ph"))
                updateAlertCard("turbidity", snapshot.child("turbidity"))
                swipeRefreshLayout?.isRefreshing = false
            }

            override fun onCancelled(error: DatabaseError) {
                alertCards.values.forEach {
                    it.message.text = "Unable to read alerts: ${error.message}"
                }
                swipeRefreshLayout?.isRefreshing = false
            }
        }

        alertsListener = listener
        alertsRef.addValueEventListener(listener)
    }

    // Listen to /status path for ESP online state and feeder updates.
    private fun attachStatusListener() {
        if (statusListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                espOnline = snapshot.child("online").getValue(Boolean::class.java)
                espLastSeen = (snapshot.child("lastSeen").value as? Number)?.toLong()
                updateFeederCard(snapshot)
                refreshAlerts()
            }

            override fun onCancelled(error: DatabaseError) {
                espOnline = false
                espLastSeen = null
                alertCards.forEach { (key, views) ->
                    if (key == "light") {
                        views.title.text = "Light monitor"
                        views.message.text = "Waiting for light status (ESP32 offline)"
                    } else if (key == "feeder") {
                        views.title.text = "Feeder monitor"
                        views.message.text = "ESP32 is not connected yet"
                    } else {
                        views.message.text = "ESP32 is not connected yet"
                    }
                    views.time.text = "Last update: --"
                }
            }
        }

        statusListener = listener
        statusRef.addValueEventListener(listener)
    }

    private fun attachControlsListener() {
        if (controlsListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val views = alertCards["light"] ?: return
                val lightOn = snapshot.child("light").getValue(Boolean::class.java)
                latestLightState = lightOn
                latestLightAtMillis = System.currentTimeMillis()
                views.title.text = "Light monitor"
                views.message.text = when (lightOn) {
                    true -> "Light is currently ON (SYNCED)"
                    false -> "Light is currently OFF (SYNCED)"
                    null -> "Waiting for light status..."
                }
                views.time.text = "Last update: ${DateFormat.format("HH:mm:ss", Date(latestLightAtMillis!!))}"
            }

            override fun onCancelled(error: DatabaseError) {
                alertCards["light"]?.let {
                    it.title.text = "Light monitor"
                    it.message.text = "Unable to read light status: ${error.message}"
                    it.time.text = "Last update: --"
                }
            }
        }

        controlsListener = listener
        controlsRef.addValueEventListener(listener)
    }

    private fun refreshAlerts() {
        if (!isInternetAvailable()) {
            swipeRefreshLayout?.isRefreshing = false
            showNoInternetOnCards()
            Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
            return
        }
        if (controlsListener == null) {
            attachControlsListener()
        }
        if (alertsListener == null) {
            attachAlertsListener()
            return
        }
        swipeRefreshLayout?.isRefreshing = true
        alertsRef.get()
            .addOnSuccessListener { snapshot ->
                updateAlertCard("waterLevel", snapshot.child("waterLevel"))
                updateAlertCard("temperature", snapshot.child("temperature"))
                updateAlertCard("humidity", snapshot.child("humidity"))
                updateAlertCard("ph", snapshot.child("ph"))
                updateAlertCard("turbidity", snapshot.child("turbidity"))
            }
            .addOnFailureListener { error ->
                alertCards.values.forEach {
                    it.message.text = "Unable to refresh alerts: ${error.message}"
                }
            }
            .addOnCompleteListener {
                swipeRefreshLayout?.isRefreshing = false
            }
    }

    private fun updateAlertCard(key: String, snapshot: DataSnapshot) {
        val views = alertCards[key] ?: return
        val disconnectedText = espDisconnectedText()
        if (disconnectedText != null && !snapshot.exists()) {
            views.title.text = "${alertTitle(key)} monitor"
            views.message.text = disconnectedText
            views.time.text = espLastSeen?.let {
                "Last update: ${DateFormat.format("HH:mm:ss", Date(it * 1000))}"
            } ?: "Last update: --"
            return
        }

        val active = snapshot.child("active").getValue(Boolean::class.java) == true
        val status = snapshot.child("status").getValue(String::class.java) ?: "UNKNOWN"
        val message = snapshot.child("message").getValue(String::class.java)
            ?: "Waiting for ESP32 ${alertLabel(key)} data"
        val value = readNumber(snapshot, "value") ?: readNumber(snapshot, "distanceCm")
        val unit = snapshot.child("unit").getValue(String::class.java)
            ?: if (key == "waterLevel") "cm" else ""
        val updatedAt = readLong(snapshot, "updatedAt")
        val computedTurbidityStatus = if (key == "turbidity" && value != null) {
            turbidityStatusByNtu(value)
        } else {
            null
        }
        val computedTemperatureStatus = if (key == "temperature" && value != null) {
            temperatureStatusByCelsius(value)
        } else {
            null
        }
        val effectiveActive = when {
            key == "temperature" && computedTemperatureStatus != null -> computedTemperatureStatus != "NORMAL"
            key == "turbidity" && computedTurbidityStatus != null -> computedTurbidityStatus != "CLEAN"
            else -> active
        }

        views.title.text = when {
            effectiveActive -> "${alertTitle(key)} alert"
            else -> "${alertTitle(key)} monitor"
        } + if (disconnectedText != null) " (cached)" else ""

        val suffix = if (unit.isBlank()) "" else " $unit"
        val baseMessage = when {
            key == "temperature" && value != null && computedTemperatureStatus != null -> {
                val tone = when (computedTemperatureStatus) {
                    "NORMAL" -> "normal"
                    "WARNING" -> "warning"
                    "CRITICAL" -> "critical"
                    else -> "invalid"
                }
                "Temperature is $tone - ${String.format("%.1f", value)} C ($computedTemperatureStatus)"
            }
            key == "turbidity" && value != null && computedTurbidityStatus != null -> {
                "Water is ${computedTurbidityStatus.lowercase()} - ${String.format("%.1f", value)} NTU ($computedTurbidityStatus)"
            }
            else -> value?.let {
                "$message - ${String.format("%.1f", it)}$suffix ($status)"
            } ?: "$message ($status)"
        }
        views.message.text = if (disconnectedText != null) {
            "$baseMessage - Last known"
        } else {
            baseMessage
        }

        views.time.text = updatedAt?.let {
            "Last update: ${DateFormat.format("HH:mm:ss", Date(it * 1000))}"
        } ?: espLastSeen?.let {
            "Last update: ${DateFormat.format("HH:mm:ss", Date(it * 1000))}"
        } ?: "Last update: --"
    }

    private fun readNumber(snapshot: DataSnapshot, key: String): Double? {
        return (snapshot.child(key).value as? Number)?.toDouble()
    }

    private fun readLong(snapshot: DataSnapshot, key: String): Long? {
        return (snapshot.child(key).value as? Number)?.toLong()
    }

    private fun turbidityStatusByNtu(value: Double): String {
        return when {
            value < 0.0 -> "UNKNOWN"
            value <= 49.9 -> "CLEAN"
            value <= 70.0 -> "CLOUDY"
            else -> "DIRTY"
        }
    }

    private fun temperatureStatusByCelsius(value: Double): String {
        return when {
            value < 0.0 -> "SENSOR_ERROR"
            value < 25.0 -> "WARNING"
            value <= 35.0 -> "NORMAL"
            value <= 40.0 -> "WARNING"
            else -> "CRITICAL"
        }
    }

    private fun alertTitle(key: String): String = when (key) {
        "temperature" -> "Temperature"
        "humidity" -> "Humidity"
        "ph" -> "pH"
        "turbidity" -> "Turbidity"
        "light" -> "Light"
        else -> "Water level"
    }

    private fun alertLabel(key: String): String = when (key) {
        "temperature" -> "temperature"
        "humidity" -> "humidity"
        "ph" -> "pH"
        "turbidity" -> "turbidity"
        "light" -> "light"
        else -> "water-level"
    }

    private fun alertColor(key: String): Int = when (key) {
        "temperature" -> R.color.stat_accent
        "humidity" -> R.color.stat_accent_humidity
        "ph" -> R.color.stat_accent_alt
        "turbidity" -> R.color.stat_accent_water
        "light" -> R.color.stat_accent
        "feeder" -> R.color.stat_accent_humidity
        else -> R.color.stat_accent_water
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun isInternetAvailable(): Boolean {
        val context = context ?: return false
        return NetworkState.isInternetAvailable(context)
    }

    private fun showNoInternetOnCards() {
        alertCards.forEach { (key, views) ->
            if (key == "light" && latestLightState != null && latestLightAtMillis != null) {
                views.message.text = if (latestLightState == true) {
                    "Light is ON (app offline)"
                } else {
                    "Light is OFF (app offline)"
                }
                views.time.text = "Last update: ${DateFormat.format("HH:mm:ss", Date(latestLightAtMillis!!))}"
            } else if (key == "feeder") {
                views.message.text = "No internet connection. Feeder status unavailable."
                views.time.text = latestFeedAtEpoch?.let {
                    "Last update: ${DateFormat.format("HH:mm:ss", Date(it * 1000L))}"
                } ?: "Last update: --"
            } else {
                views.message.text = "No internet connection. Connect and pull to refresh."
                views.time.text = "Last update: --"
            }
        }
    }

    private fun setupPullUpRefresh(root: View) {
        val scrollView = (root as? ViewGroup)?.findScrollViewChild() ?: return
        scrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pullStartY = event.y
                    canTriggerPullUpRefresh = isAtBottom(scrollView)
                }

                MotionEvent.ACTION_MOVE -> {
                    val pullDistance = pullStartY - event.y
                    val now = System.currentTimeMillis()
                    if (
                        canTriggerPullUpRefresh &&
                        isAtBottom(scrollView) &&
                        pullDistance >= pullUpRefreshThresholdPx &&
                        now - lastPullUpRefreshMs >= pullUpRefreshCooldownMs &&
                        swipeRefreshLayout?.isRefreshing != true
                    ) {
                        lastPullUpRefreshMs = now
                        canTriggerPullUpRefresh = false
                        swipeRefreshLayout?.isRefreshing = true
                        refreshAlerts()
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    canTriggerPullUpRefresh = false
                }
            }
            false
        }
    }

    private fun isAtBottom(scrollView: ScrollView): Boolean {
        val child = scrollView.getChildAt(0) ?: return false
        val threshold = 12.dp()
        return scrollView.scrollY + scrollView.height >= child.measuredHeight - threshold
    }

    private fun isAtTop(scrollView: ScrollView): Boolean {
        return scrollView.scrollY <= 0
    }

    private fun updateFeederCard(statusSnapshot: DataSnapshot) {
        val views = alertCards["feeder"] ?: return
        val disconnectedText = espDisconnectedText()
        val feederBusy = statusSnapshot.child("feederBusy").getValue(Boolean::class.java) == true
        val feederState = statusSnapshot.child("feederState").getValue(String::class.java) ?: "Ready"
        val lastFeedAt = (statusSnapshot.child("lastFeedAt").value as? Number)?.toLong()
        latestFeedAtEpoch = lastFeedAt

        views.title.text = "Feeder monitor"
        views.message.text = when {
            disconnectedText != null -> disconnectedText
            feederBusy -> "Feeder is running..."
            else -> "Feeder state: $feederState"
        }
        views.time.text = when {
            lastFeedAt != null -> "Last update: ${DateFormat.format("HH:mm:ss", Date(lastFeedAt * 1000L))}"
            espLastSeen != null -> "Last update: ${DateFormat.format("HH:mm:ss", Date(espLastSeen!! * 1000L))}"
            else -> "Last update: --"
        }
    }

    private fun espDisconnectedText(): String? {
        val online = espOnline
        val lastSeen = espLastSeen
        val nowSeconds = System.currentTimeMillis() / 1000

        if (online == false) {
            if (lastSeen == null) return "ESP32 is not connected yet"
            val minutes = ((nowSeconds - lastSeen) / 60).coerceAtLeast(1)
            return "ESP32 is not connected yet (last seen ${minutes}m ago)"
        }

        if (lastSeen == null) return "ESP32 is not connected yet"

        if (nowSeconds - lastSeen > deviceOfflineGraceSeconds) {
            val minutes = ((nowSeconds - lastSeen) / 60).coerceAtLeast(1)
            return "ESP32 is not connected yet (last seen ${minutes}m ago)"
        }

        return null
    }

    private fun ViewGroup.findLinearLayoutChild(): LinearLayout? {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is LinearLayout) return child
            if (child is ViewGroup) {
                child.findLinearLayoutChild()?.let { return it }
            }
        }
        return null
    }

    private fun ViewGroup.findScrollViewChild(): ScrollView? {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is ScrollView) return child
            if (child is ViewGroup) {
                child.findScrollViewChild()?.let { return it }
            }
        }
        return null
    }
}
