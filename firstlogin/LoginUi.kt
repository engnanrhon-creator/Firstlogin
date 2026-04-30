package com.example.firstlogin

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class LoginUi : AppCompatActivity() {

    // ===== AUTH + SCREEN STATE =====
    private lateinit var auth: FirebaseAuth
    private lateinit var signUpButton: Button
    private lateinit var offlineStatus: TextView

    // Registration screen: validates inputs, creates account, then routes to sign-in.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_ui)

        auth = FirebaseAuth.getInstance()

        val rootLayout = findViewById<ConstraintLayout>(R.id.main)
        val fullnameInput = findViewById<EditText>(R.id.fullnameInput)
        val emailInput = findViewById<EditText>(R.id.emailInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        signUpButton = findViewById(R.id.loginButton)
        val signInText = findViewById<TextView>(R.id.signInText)
        val backButton = findViewById<ImageView>(R.id.btnBack)
        val showPassword = CheckBox(this).apply {
            id = View.generateViewId()
            text = "Show password"
            textSize = 13f
            setTextColor(Color.parseColor("#777777"))
        }
        val fieldWidth = (292 * resources.displayMetrics.density).toInt()
        val topSpacing = (4 * resources.displayMetrics.density).toInt()
        rootLayout.addView(
            showPassword,
            ConstraintLayout.LayoutParams(fieldWidth, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                topToBottom = passwordInput.id
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = topSpacing
            }
        )
        offlineStatus = TextView(this).apply {
            id = View.generateViewId()
            textSize = 13f
            setTextColor(Color.parseColor("#B00020"))
            visibility = View.GONE
        }
        rootLayout.addView(
            offlineStatus,
            ConstraintLayout.LayoutParams(fieldWidth, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                topToBottom = showPassword.id
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = (2 * resources.displayMetrics.density).toInt()
            }
        )
        signUpButton.layoutParams = (signUpButton.layoutParams as ConstraintLayout.LayoutParams).apply {
            topToBottom = offlineStatus.id
            topMargin = (8 * resources.displayMetrics.density).toInt()
        }

        backButton.setOnClickListener { finish() }

        signInText.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        showPassword.setOnCheckedChangeListener { _, isChecked ->
            passwordInput.inputType = if (isChecked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            passwordInput.setSelection(passwordInput.text.length)
        }

        signUpButton.setOnClickListener {
            if (!isInternetAvailable()) {
                Toast.makeText(this, "Offline: Connect to internet to sign up", Toast.LENGTH_SHORT).show()
                updateNetworkUi()
                return@setOnClickListener
            }

            val fullName = fullnameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (fullName.isEmpty() || email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isStrongPassword(password)) {
                Toast.makeText(
                    this,
                    "Password must be 8+ chars and include letters, numbers, and symbols",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {

                        // Keep user flow consistent: after signup, require explicit sign-in.
                        auth.signOut()

                        Toast.makeText(this, "Account created! Please sign in.", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()

                    } else {
                        Toast.makeText(
                            this,
                            task.exception?.message ?: "Sign up failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        updateNetworkUi()
    }

    override fun onResume() {
        super.onResume()
        updateNetworkUi()
    }

    // Enforce password complexity: letters + digits + symbols.
    private fun isStrongPassword(password: String): Boolean {
        if (password.length < 8) return false
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        return hasLetter && hasDigit && hasSymbol
    }

    private fun isInternetAvailable(): Boolean = NetworkState.isInternetAvailable(this)

    // Disable signup action while offline and show status hint.
    private fun updateNetworkUi() {
        if (!::signUpButton.isInitialized || !::offlineStatus.isInitialized) return

        val online = isInternetAvailable()
        signUpButton.isEnabled = online
        signUpButton.alpha = if (online) 1f else 0.6f
        offlineStatus.visibility = if (online) View.GONE else View.VISIBLE
        offlineStatus.text = if (online) "" else "Offline mode: Sign up unavailable"
    }
}
