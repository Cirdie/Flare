package com.example.flare_capstone.views.activity

import androidx.navigation.ui.setupWithNavController
import com.example.flare_capstone.data.model.User
import com.example.flare_capstone.util.ThemeManager
import com.example.flare_capstone.views.fragment.user.FireReportResponseActivity

class UserActivity : androidx.appcompat.app.AppCompatActivity() {

    /* ---------------- View / State ---------------- */
    internal lateinit var binding: com.example.flare_capstone.databinding.ActivityDashboardBinding
    private lateinit var database: com.google.firebase.database.DatabaseReference
    private lateinit var sharedPreferences: android.content.SharedPreferences
    private var currentUserName: String? = null
    private var user: User? = null
    private var unreadMessageCount: Int = 0

    /* ---------------- Location / Connectivity ---------------- */
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var connectivityManager: android.net.ConnectivityManager
    private var loadingDialog: androidx.appcompat.app.AlertDialog? = null

    /* ---------------- Firebase / Queries ---------------- */
    // ✅ Single station
    private val stationNode = "TagumCityCentralFireStation"
    private val responseListeners = mutableListOf<Pair<com.google.firebase.database.Query, com.google.firebase.database.ChildEventListener>>()
    private val unreadCounterListeners = mutableListOf<Pair<com.google.firebase.database.Query, com.google.firebase.database.ValueEventListener>>()

    /* ---------------- Connectivity state gates ---------------- */
    private var isNetworkValidated = false
    private var isNetworkSlow = true
    private var isInitialFirebaseReady = false

    private companion object {
        private const val CH_GENERAL = "default_channel_v2" // NEW ID
    }


