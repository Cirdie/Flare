package com.example.flare_capstone.views.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.R
import com.example.flare_capstone.views.auth.LoginActivity
import com.example.flare_capstone.views.activity.FirefighterActivity
import com.example.flare_capstone.views.auth.MainActivity
import com.example.flare_capstone.views.activity.UserActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val firestore = FirebaseFirestore.getInstance()
    private lateinit var prefManager: PrefManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        auth = FirebaseAuth.getInstance()
        prefManager = PrefManager(this)

        // ⏳ 2-second splash delay
        Handler().postDelayed({
            checkFirstTime()
        }, 2000)
    }

    // ✔ Check if first time opening the app
    private fun checkFirstTime() {
        if (prefManager.isFirstTimeLaunch()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        } else {
            checkAutoLogin()
        }
    }

    // ✔ Auto login logic SAME AS YOUR Onboard1Activity
    private fun checkAutoLogin() {
        val user = auth.currentUser

        // ❌ No logged-in user → go to MainActivity
        if (user == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        user.reload().addOnSuccessListener {

            // ❌ Not verified → force login
            if (!user.isEmailVerified) {
                Toast.makeText(this, "Please verify your email first.", Toast.LENGTH_SHORT).show()
                auth.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return@addOnSuccessListener
            }

            // 🔥 Fetch user Firestore record
            firestore.collection("users")
                .document(user.uid)
                .get()
                .addOnSuccessListener { doc ->

                    if (!doc.exists()) {
                        auth.signOut()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                        return@addOnSuccessListener
                    }

                    val status = doc.getString("status") ?: "unverified"

                    // ❌ Account pending admin verification
                    if (status != "verified") {
                        Toast.makeText(this, "Account pending verification.", Toast.LENGTH_SHORT).show()
                        auth.signOut()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                        return@addOnSuccessListener
                    }

                    // 🔥 Set verifiedAt if missing
                    if (doc.get("verifiedAt") == null) {
                        firestore.collection("users")
                            .document(user.uid)
                            .update("verifiedAt", Timestamp.now())
                    }

                    // 🔥 Firefighter emails
                    val firefighterEmails = listOf(
                        "tcwestfiresubstation@gmail.com",
                        "lafilipinafire@gmail.com",
                        "bfp_tagumcity@yahoo.com"
                    )

                    if (firefighterEmails.contains(user.email)) {
                        Toast.makeText(this, "Welcome back, Firefighter", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, FirefighterActivity::class.java))
                        finish()
                        return@addOnSuccessListener
                    }

                    // 🔥 Normal users
                    Toast.makeText(this, "Welcome back", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, UserActivity::class.java))
                    finish()
                }
        }
    }
}
