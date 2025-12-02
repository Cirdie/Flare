package com.example.flare_capstone.util

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.net.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.R
import com.google.firebase.database.*
import java.net.InetSocketAddress
import java.net.Socket

object NetworkUtils {

    private var alertDialog: AlertDialog? = null
    private var isDialogVisible = false

    // ✅ Checks for active local network (Wi-Fi or Mobile Data)
    fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    }

    // ✅ Performs a true connectivity check: socket ping + Firebase verification
    fun verifyFullConnectivity(
        context: Context,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())

        // Step 1: Check if device has any local network connection
        if (!isConnected(context)) {
            Log.w("NetworkUtils", "No active network detected.")
            handler.post { onDisconnected() }
            return
        }

        // Step 2: Verify real Internet access by socket ping
        Thread {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("8.8.8.8", 53), 1500)
                }
                Log.i("NetworkUtils", "Socket ping successful — real internet confirmed.")

                // Step 3: Optional Firebase connectivity (non-blocking)
                val connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected")
                connectedRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val connected = snapshot.getValue(Boolean::class.java) ?: false
                        handler.post {
                            if (connected) {
                                Log.i("NetworkUtils", "Firebase connection OK.")
                                onConnected()
                            } else {
                                // Firebase may not be ready yet — fail open
                                Log.w("NetworkUtils", "Firebase not yet synced. Internet OK, proceeding.")
                                onConnected()
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e("NetworkUtils", "Firebase connectivity check cancelled: ${error.message}")
                        handler.post { onConnected() } // Don't block the user
                    }
                })
            } catch (e: Exception) {
                Log.e("NetworkUtils", "Socket ping failed: ${e.message}")
                handler.post { onDisconnected() }
            }
        }.start()
    }

    // ✅ Custom No Internet Dialog with retry
    fun showCustomNoInternetDialog(context: Context, onRetry: (() -> Unit)? = null) {
        if (context !is AppCompatActivity || context.isFinishing || context.isDestroyed || isDialogVisible) return

        context.runOnUiThread {
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_no_internet, null)
            val retryButton = view.findViewById<Button>(R.id.btnRetry)
            val dismissButton = view.findViewById<TextView>(R.id.btnDismiss)

            alertDialog = AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(false)
                .create().apply {
                }

            retryButton.setOnClickListener {
                Log.i("NetworkUtils", "Retry clicked.")
                showTemporaryToast(context, "Checking connection...")
                verifyFullConnectivity(
                    context = context,
                    onConnected = {
                        Log.i("NetworkUtils", "Connection restored successfully.")
                        dismissNoInternetDialog()
                        onRetry?.invoke()
                    },
                    onDisconnected = {
                        Toast.makeText(context, "Still no internet. Please try again.", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            dismissButton.setOnClickListener {
                Log.i("NetworkUtils", "Dismiss clicked.")
                dismissNoInternetDialog()
            }

            try {
                alertDialog?.show()
                isDialogVisible = true
                Log.i("NetworkUtils", "Custom No-Internet Dialog shown.")
            } catch (e: WindowManager.BadTokenException) {
                Log.e("NetworkUtils", "Dialog show failed: ${e.message}")
            }
        }
    }

    // ✅ Dismiss any active no-internet dialog
    fun dismissNoInternetDialog() {
        if (isDialogVisible) {
            alertDialog?.dismiss()
            alertDialog = null
            isDialogVisible = false
            Log.i("NetworkUtils", "No-Internet dialog dismissed.")
        }
    }

    // ✅ Realtime observer: auto-hide dialog when connection is back
    fun startRealtimeNetworkObserver(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i("NetworkUtils", "Network available.")
                dismissNoInternetDialog()
            }

            override fun onLost(network: Network) {
                Log.w("NetworkUtils", "Network lost.")
                if (context is AppCompatActivity) showCustomNoInternetDialog(context)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    Log.w("NetworkUtils", "Network invalid (not validated).")
                    if (context is AppCompatActivity) showCustomNoInternetDialog(context)
                } else {
                    Log.i("NetworkUtils", "Network validated.")
                    dismissNoInternetDialog()
                }
            }
        })
    }

    // ✅ Observe realtime network state per activity
    fun observeRealtimeConnectionInActivity(
        activity: AppCompatActivity,
        onLost: () -> Unit,
        onRestored: () -> Unit
    ) {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.runOnUiThread { onRestored() }
                }
            }

            override fun onLost(network: Network) {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.runOnUiThread { onLost() }
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.runOnUiThread { onLost() }
                    }
                } else {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.runOnUiThread { onRestored() }
                    }
                }
            }
        })
    }

    private fun showTemporaryToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
