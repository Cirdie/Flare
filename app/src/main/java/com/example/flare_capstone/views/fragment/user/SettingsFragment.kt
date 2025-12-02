package com.example.flare_capstone.views.fragment.user

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.flare_capstone.views.fragment.settings.FireStationInfoActivity
import com.example.flare_capstone.views.auth.MainActivity
import com.example.flare_capstone.R
import com.example.flare_capstone.util.ThemeManager
import com.example.flare_capstone.views.fragment.settings.UserGuideActivity
import com.example.flare_capstone.databinding.FragmentSettingsBinding
import com.example.flare_capstone.views.fragment.settings.AboutAppActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        loadUserData()
        setupDarkMode()
        setupMenuButtons()
        setupLogout()
    }

    /* ============================================================
       LOAD USER PROFILE FROM FIRESTORE
       ============================================================ */
    private fun loadUserData() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Toast.makeText(requireActivity(), "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        firestore.collection("users").document(userId)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener

                val name = doc.getString("name")
                val profileBase64 = doc.getString("profile")

                binding.fullName.text = name ?: ""

                if (!profileBase64.isNullOrEmpty()) {
                    convertBase64ToBitmap(profileBase64)?.let {
                        binding.profileIcon.setImageBitmap(it)
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireActivity(), "Failed to load user data", Toast.LENGTH_SHORT).show()
            }
    }

    /* ============================================================
       DARK MODE SWITCH
       ============================================================ */
    private fun setupDarkMode() {
        val prefs = requireActivity().getSharedPreferences(ThemeManager.PREF_NAME, Context.MODE_PRIVATE)
        val darkModeEnabled = prefs.getBoolean(ThemeManager.DARK_MODE_KEY, false)

        binding.darkModeSwitch.isChecked = darkModeEnabled

        binding.darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            ThemeManager.setDarkModeEnabled(requireContext(), isChecked)
            requireActivity().recreate()
        }
    }

    /* ============================================================
       MENU BUTTONS
       ============================================================ */
    private fun setupMenuButtons() {
        binding.aboutApp.setOnClickListener {
            startActivity(Intent(requireActivity(), AboutAppActivity::class.java))
        }

        binding.userGuide.setOnClickListener {
            startActivity(Intent(requireActivity(), UserGuideActivity::class.java))
        }

        binding.fireStationInfo.setOnClickListener {
            startActivity(Intent(requireActivity(), FireStationInfoActivity::class.java))
        }
    }

    /* ============================================================
       LOGOUT
       ============================================================ */
    private fun setupLogout() {
        binding.logout.setOnClickListener {
            val dialogView = requireActivity().layoutInflater.inflate(R.layout.dialog_logout, null)
            dialogView.findViewById<ImageView>(R.id.logoImageView).setImageResource(R.drawable.ic_logo)

            AlertDialog.Builder(requireActivity())
                .setView(dialogView)
                .setPositiveButton("Yes") { _, _ ->
                    // Clear any stored notification flags
                    val prefs = requireActivity().getSharedPreferences("shown_notifications", Context.MODE_PRIVATE)
                    prefs.edit().clear().apply()
                    prefs.edit().putInt("unread_message_count", 0).apply()

                    // ✅ Set isActive = false in Firestore
                    setUserActiveStatus(false)

                    FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(requireActivity(), MainActivity::class.java))
                    Toast.makeText(requireActivity(), "You have been logged out.", Toast.LENGTH_SHORT).show()
                    requireActivity().finish()
                }
                .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
                .create()
                .show()
        }
    }


    private fun setUserActiveStatus(isActive: Boolean) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        firestore.collection("users").document(userId)
            .update(
                mapOf(
                    "isActive" to isActive,
                    "lastSeen" to System.currentTimeMillis()
                )
            )
            .addOnSuccessListener {
                // Optional log
                println("User active status updated: $isActive")
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
            }
    }


    /* ============================================================
       BASE64 -> BITMAP
       ============================================================ */
    private fun convertBase64ToBitmap(base64String: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}