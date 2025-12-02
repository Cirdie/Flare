package com.example.flare_capstone.util

import android.content.Context
import android.util.Log
import com.example.flare_capstone.data.model.User
import com.example.flare_capstone.data.database.UserSession
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

object FirebaseHelper {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    // Fetch user information from Firestore
    fun getUser(uid: String, onSuccess: (User) -> Unit, onFailure: () -> Unit) {
        val userDocRef = firestore.collection("users").document(uid)

        userDocRef.get().addOnSuccessListener { doc ->
            if (!doc.exists()) {
                onFailure()
                return@addOnSuccessListener
            }

            // Retrieve user info from Firestore
            val info = doc.get("information") as? Map<String, Any> ?: return@addOnSuccessListener onFailure()

            val user = User(
                name = info["fullName"] as? String,
                email = info["email"] as? String,
                contact = info["phoneNumber"] as? String,
                profile = info["profilePicUrl"] as? String,  // Assuming profile picture URL
                userDocId = uid
            )

            // Set current user in UserSession
            UserSession.setCurrentUser(user)
            onSuccess(user)
        }.addOnFailureListener {
            onFailure()
        }
    }

    // Check if network is available
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }



    // Save the user's FCM token
    fun saveUserFcmToken(uid: String, onSuccess: () -> Unit = {}, onFailure: (Exception) -> Unit = {}) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            FirebaseDatabase.getInstance()
                .getReference("users")
                .child(uid)
                .child("fcmToken")
                .setValue(token)
                .addOnSuccessListener {
                    Log.d("FirebaseHelper", "✅ FCM token saved.")
                    onSuccess()
                }
                .addOnFailureListener {
                    Log.e("FirebaseHelper", "❌ Failed to save FCM token: ${it.message}")
                    onFailure(it)
                }
        }
    }

    // Get the current logged-in user from Firebase Auth
    fun getCurrentUser(): User? {
        val user = auth.currentUser ?: return null
        val uid = user.uid
        // Retrieve user info from Firestore
        val docRef = firestore.collection("users").document(uid)
        docRef.get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val info = doc.get("information") as? Map<String, Any> ?: return@addOnSuccessListener
                val currentUser = User(
                    name = info["fullName"] as? String,
                    email = info["email"] as? String,
                    contact = info["phoneNumber"] as? String,
                    profile = info["profilePicUrl"] as? String,  // Assuming profile picture URL
                    userDocId = uid
                )
                UserSession.setCurrentUser(currentUser)
            }
        }
        return null
    }
}
