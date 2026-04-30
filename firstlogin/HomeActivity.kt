package com.example.firstlogin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.firstlogin.ui.login.notificationFragment
import com.example.firstlogin.ui.login.settingFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class HomeActivity : AppCompatActivity() {

    // ===== FIREBASE PATHS =====
    private val alertsRef by lazy {
        FirebaseDatabase.getInstance().getReference("alerts")
    }
    private val controlsRef by lazy {
        FirebaseDatabase.getInstance().getReference("controls")
    }
    private val statusRef by lazy {
        FirebaseDatabase.getInstance().getReference("status")
    }

    // ===== LISTENER + NOTIFICATION STATE =====
    private var alertsListener: ValueEventListener? = null
    private var controlsListener: ValueEventListener? = null
    private var statusListener: ValueEventListener? = null
    private val lastNotificationKeys = mutableMapOf<String, String>()
    private var lastLightState: Boolean? = null
    private lateinit var notifPrefs: SharedPreferences
    private var lastFeederEpoch: Long? = null

    // Initialize bottom navigation and default dashboard fragment.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        notifPrefs = getSharedPreferences("notif_prefs", MODE_PRIVATE)
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, DashboardFragment())
                .commit()
        }

        bottomNav.setOnItemSelectedListener { item ->
            val current = supportFragmentManager.findFragmentById(R.id.container)

            when (item.itemId) {
                R.id.nav_Dashboard -> {
                    if (current !is DashboardFragment) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.container, DashboardFragment())
                            .commit()
                    }
                    true
                }

                R.id.nav_Notifications -> {
                    if (current !is notificationFragment) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.container, notificationFragment())
                            .commit()
                    }
                    true
                }

                R.id.nav_Settings -> {
                    if (current !is settingFragment) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.container, settingFragment())
                            .commit()
                    }
                    true
                }

                else -> false
            }
        }
    }

    // Start all live listeners while activity is in foreground.
    override fun onStart() {
        super.onStart()
        attachAlertsListener()
        attachControlsListener()
        attachStatusListener()
    }

    // Remove listeners to avoid duplicate callbacks in background.
    override fun onStop() {
        super.onStop()
        alertsListener?.let { alertsRef.removeEventListener(it) }
        alertsListener = null
        controlsListener?.let { controlsRef.removeEventListener(it) }
        controlsListener = null
        statusListener?.let { statusRef.removeEventListener(it) }
        statusListener = null
    }

    // Listen for sensor alert changes and fire notifications for active alerts.
    private fun attachAlertsListener() {
        if (alertsListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                notifyIfActive("waterLevel", snapshot.child("waterLevel"))
                notifyIfActive("temperature", snapshot.child("temperature"))
                notifyIfActive("humidity", snapshot.child("humidity"))
                notifyIfActive("ph", snapshot.child("ph"))
                notifyIfActive("turbidity", snapshot.child("turbidity"))
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }

        alertsListener = listener
        alertsRef.addValueEventListener(listener)
    }

    // Listen for light state changes from cloud control path.
    private fun attachControlsListener() {
        if (controlsListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lightOn = snapshot.child("light").getValue(Boolean::class.java)
                if (lightOn == null) return

                if (lastLightState == null) {
                    val previous = notifPrefs.getString("last_light_state", null)
                    if (previous != null && previous != lightOn.toString()) {
                        showLightNotification(lightOn)
                    }
                } else if (lastLightState != lightOn) {
                    showLightNotification(lightOn)
                }

                lastLightState = lightOn
                notifPrefs.edit().putString("last_light_state", lightOn.toString()).apply()
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }

        controlsListener = listener
        controlsRef.addValueEventListener(listener)
    }

    // Listen for feeder status transitions and notify on new completed feed cycles.
    private fun attachStatusListener() {
        if (statusListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val feedAt = snapshot.child("lastFeedAt").getValue(Long::class.java)
                    ?: (snapshot.child("lastFeedAt").value as? Number)?.toLong()
                val feedState = snapshot.child("feederState").getValue(String::class.java) ?: "Ready"

                if (feedAt != null) {
                    if (lastFeederEpoch == null) {
                        val prev = notifPrefs.getLong("last_feeder_epoch", 0L)
                        if (prev > 0L) lastFeederEpoch = prev
                    }

                    if (lastFeederEpoch != null && feedAt > (lastFeederEpoch ?: 0L)) {
                        showFeederNotification(feedAt, feedState)
                    }

                    lastFeederEpoch = feedAt
                    notifPrefs.edit().putLong("last_feeder_epoch", feedAt).apply()
                }
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }

        statusListener = listener
        statusRef.addValueEventListener(listener)
    }

    // Build de-duplicated alert notification key to suppress repeats.
    private fun notifyIfActive(alertKey: String, snapshot: DataSnapshot) {
        val active = snapshot.child("active").getValue(Boolean::class.java) == true
        if (!active) return

        val status = snapshot.child("status").getValue(String::class.java) ?: "ALERT"
        val message = snapshot.child("message").getValue(String::class.java)
            ?: "${alertLabel(alertKey)} needs attention"
        val value = readNumber(snapshot, "value")
            ?: readNumber(snapshot, "distanceCm")
        val unit = snapshot.child("unit").getValue(String::class.java)
            ?: if (alertKey == "waterLevel") "cm" else ""
        val updatedAt = readLong(snapshot, "updatedAt") ?: 0L
        val notificationKey = "$status-$updatedAt-${value ?: 0.0}"

        if (notificationKey != lastNotificationKeys[alertKey]) {
            lastNotificationKeys[alertKey] = notificationKey
            showAlertNotification(alertKey, status, message, value, unit)
        }
    }

    // Show one alert notification card (water/temp/humidity/ph/turbidity).
    private fun showAlertNotification(
        alertKey: String,
        status: String,
        message: String,
        value: Double?,
        unit: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = value?.let {
            val suffix = if (unit.isBlank()) "" else " $unit"
            "$message (${String.format("%.1f", it)}$suffix)"
        } ?: message
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.alert)
            .setContentTitle("Aquasave ${alertLabel(alertKey)} alert: $status")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this)
            .notify(alertNotificationId(alertKey), notification)
    }

    // Notify when light state switches ON/OFF.
    private fun showLightNotification(lightOn: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (lightOn) "Aquasave light is ON" else "Aquasave light is OFF"
        val detail = if (lightOn) "Light has been turned on." else "Light has been turned off."

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.alert)
            .setContentTitle(title)
            .setContentText(detail)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(3005, notification)
    }

    private fun showFeederNotification(feedAtEpoch: Long, feedState: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1003,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeText = android.text.format.DateFormat.format(
            "HH:mm:ss",
            java.util.Date(feedAtEpoch * 1000L)
        ).toString()
        val detail = "Feeder activated at $timeText (state: $feedState)"

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.alert)
            .setContentTitle("Aquasave feeder update")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(3006, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Aquasave alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when aquarium readings need attention"
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                2001
            )
        }
    }

    private fun alertLabel(alertKey: String): String = when (alertKey) {
        "temperature" -> "temperature"
        "humidity" -> "humidity"
        "ph" -> "pH"
        "turbidity" -> "turbidity"
        else -> "water level"
    }

    private fun alertNotificationId(alertKey: String): Int = when (alertKey) {
        "temperature" -> 3002
        "humidity" -> 3003
        "ph" -> 3004
        "turbidity" -> 3007
        else -> 3001
    }

    private fun readNumber(snapshot: DataSnapshot, key: String): Double? {
        return (snapshot.child(key).value as? Number)?.toDouble()
    }

    private fun readLong(snapshot: DataSnapshot, key: String): Long? {
        return (snapshot.child(key).value as? Number)?.toLong()
    }

    companion object {
        private const val ALERT_CHANNEL_ID = "aquasave_alerts"
    }
}
