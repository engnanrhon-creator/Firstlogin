package com.example.firstlogin

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.firstlogin.databinding.FragmentStatsBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Date

class StatsFragment : Fragment() {

    // ===== VIEW REFERENCES =====
    private var binding: FragmentStatsBinding? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null

    // ===== FIREBASE PATHS =====
    private val sensorsRef by lazy {
        FirebaseDatabase.getInstance().getReference("sensors")
    }
    private val statusRef by lazy {
        FirebaseDatabase.getInstance().getReference("status")
    }
    // ===== LISTENERS + STATUS CACHE =====
    private var sensorsListener: ValueEventListener? = null
    private var statusListener: ValueEventListener? = null
    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusTicker: Runnable? = null
    private var lastSeenSeconds: Long? = null
    private var isOnline: Boolean? = null
    private val deviceOfflineGraceSeconds = 90L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate stats layout and wrap it with pull-to-refresh.
        val fragmentBinding = FragmentStatsBinding.inflate(inflater, container, false)
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
                refreshStats()
            }
        }
        swipeRefreshLayout = refreshLayout
        return refreshLayout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Top arrow returns to previous fragment.

        val fragmentBinding = binding ?: return

        fragmentBinding.arrowup.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    override fun onStart() {
        super.onStart()
        // Attach listeners while this screen is visible.
        attachListener()
        attachStatusListener()
        startStatusTicker()
    }

    override fun onStop() {
        super.onStop()
        // Detach listeners/ticker in background.
        detachListener()
        detachStatusListener()
        stopStatusTicker()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Release view references to avoid leaks.
        swipeRefreshLayout = null
        binding = null
    }

    private fun refreshStats() {
        // Restart listeners to force fresh snapshot and UI update.
        swipeRefreshLayout?.isRefreshing = true
        detachListener()
        detachStatusListener()
        attachListener()
        attachStatusListener()
        statusHandler.postDelayed({
            swipeRefreshLayout?.isRefreshing = false
        }, 1200L)
    }

    private fun attachListener() {
        // Read sensor values in real-time from /sensors.
        if (sensorsListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val fragmentBinding = binding ?: return
                val temperature = readDouble(snapshot, "temperature")
                val humidity = readDouble(snapshot, "humidity")
                val waterLevel = normalizeWaterLevel(readDouble(snapshot, "waterLevel"))
                val phLevel = readDouble(snapshot, "ph")
                val turbidity = normalizeTurbidity(readDouble(snapshot, "turbidity"))

                requireActivity().runOnUiThread {
                    updateDeviceStatus(fragmentBinding)
                    fragmentBinding.tvStatsLastUpdated.text =
                        "Last update: ${DateFormat.format("HH:mm:ss", Date())}"

                    if (temperature != null) {
                        fragmentBinding.tvStatsTempValue.text =
                            String.format("%.1f \u00B0C", temperature)
                    }

                    if (humidity != null) {
                        fragmentBinding.tvStatsHumidityValue.text =
                            String.format("%.0f %%", humidity)
                    }

                    if (waterLevel != null) {
                        fragmentBinding.tvStatsWaterValue.text =
                            String.format("%.0f", waterLevel)
                    }

                    if (phLevel != null) {
                        fragmentBinding.tvStatsPhValue.text =
                            String.format("%.2f", phLevel)
                    }

                    fragmentBinding.tvStatsWaterFlowValue.text = if (turbidity != null) {
                        String.format("%.1f", turbidity)
                    } else {
                        "--"
                    }
                    swipeRefreshLayout?.isRefreshing = false
                }
            }

            override fun onCancelled(error: DatabaseError) {
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
    }

    private fun attachStatusListener() {
        // Track /status online + lastSeen to render connection state.
        if (statusListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val fragmentBinding = binding ?: return
                lastSeenSeconds = snapshot.child("lastSeen").getValue(Long::class.java)
                    ?: snapshot.child("lastSeen").getValue(Double::class.java)?.toLong()
                isOnline = snapshot.child("online").getValue(Boolean::class.java)

                requireActivity().runOnUiThread {
                    updateDeviceStatus(fragmentBinding)
                    swipeRefreshLayout?.isRefreshing = false
                }
            }

            override fun onCancelled(error: DatabaseError) {
                updateStatus(connected = false, message = "ESP32: Firebase error")
                swipeRefreshLayout?.isRefreshing = false
            }
        }

        statusListener = listener
        statusRef.addValueEventListener(listener)
    }

    private fun detachStatusListener() {
        statusListener?.let { statusRef.removeEventListener(it) }
        statusListener = null
    }

    private fun updateStatus(connected: Boolean, message: String) {
        val fragmentBinding = binding ?: return
        requireActivity().runOnUiThread {
            fragmentBinding.tvStatsDeviceStatus.text = message
            fragmentBinding.tvStatsLastUpdated.text =
                "Last update: ${DateFormat.format("HH:mm:ss", Date())}"

            if (!connected) {
                if (!hasStatsDataDisplayed(fragmentBinding)) {
                    clearStatsDisplay(fragmentBinding)
                }
            }
        }
    }

    private fun readDouble(snapshot: DataSnapshot, key: String): Double? {
        val value = snapshot.child(key).value
        return (value as? Number)?.toDouble()
    }

    private fun normalizeWaterLevel(value: Double?): Double? {
        if (value == null) return null
        if (value < 0) return null
        return value.coerceIn(0.0, 300.0)
    }

    private fun normalizeTurbidity(value: Double?): Double? {
        if (value == null) return null
        if (value < 0) return null
        return value.coerceIn(0.0, 100.0)
    }

    private fun updateDeviceStatus(fragmentBinding: FragmentStatsBinding) {
        val lastSeen = lastSeenSeconds
        if (lastSeen == null) {
            fragmentBinding.tvStatsDeviceStatus.text = "ESP32: No data"
            if (!hasStatsDataDisplayed(fragmentBinding)) {
                clearStatsDisplay(fragmentBinding)
            }
            return
        }

        val diff = (System.currentTimeMillis() / 1000) - lastSeen
        val isDeviceOnline = isOnline != false && diff <= deviceOfflineGraceSeconds
        fragmentBinding.tvStatsDeviceStatus.text = if (!isDeviceOnline) {
            "ESP32: Offline (last seen ${formatAge(diff)} ago)"
        } else {
            "ESP32: Connected"
        }
        if (!isDeviceOnline) {
            if (!hasStatsDataDisplayed(fragmentBinding)) {
                clearStatsDisplay(fragmentBinding)
            }
        }
    }

    private fun hasStatsDataDisplayed(fragmentBinding: FragmentStatsBinding): Boolean {
        return fragmentBinding.tvStatsTempValue.text.toString() != "-- \u00B0C" ||
            fragmentBinding.tvStatsHumidityValue.text.toString() != "-- %" ||
            fragmentBinding.tvStatsWaterValue.text.toString() != "--" ||
            fragmentBinding.tvStatsPhValue.text.toString() != "--" ||
            fragmentBinding.tvStatsWaterFlowValue.text.toString() != "--"
    }

    private fun formatAge(seconds: Long): String {
        return when {
            seconds < 60L -> "${seconds.coerceAtLeast(0)}s"
            else -> "${(seconds / 60L).coerceAtLeast(1)}m"
        }
    }

    private fun clearStatsDisplay(fragmentBinding: FragmentStatsBinding) {
        fragmentBinding.tvStatsTempValue.text = "-- \u00B0C"
        fragmentBinding.tvStatsHumidityValue.text = "-- %"
        fragmentBinding.tvStatsWaterValue.text = "--"
        fragmentBinding.tvStatsPhValue.text = "--"
        fragmentBinding.tvStatsWaterFlowValue.text = "--"
    }

    private fun startStatusTicker() {
        if (statusTicker != null) return
        statusTicker = object : Runnable {
            override fun run() {
                binding?.let { updateDeviceStatus(it) }
                statusHandler.postDelayed(this, 5000)
            }
        }
        statusHandler.post(statusTicker!!)
    }

    private fun stopStatusTicker() {
        statusTicker?.let { statusHandler.removeCallbacks(it) }
        statusTicker = null
    }
}