    /* ---------------- Network Callback ---------------- */
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            runOnUiThread {
                isNetworkValidated = false
                isNetworkSlow = true
                showLoadingDialog("Connecting… (waiting for internet)")
            }
        }
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
            runOnUiThread {
                isNetworkValidated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                isNetworkSlow = isSlow(caps)
                maybeHideLoading()
            }
        }
        override fun onLost(network: android.net.Network) {
            runOnUiThread {
                isNetworkValidated = false
                isNetworkSlow = true
                showLoadingDialog("No internet connection")
            }
        }
    }

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        binding = com.example.flare_capstone.databinding.ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager =
            getSystemService(android.net.ConnectivityManager::class.java)


        if (!isConnected()) {
            showLoadingDialog("No internet connection")
        } else {
            showLoadingDialog("Connecting…")
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        showLoadingDialog()

        sharedPreferences = getSharedPreferences("shown_notifications", MODE_PRIVATE)
        unreadMessageCount = sharedPreferences.getInt("unread_message_count", 0)
        updateInboxBadge(unreadMessageCount)

        database = com.google.firebase.database.FirebaseDatabase.getInstance().reference

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(com.example.flare_capstone.R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)

        createNotificationChannel()

        fetchCurrentUserName { name ->
            if (name != null) {
                currentUserName = name
                updateUnreadMessageCount()
                startRealtimeUnreadCounter()
                listenForResponseMessages()
                listenForStatusChanges()
            } else {
                startRealtimeUnreadCounter()
                android.util.Log.e("UserCheck", "Failed to get current user name. Notifications will not be triggered.")
            }
            isInitialFirebaseReady = true
            runOnUiThread { maybeHideLoading() }
        }

        binding.root.postDelayed({
            if (!isInitialFirebaseReady) showLoadingDialog("Still loading data… (slow internet)")
        }, 10_000)
    }

    override fun onResume() {
        super.onResume()
        if (::database.isInitialized && user != null) {
            updateUnreadMessageCount()
        }
        maybeHideLoading()
    }

    override fun onStart() {
        super.onStart()
        // App visible → user is active
        setUserActiveStatus(true)
    }

    override fun onStop() {
        super.onStop()
        // App backgrounded → mark inactive, update lastSeen
        setUserActiveStatus(false)
    }


    override fun onDestroy() {
        super.onDestroy()
        setUserActiveStatus(false)  // mark user as inactive
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        responseListeners.forEach { (q, l) -> runCatching { q.removeEventListener(l) } }
        responseListeners.clear()
        stopRealtimeUnreadCounter()
    }


    /* =========================================================
     * Connectivity / Loading helpers
     * ========================================================= */
    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isSlow(caps: android.net.NetworkCapabilities): Boolean {
        val down = caps.linkDownstreamBandwidthKbps
        val up = caps.linkUpstreamBandwidthKbps
        if (down <= 0 || up <= 0) return true
        return down < 1500 || up < 512
    }

    private fun maybeHideLoading() {
        if (isNetworkValidated && !isNetworkSlow && isInitialFirebaseReady) {
            hideLoadingDialog()
        } else {
            val msg = when {
                !isNetworkValidated -> "Connecting… (waiting for internet)"
                isNetworkSlow -> "Slow internet connection"
                !isInitialFirebaseReady -> "Loading data…"
                else -> "Please wait"
            }
            showLoadingDialog(msg)
        }
    }

    private fun showLoadingDialog(message: String = "Please wait if internet is slow") {
        if (loadingDialog == null) {
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            val dialogView = layoutInflater.inflate(com.example.flare_capstone.R.layout.custom_loading_dialog, null)

            // 🔒 Hide the Close button for DashboardActivity
            dialogView.findViewById<android.widget.TextView>(com.example.flare_capstone.R.id.closeBtn)?.apply {
                visibility = android.view.View.GONE
                isClickable = false
            }

            builder.setView(dialogView)
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }

        if (loadingDialog?.isShowing != true) loadingDialog?.show()

        // Ensure it stays hidden even on reused dialog instances
        loadingDialog?.findViewById<android.widget.TextView>(com.example.flare_capstone.R.id.closeBtn)?.apply {
            visibility = android.view.View.GONE
            isClickable = false
        }

        loadingDialog?.findViewById<android.widget.TextView>(com.example.flare_capstone.R.id.loading_message)?.text = message
    }


    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }

    /* =========================================================
     * Notifications
     * ========================================================= */
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = getSystemService(android.app.NotificationManager::class.java)

        // Clean up the truly old one once (harmless if missing)
        runCatching { nm.deleteNotificationChannel("default_channel") }
        // Ensure fresh channel with correct sound
        runCatching { nm.deleteNotificationChannel(CH_GENERAL) }

        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val soundUri = android.net.Uri.parse("android.resource://$packageName/${com.example.flare_capstone.R.raw.message_notif}")

        val ch = _root_ide_package_.android.app.NotificationChannel(
            CH_GENERAL,
            "General Notifications",
            android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(soundUri, attrs)
            enableVibration(true)
            description = "General alerts with custom sound"
        }

        nm.createNotificationChannel(ch)
        android.util.Log.d("Notif", "Dashboard channel sound=${nm.getNotificationChannel(CH_GENERAL)?.sound}")
    }


    @androidx.annotation.RequiresPermission(android.Manifest.permission.POST_NOTIFICATIONS)
    private fun triggerNotification(
        fireStationName: String?,
        message: String?,
        messageId: String,
        incidentId: String?,
        reporterName: String?,
        title: String,
        stationNodeParam: String // kept for intent extras; always TagumCityCentralFireStation
    ) {
        val notificationId = (stationNodeParam + "::" + messageId).hashCode()
        val reportNode = defaultReportNode() // points to AllReport/FireReport

        val resultIntent = _root_ide_package_.android.content.Intent(
            this,
            FireReportResponseActivity::class.java
        ).apply {
            putExtra("INCIDENT_ID", incidentId)
            putExtra("FIRE_STATION_NAME", fireStationName)
            putExtra("NAME", reporterName)
            putExtra("fromNotification", true)
            putExtra("STATION_NODE", stationNode)             // single node
            putExtra("REPORT_NODE", reportNode)               // AllReport/FireReport by default
        }

        val pendingIntent = androidx.core.app.TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(
                _root_ide_package_.android.content.Intent(
                    this@UserActivity,
                    UserActivity::class.java
                )
            )
            addNextIntent(resultIntent)
            getPendingIntent(0, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, CH_GENERAL)
            .setSmallIcon(com.example.flare_capstone.R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        androidx.core.app.NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.POST_NOTIFICATIONS)
    private fun triggerStatusChangeNotification(
        reportId: String,
        stationNodeParam: String,
        reporterName: String,
        status: String
    ) {
        val notificationId = (stationNodeParam + "::" + reportId).hashCode()

        val resultIntent = _root_ide_package_.android.content.Intent(
            this,
            FireReportResponseActivity::class.java
        ).apply {
            putExtra("REPORT_ID", reportId)
            putExtra("STATUS", status)
            putExtra("REPORTER_NAME", reporterName)
            putExtra("STATION_NODE", stationNode)
            putExtra("REPORT_NODE", defaultReportNode())
        }

        val pendingIntent = androidx.core.app.TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(
                _root_ide_package_.android.content.Intent(
                    this@UserActivity,
                    UserActivity::class.java
                )
            )
            addNextIntent(resultIntent)
            getPendingIntent(0, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, CH_GENERAL)
            .setSmallIcon(com.example.flare_capstone.R.drawable.ic_logo)
            .setContentTitle("Status Update: $status")
            .setContentText("The status of your report has changed to $status.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        androidx.core.app.NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    private fun setUserActiveStatus(isActive: Boolean) {
        val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()

        val statusMap = mapOf(
            "isActive" to isActive,
            "lastSeen" to System.currentTimeMillis() // track last timestamp
        )

        firestore.collection("users").document(userId)
            .update(statusMap)
            .addOnSuccessListener {
                _root_ide_package_.android.util.Log.d("UserStatus", "isActive=$isActive updated with lastSeen")
            }
            .addOnFailureListener { e ->
                _root_ide_package_.android.util.Log.e("UserStatus", "Failed to update status", e)
            }
    }



    /* =========================================================
     * Firebase: Users / Messages
     * ========================================================= */
    private fun fetchCurrentUserName(callback: (String?) -> Unit) {
        val currentEmail = _root_ide_package_.com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
        if (currentEmail == null) {
            callback(null)
            return
        }

        val firestore = _root_ide_package_.com.google.firebase.firestore.FirebaseFirestore.getInstance()

        firestore.collection("users")
            .whereEqualTo("email", currentEmail.trim())
            .limit(1)
            .get()
            .addOnSuccessListener { query ->
                if (!query.isEmpty) {
                    val doc = query.documents[0]

                    val name = doc.getString("name")
                    val contact = doc.getString("contact")

                    // Convert Firestore doc to User class
                    user = User(
                        name = name ?: "",
                        email = doc.getString("email") ?: "",
                        contact = contact ?: "",
                        profile = doc.getString("profile") ?: "",
//                        address = doc.getString("address") ?: "",
//                        status = doc.getString("status") ?: "",
//                        verifiedAt = doc.getString("verifiedAt") ?: ""
                    )

                    callback(name)
                    setUserActiveStatus(true)
                } else {
                    callback(null)
                }
            }
            .addOnFailureListener {
                callback(null)
            }
    }


    private fun updateUnreadMessageCount() {
        val myContact = user?.contact?.trim().orEmpty()
        val myName = currentUserName?.trim().orEmpty()

        if (myContact.isEmpty() && myName.isEmpty()) {
            unreadMessageCount = 0
            sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
            runOnUiThread { updateInboxBadge(unreadMessageCount) }
            return
        }

        // ✅ Read under TagumCityCentralFireStation/AllReport/ResponseMessage
        val baseRef = database.child(stationNode).child("AllReport").child("ResponseMessage")
        val query: com.google.firebase.database.Query = if (myContact.isNotEmpty()) {
            baseRef.orderByChild("contact").equalTo(myContact)
        } else {
            baseRef.orderByChild("reporterName").equalTo(myName)
        }

        query.addListenerForSingleValueEvent(object :
            com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                var total = 0
                snapshot.children.forEach { msg ->
                    val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: false
                    if (!isRead) total++
                }
                unreadMessageCount = total
                sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
                runOnUiThread { updateInboxBadge(unreadMessageCount) }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                runOnUiThread { updateInboxBadge(unreadMessageCount) }
            }
        })
    }

    private fun listenForResponseMessages() {
        val prefs = getSharedPreferences("user_preferences", MODE_PRIVATE)
        if (!prefs.getBoolean("notifications_enabled", true)) return

        val myName = currentUserName?.trim().orEmpty()
        val myContact = user?.contact?.trim().orEmpty()
        if (myName.isEmpty() && myContact.isEmpty()) return

        // ✅ Listen under AllReport/ResponseMessage
        val baseRef = database.child(stationNode).child("AllReport").child("ResponseMessage")
        val query: com.google.firebase.database.Query = if (myContact.isNotEmpty()) {
            baseRef.orderByChild("contact").equalTo(myContact)
        } else {
            baseRef.orderByChild("reporterName").equalTo(myName)
        }

        val listener = object : com.google.firebase.database.ChildEventListener {
            @androidx.annotation.RequiresPermission(_root_ide_package_.android.Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildAdded(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                val messageId = snapshot.key ?: return
                val uniqueKey = "$stationNode::$messageId"

                val isRead = snapshot.child("isRead").getValue(Boolean::class.java) ?: false
                if (isRead || isNotificationShown(uniqueKey)) return

                val fireStationName = snapshot.child("fireStationName").getValue(String::class.java)
                val responseMessage = snapshot.child("responseMessage").getValue(String::class.java)
                val incidentId = snapshot.child("incidentId").getValue(String::class.java)
                val reporterName = snapshot.child("reporterName").getValue(String::class.java)

                // Only notify for my messages
                if (reporterName == myName) {
                    unreadMessageCount++
                    sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
                    runOnUiThread { updateInboxBadge(unreadMessageCount) }

                    triggerNotification(
                        fireStationName = fireStationName,
                        message = responseMessage,
                        messageId = messageId,
                        incidentId = incidentId,
                        reporterName = reporterName,
                        title = "New Response from $fireStationName",
                        stationNodeParam = stationNode
                    )

                    markNotificationAsShown(uniqueKey)
                }
            }

            override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                updateUnreadMessageCount()
            }

            override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) {}
            override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        }

        query.addChildEventListener(listener)
        responseListeners += query to listener
    }

    private fun isNotificationShown(key: String): Boolean =
        sharedPreferences.getBoolean(key, false)

    private fun markNotificationAsShown(key: String) {
        sharedPreferences.edit().putBoolean(key, true).apply()
    }

    /* =========================================================
     * Firebase: Status Changes (watch FireReport under AllReport)
     * ========================================================= */
    private fun listenForStatusChanges() {
        val myName = currentUserName?.trim().orEmpty()
        if (myName.isEmpty()) return

        val baseRef = database.child(stationNode).child("AllReport").child("FireReport")

        baseRef.addChildEventListener(object : com.google.firebase.database.ChildEventListener {
            @androidx.annotation.RequiresPermission(_root_ide_package_.android.Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildAdded(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                val reportId = snapshot.key ?: return
                val reporterName = snapshot.child("reporterName").getValue(String::class.java) ?: return
                val status = snapshot.child("status").getValue(String::class.java)

                if (reporterName == myName && status == "Ongoing") {
                    triggerStatusChangeNotification(reportId, stationNode, reporterName, status ?: "Ongoing")
                }
            }

            @androidx.annotation.RequiresPermission(_root_ide_package_.android.Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                val reportId = snapshot.key ?: return
                val reporterName = snapshot.child("reporterName").getValue(String::class.java) ?: return
                val status = snapshot.child("status").getValue(String::class.java)

                if (reporterName == myName && status == "Ongoing") {
                    triggerStatusChangeNotification(reportId, stationNode, reporterName, status ?: "Ongoing")
                }
            }

            override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) {}
            override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })
    }

    /* =========================================================
     * Utilities
     * ========================================================= */
    private fun defaultReportNode(): String {
        // When opening the conversation view from a notification,
        // default to FireReport under AllReport (your reader can switch as needed).
        return "AllReport/FireReport"
    }

    private fun updateInboxBadge(count: Int) {
        val badge = binding.bottomNavigation.getOrCreateBadge(_root_ide_package_.com.example.flare_capstone.R.id.inboxFragment)
        badge.isVisible = count > 0
        badge.number = count
        badge.maxCharacterCount = 3
    }

    private fun startRealtimeUnreadCounter() {
        if (unreadCounterListeners.isNotEmpty()) return

        val myContact = user?.contact?.trim().orEmpty()
        val myName = currentUserName?.trim().orEmpty()
        if (myContact.isEmpty() && myName.isEmpty()) {
            updateInboxBadge(0)
            return
        }

        // ✅ Watch AllReport/ResponseMessage for unread
        val baseRef = database.child(stationNode).child("AllReport").child("ResponseMessage")
        val query: com.google.firebase.database.Query = if (myContact.isNotEmpty()) {
            baseRef.orderByChild("contact").equalTo(myContact)
        } else {
            baseRef.orderByChild("reporterName").equalTo(myName)
        }

        val v = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                var count = 0
                snapshot.children.forEach { msg ->
                    val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: false
                    if (!isRead) count++
                }
                unreadMessageCount = count
                sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
                runOnUiThread { updateInboxBadge(unreadMessageCount) }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                runOnUiThread { updateInboxBadge(unreadMessageCount) }
            }
        }

        query.addValueEventListener(v)
        unreadCounterListeners += query to v
    }

    private fun stopRealtimeUnreadCounter() {
        unreadCounterListeners.forEach { (q, v) ->
            runCatching { q.removeEventListener(v) }
        }
        unreadCounterListeners.clear()
    }
}