package com.example.flare_capstone.views.auth

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.flare_capstone.data.database.AppDatabase
import com.example.flare_capstone.databinding.FragmentMainBinding
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.flare_capstone.R

class MainFragment : Fragment(R.layout.fragment_main) {

    private lateinit var binding: FragmentMainBinding
    private lateinit var db: AppDatabase

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = FragmentMainBinding.bind(view)
        db = AppDatabase.getDatabase(requireContext())

        if (isInternetAvailable()) uploadPendingReports()

        binding.loginButton.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_loginFragment)
        }

        binding.stationContact.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_fireStationInfoFragment)
        }

        binding.registerButton.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_registerFragment)
        }

        binding.smsReport.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_smsFragment)
        }

        // Remove or comment out these if not using Navigation Component
        // binding.logo.setOnClickListener {
        //     startActivity(Intent(requireContext(), Onboard1Activity::class.java))
        // }

        // binding.onboard.setOnClickListener {
        //     startActivity(Intent(requireContext(), OnboardingActivity::class.java))
        // }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = cm.activeNetworkInfo
        return info != null && info.isConnected
    }

    // Map display name -> station node only; child is "SmsReport"
    private fun stationNodeFor(name: String): String? {
        val n = name.trim().lowercase()
        return when {
            "mabini" in n -> "MabiniFireStation"
            "canocotan" in n -> "CanocotanFireStation"
            "la filipina" in n || "lafilipina" in n -> "LaFilipinaFireStation"
            else -> null
        }
    }

    // Push pending SmsReport under <StationNode>/SmsReport
    private fun uploadPendingReports() {
        val dao = db.reportDao()
        val dbRef = FirebaseDatabase.getInstance().reference

        CoroutineScope(Dispatchers.IO).launch {
            val pending = dao.getPendingReports()
            for (report in pending) {
                val stationNode = stationNodeFor(report.fireStationName)

                val reportMap = mapOf(
                    "name" to report.name,
                    "location" to report.location,
                    "fireReport" to report.fireReport,
                    "date" to report.date,
                    "time" to report.time,
                    "latitude" to report.latitude,
                    "longitude" to report.longitude,
                    "fireStationName" to report.fireStationName,
                    "status" to "sent"
                )

                val task = if (stationNode != null) {
                    dbRef.child(stationNode).child("SmsReport").push().setValue(reportMap)
                } else {
                    dbRef.child("SmsReport").push().setValue(reportMap)
                }

                task
                    .addOnSuccessListener {
                        CoroutineScope(Dispatchers.IO).launch { dao.deleteReport(report.id) }
                    }
                    .addOnFailureListener {
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Failed to sync report: ${report.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }
}