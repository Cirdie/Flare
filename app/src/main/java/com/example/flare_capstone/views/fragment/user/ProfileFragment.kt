package com.example.flare_capstone.views.fragment.user

import android.app.AlertDialog
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
import com.example.flare_capstone.views.fragment.profile.EditProfileActivity
import com.example.flare_capstone.views.auth.MainActivity
import com.example.flare_capstone.views.fragment.profile.MyReportActivity
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.FragmentProfileBinding
import com.example.flare_capstone.views.auth.ChangePasswordActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadUserProfile()

        binding.editProfile.setOnClickListener {
            startActivity(Intent(requireActivity(), EditProfileActivity::class.java))
        }

        binding.changePassword.setOnClickListener {
            startActivity(Intent(requireActivity(), ChangePasswordActivity::class.java))
        }

        binding.myReport.setOnClickListener {
            startActivity(Intent(requireActivity(), MyReportActivity::class.java))
        }

        binding.logout.setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun loadUserProfile() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(requireActivity(), "User not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        firestore.collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Toast.makeText(requireActivity(), "User data missing in Firestore", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val fullName = doc.getString("name")
                val email = doc.getString("email")
                val role = doc.getString("role")
                val profileBase64 = doc.getString("profile")

                binding.fullName.text = fullName ?: "No Name"
//                binding.emailText.text = email ?: "No Email"
//                binding.roleText.text = role ?: "User"

                // Load profile image if exists
                if (!profileBase64.isNullOrEmpty()) {
                    val bitmap = convertBase64ToBitmap(profileBase64)
                    if (bitmap != null) binding.profileIcon.setImageBitmap(bitmap)
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireActivity(), "Error loading profile: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showLogoutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_logout, null)
        val logoImageView = dialogView.findViewById<ImageView>(R.id.logoImageView)
        logoImageView.setImageResource(R.drawable.ic_logo)

        AlertDialog.Builder(requireActivity())
            .setView(dialogView)
            .setPositiveButton("Yes") { _, _ ->
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(requireActivity(), MainActivity::class.java))
                Toast.makeText(requireActivity(), "You have been logged out.", Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
            .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    private fun convertBase64ToBitmap(base64String: String): Bitmap? {
        return try {
            val decoded = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}