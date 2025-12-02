package com.example.flare_capstone.views.fragment.settings

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.FragmentFireStationInformationBinding
import com.example.flare_capstone.views.auth.MainActivity

class FireStationInfoFragment : Fragment(R.layout.fragment_fire_station_information) {

    private lateinit var binding: FragmentFireStationInformationBinding
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activity?.runOnUiThread {
                hideLoadingDialog()
            }
        }

        override fun onLost(network: Network) {
            activity?.runOnUiThread {
                showLoadingDialog("No internet connection")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentFireStationInformationBinding.inflate(inflater, container, false)

        connectivityManager = activity?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val fromReport = arguments?.getBoolean("fromReport", false) ?: false

        // Check initial connection
        if (!isConnected()) {
            showLoadingDialog("No internet connection")
        } else {
            hideLoadingDialog()
        }

        // Register network callback
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        binding.back.setOnClickListener {
            if (fromReport) {
                val intent = Intent(requireContext(), MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
            } else {
                requireActivity().onBackPressed()
            }
        }

        return binding.root
    }

    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showLoadingDialog(message: String = "Please wait if internet is slow") {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(requireContext())
            val inflater = layoutInflater
            val dialogView = inflater.inflate(R.layout.custom_loading_dialog, null)
            builder.setView(dialogView)
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
        loadingDialog?.findViewById<TextView>(R.id.loading_message)?.text = message
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
