package com.example.firstlogin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.example.firstlogin.R
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class PrivacyFragment : Fragment(R.layout.fragment_privacy) {

    // Manage backup/analytics privacy toggles with local + Firebase sync.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences("privacy_prefs", 0)
        val analytics = FirebaseAnalytics.getInstance(requireContext())
        val user = FirebaseAuth.getInstance().currentUser
        val privacyRef = user?.uid?.let { uid ->
            FirebaseDatabase.getInstance().getReference("users")
                .child(uid)
                .child("privacy")
        }
        val backupControlRef = FirebaseDatabase.getInstance()
            .getReference("controls")
            .child("allowDataBackup")

        val backupSwitch = view.findViewById<Switch>(R.id.switchBackup)
        val analyticsSwitch = view.findViewById<Switch>(R.id.switchAnalytics)
        val crashSwitch = view.findViewById<Switch>(R.id.switchCrashReports)

        backupSwitch.isChecked = prefs.getBoolean("allow_backup", true)
        analyticsSwitch.isChecked = prefs.getBoolean("allow_analytics", true)
        prefs.edit { remove("allow_crash_reports") }
        // Crash-report setting is deprecated; hide only that row.
        crashSwitch.visibility = View.GONE
        val crashParent = crashSwitch.parent as? ViewGroup
        if (crashParent != null && crashParent.childCount <= 3) {
            crashParent.visibility = View.GONE
        }

        analytics.setAnalyticsCollectionEnabled(analyticsSwitch.isChecked)

        var applyingRemoteSettings = false

        // Persist local toggle values and apply analytics collection immediately.
        fun saveLocalSettings(allowBackup: Boolean, allowAnalytics: Boolean) {
            prefs.edit {
                putBoolean("allow_backup", allowBackup)
                putBoolean("allow_analytics", allowAnalytics)
                remove("allow_crash_reports")
            }
            analytics.setAnalyticsCollectionEnabled(allowAnalytics)
        }

        // Sync current privacy state to Firebase and mirror backup switch to /controls.
        fun savePrivacyToFirebase(showToast: Boolean = true) {
            val ref = privacyRef

            fun syncBackupControl() {
                backupControlRef.setValue(backupSwitch.isChecked)
            }

            if (ref == null) {
                syncBackupControl()
                if (showToast) {
                    Toast.makeText(requireContext(), "Saved locally. Sign in to sync privacy settings.", Toast.LENGTH_SHORT).show()
                }
                return
            }

            val updates = mapOf(
                "allowBackup" to backupSwitch.isChecked,
                "allowAnalytics" to analyticsSwitch.isChecked,
                "updatedAt" to System.currentTimeMillis()
            )

            ref.updateChildren(updates)
                .addOnSuccessListener {
                    syncBackupControl()
                    ref.child("allowCrashReports").removeValue()
                    if (showToast) {
                        Toast.makeText(requireContext(), "Privacy settings synced", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    if (showToast) {
                        Toast.makeText(requireContext(), "Saved locally. Firebase sync failed.", Toast.LENGTH_SHORT).show()
                    }
                }
        }

        privacyRef?.get()?.addOnSuccessListener { snapshot ->
            if (!isAdded) return@addOnSuccessListener

            if (snapshot.exists()) {
                val allowBackup = snapshot.child("allowBackup").getValue(Boolean::class.java)
                    ?: backupSwitch.isChecked
                val allowAnalytics = snapshot.child("allowAnalytics").getValue(Boolean::class.java)
                    ?: analyticsSwitch.isChecked

                applyingRemoteSettings = true
                backupSwitch.isChecked = allowBackup
                analyticsSwitch.isChecked = allowAnalytics
                applyingRemoteSettings = false

                saveLocalSettings(allowBackup, allowAnalytics)
                privacyRef.child("allowCrashReports").removeValue()
                backupControlRef.setValue(allowBackup)
            } else {
                savePrivacyToFirebase(showToast = false)
            }
        }

        backupSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("allow_backup", isChecked) }
            if (applyingRemoteSettings) return@setOnCheckedChangeListener
            savePrivacyToFirebase(showToast = false)
            Toast.makeText(
                requireContext(),
                if (isChecked) "Backup enabled" else "Backup disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        analyticsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("allow_analytics", isChecked) }
            analytics.setAnalyticsCollectionEnabled(isChecked)
            if (applyingRemoteSettings) return@setOnCheckedChangeListener
            savePrivacyToFirebase(showToast = false)
            Toast.makeText(
                requireContext(),
                if (isChecked) "Analytics enabled" else "Analytics disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
