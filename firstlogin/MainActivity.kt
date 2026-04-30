package com.example.firstlogin

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException

class MainActivity : AppCompatActivity() {

    // ===== AUTH + UI STATE =====
    private lateinit var auth: FirebaseAuth
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var loginBtn: Button
    private lateinit var footerStatusText: TextView
    private var defaultFooterText: String = ""

    // Entry screen: login, signup, remember-me, forgot-password.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        auth = FirebaseAuth.getInstance()
        sharedPreferences = getSharedPreferences("loginPrefs", MODE_PRIVATE)

        if (auth.currentUser != null) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        val emailInput = findViewById<EditText>(R.id.usernameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        loginBtn = findViewById(R.id.loginButton)
        val signUpBtn = findViewById<Button>(R.id.regInput)
        val rememberMe = findViewById<CheckBox>(R.id.rememberMeCheckbox)
        val showPassword = findViewById<CheckBox>(R.id.showPasswordCheckbox)
        footerStatusText = findViewById(R.id.textView)
        defaultFooterText = footerStatusText.text.toString()
        val rootLayout = findViewById<ConstraintLayout>(R.id.main)

        val forgotPasswordText = TextView(this).apply {
            id = View.generateViewId()
            text = "Forgot password?"
            textSize = 13f
            setTextColor(Color.parseColor("#0A9F77"))
        }
        rootLayout.addView(
            forgotPasswordText,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToBottom = signUpBtn.id
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = (10 * resources.displayMetrics.density).toInt()
            }
        )

        val savedEmail = sharedPreferences.getString("email", "") ?: ""
        val savedPassword = sharedPreferences.getString("password", "") ?: ""
        val shouldRemember = sharedPreferences.getBoolean("rememberMe", false)
        val skipAutoLoginOnce = sharedPreferences.getBoolean("skipAutoLoginOnce", false)

        emailInput.setText(savedEmail)
        passwordInput.setText(savedPassword)
        rememberMe.isChecked = shouldRemember

        // Shared sign-in flow used by manual login and auto-login.
        fun signIn(email: String, password: String, autoLogin: Boolean = false) {
            if (!isInternetAvailable()) {
                if (!autoLogin) {
                    Toast.makeText(this, "Offline: Connect to internet to login", Toast.LENGTH_SHORT).show()
                }
                updateNetworkUi()
                return
            }

            if (!autoLogin && (email.isEmpty() || password.isEmpty())) {
                Toast.makeText(this, "Email and password required", Toast.LENGTH_SHORT).show()
                return
            }

            loginBtn.isEnabled = false
            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    loginBtn.isEnabled = true
                    if (task.isSuccessful) {
                        sharedPreferences.edit()
                            .putBoolean("isLoggedIn", true)
                            .putBoolean("rememberMe", rememberMe.isChecked)
                            .putBoolean("skipAutoLoginOnce", false)
                            .apply()

                        if (rememberMe.isChecked) {
                            sharedPreferences.edit()
                                .putString("email", email)
                                .putString("password", password)
                                .apply()
                        } else {
                            sharedPreferences.edit()
                                .remove("email")
                                .remove("password")
                                .apply()
                        }

                        if (!autoLogin) {
                            Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show()
                        }
                        startActivity(Intent(this, HomeActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(
                            this,
                            if (autoLogin) "Auto sign-in failed. Please login again."
                            else task.exception?.message ?: "Login failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        if (!isInternetAvailable()) {
            Toast.makeText(this, "Offline mode: Login unavailable", Toast.LENGTH_SHORT).show()
        }

        if (
            shouldRemember &&
            !skipAutoLoginOnce &&
            savedEmail.isNotEmpty() &&
            savedPassword.isNotEmpty() &&
            isInternetAvailable()
        ) {
            signIn(savedEmail, savedPassword, autoLogin = true)
        }

        loginBtn.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()
            signIn(email, password)
        }

        signUpBtn.setOnClickListener {
            startActivity(Intent(this, LoginUi::class.java))
        }

        showPassword.setOnCheckedChangeListener { _, isChecked ->
            passwordInput.inputType = if (isChecked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            passwordInput.setSelection(passwordInput.text.length)
        }

        forgotPasswordText.setOnClickListener {
            if (!isInternetAvailable()) {
                Toast.makeText(this, "Offline: Connect to internet to reset password", Toast.LENGTH_SHORT).show()
                updateNetworkUi()
                return@setOnClickListener
            }

            val email = emailInput.text.toString().trim()
            if (email.isBlank()) {
                Toast.makeText(this, "Enter your email first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Enter a valid email address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            forgotPasswordText.isEnabled = false
            auth.sendPasswordResetEmail(email)
                .addOnSuccessListener {
                    Toast.makeText(
                        this,
                        "Reset link sent to $email. Check inbox/spam/promotions.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .addOnFailureListener { error ->
                    val message = when ((error as? FirebaseAuthException)?.errorCode) {
                        "ERROR_USER_NOT_FOUND" -> "No existing account for this email"
                        "ERROR_INVALID_EMAIL" -> "Enter a valid email address"
                        else -> error.message ?: "Could not send reset email"
                    }
                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
                .addOnCompleteListener {
                    forgotPasswordText.isEnabled = true
                }
        }

        updateNetworkUi()
    }

    // Refresh login availability when activity returns to foreground.
    override fun onResume() {
        super.onResume()
        updateNetworkUi()
    }

    private fun isInternetAvailable(): Boolean = NetworkState.isInternetAvailable(this)

    // Disable login actions while offline and show status hint.
    private fun updateNetworkUi() {
        if (!::loginBtn.isInitialized || !::footerStatusText.isInitialized) return

        val online = isInternetAvailable()
        loginBtn.isEnabled = online
        loginBtn.alpha = if (online) 1f else 0.6f

        if (online) {
            footerStatusText.text = defaultFooterText
            footerStatusText.setTextColor(Color.parseColor("#0D0D0D"))
        } else {
            footerStatusText.text = "Offline mode: Connect to internet to login"
            footerStatusText.setTextColor(Color.parseColor("#B00020"))
        }
    }
}
