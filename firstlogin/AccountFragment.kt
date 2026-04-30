package com.example.firstlogin.fragments

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.firstlogin.MainActivity
import com.example.firstlogin.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.database.FirebaseDatabase

class AccountFragment : Fragment(R.layout.fragment_account) {
    // Setup account actions (change password + logout).
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val logoutBtn = view.findViewById<Button>(R.id.logoutBtn)
        addChangePasswordButton(logoutBtn)

        logoutBtn.setOnClickListener {
            val loginPrefs = requireContext().getSharedPreferences("loginPrefs", 0)
            val rememberMe = loginPrefs.getBoolean("rememberMe", false)
            FirebaseAuth.getInstance().signOut()
            if (!rememberMe) {
                loginPrefs.edit().clear().apply()
            } else {
                loginPrefs.edit()
                    .putBoolean("skipAutoLoginOnce", true)
                    .apply()
            }
            val intent = Intent(requireContext(), MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    // Inject "Change Password" button above logout using same style.
    private fun addChangePasswordButton(logoutBtn: Button) {
        val parent = logoutBtn.parent as? LinearLayout ?: return
        val changePasswordBtn = Button(requireContext()).apply {
            id = View.generateViewId()
            text = "Change Password"
            textSize = logoutBtn.textSize / resources.displayMetrics.scaledDensity
            setTextColor(logoutBtn.textColors)
            background = logoutBtn.background?.constantState?.newDrawable()?.mutate()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val logoutParams = logoutBtn.layoutParams as? LinearLayout.LayoutParams
                val gap = 12.dp()
                setMargins(
                    logoutParams?.leftMargin ?: 0,
                    0,
                    logoutParams?.rightMargin ?: 0,
                    gap
                )
            }
        }

        val logoutIndex = parent.indexOfChild(logoutBtn)
        if (logoutIndex >= 0) {
            parent.addView(changePasswordBtn, logoutIndex)
        } else {
            parent.addView(changePasswordBtn)
        }

        changePasswordBtn.setOnClickListener { showChangePasswordDialog() }
    }

    // Re-authenticate user, validate new password, then update Firebase Auth password.
    private fun showChangePasswordDialog() {
        val context = context ?: return
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(context, "No signed-in user", Toast.LENGTH_SHORT).show()
            return
        }
        val email = user.email
        if (email.isNullOrBlank()) {
            Toast.makeText(context, "Password change requires email/password account", Toast.LENGTH_LONG).show()
            return
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val currentPasswordInput = EditText(context).apply {
            hint = "Current password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newPasswordInput = EditText(context).apply {
            hint = "New password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmPasswordInput = EditText(context).apply {
            hint = "Confirm new password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val showPasswordsToggle = CheckBox(context).apply {
            text = "Show passwords"
        }

        container.addView(currentPasswordInput)
        container.addView(newPasswordInput)
        container.addView(confirmPasswordInput)
        container.addView(showPasswordsToggle)

        showPasswordsToggle.setOnCheckedChangeListener { _, checked ->
            val type = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            currentPasswordInput.inputType = type
            newPasswordInput.inputType = type
            confirmPasswordInput.inputType = type
            currentPasswordInput.setSelection(currentPasswordInput.text.length)
            newPasswordInput.setSelection(newPasswordInput.text.length)
            confirmPasswordInput.setSelection(confirmPasswordInput.text.length)
        }

        AlertDialog.Builder(context)
            .setTitle("Change Password")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    saveButton.setOnClickListener {
                        val currentPassword = currentPasswordInput.text.toString().trim()
                        val newPassword = newPasswordInput.text.toString().trim()
                        val confirmPassword = confirmPasswordInput.text.toString().trim()

                        if (currentPassword.isBlank() || newPassword.isBlank() || confirmPassword.isBlank()) {
                            Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (!isStrongPassword(newPassword)) {
                            Toast.makeText(
                                context,
                                "New password must be 8+ chars and include letters, numbers, and symbols",
                                Toast.LENGTH_LONG
                            ).show()
                            return@setOnClickListener
                        }
                        if (newPassword != confirmPassword) {
                            Toast.makeText(context, "New passwords do not match", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (newPassword == currentPassword) {
                            Toast.makeText(context, "New password must be different", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        saveButton.isEnabled = false
                        val credential = EmailAuthProvider.getCredential(email, currentPassword)
                        user.reauthenticate(credential)
                            .addOnSuccessListener {
                                user.updatePassword(newPassword)
                                    .addOnSuccessListener {
                                        val uid = user.uid
                                        val updates = mapOf(
                                            "passwordUpdatedAt" to System.currentTimeMillis(),
                                            "passwordAuthEmail" to email
                                        )
                                        FirebaseDatabase.getInstance()
                                            .getReference("users")
                                            .child(uid)
                                            .child("account")
                                            .updateChildren(updates)

                                        Toast.makeText(context, "Password updated", Toast.LENGTH_SHORT).show()
                                        dialog.dismiss()
                                    }
                                    .addOnFailureListener { error ->
                                        saveButton.isEnabled = true
                                        Toast.makeText(context, "Update failed: ${error.message}", Toast.LENGTH_LONG).show()
                                    }
                            }
                            .addOnFailureListener { error ->
                                saveButton.isEnabled = true
                                Toast.makeText(context, "Current password is incorrect: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                    }
                }
                dialog.show()
            }
    }

    // Enforce password complexity: letters + digits + symbols.
    private fun isStrongPassword(password: String): Boolean {
        if (password.length < 8) return false
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        return hasLetter && hasDigit && hasSymbol
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
