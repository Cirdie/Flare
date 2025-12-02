package com.example.flare_capstone.views.fragment.user

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flare_capstone.util.FetchBarangayAddressTask
import com.example.flare_capstone.R
import com.example.flare_capstone.util.ThemeManager
import com.example.flare_capstone.data.model.FireReport
import com.example.flare_capstone.data.model.User
import com.example.flare_capstone.databinding.ActivityFireLevelBinding
import com.example.flare_capstone.views.activity.UserActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FireLevelActivity : AppCompatActivity() {

    /* ---------------- View / Firebase / Location ---------------- */
    private lateinit var binding: ActivityFireLevelBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    /* ---------------- CameraX ---------------- */
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var capturedFile: File? = null
    private var capturedOnce = false
    private val CAMERA_PERMISSION_REQUEST_CODE = 1002

    /* ---------------- Location State ---------------- */
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    /* ---------------- Connectivity ---------------- */
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    /* ---------------- Tagum fence ---------------- */
    private val TAGUM_CENTER_LAT = 7.447725
    private val TAGUM_CENTER_LON = 125.804150
    private val TAGUM_RADIUS_METERS = 11_000f

    private var isResolvingLocation = false
    private var locationConfirmed = false
    private var readableAddress: String? = null
    private var lastReportTime: Long = 0

    /* -------- Location-confirmation dialog (non-dismissible) --- */
    private var locatingDialog: AlertDialog? = null
    private var locatingDialogMessage: TextView? = null
    private var isMyLocation: Boolean = true // default to My Location

    private val options = listOf(
        "My Location",      // default
        "Not My Location"   // user wants to paste a link
    )

    /* ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityFireLevelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Connectivity
        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Location
        beginLocationConfirmation()
        checkPermissionsAndGetLocation()

        // Camera
        checkCameraPermissionAndStart()

//        // Dropdown from DB (CanocotanFireStation/ManageApplication/FireReport/Option)
//        populateDropdownFromDB()

        populateDropdownStatic()
        populateLocationSourceDropdown()


        // Buttons
        binding.cancelButton.setOnClickListener {
            startActivity(Intent(this, UserActivity::class.java))
            finish()
        }
        binding.btnCapture.setOnClickListener {
            if (capturedOnce) retakePhoto() else captureOnce()
        }
        binding.sendButton.setOnClickListener { showSendConfirmationDialog() }

        // Link input listener
        binding.locationLinkInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pastedLocation = s.toString().trim()
                if (pastedLocation.isBlank()) return

                if (!isValidGoogleMapsLink(pastedLocation)) {
                    readableAddress = null
                    Toast.makeText(this@FireLevelActivity, "Please enter a valid Google Maps link.", Toast.LENGTH_SHORT).show()
                    return
                }

                // Only now start location confirmation
                beginLocationConfirmation("Getting Exact Location…")

                if (pastedLocation.contains("maps.app.goo.gl")) {
                    resolveShortLink(pastedLocation) { resolvedUrl ->
                        runOnUiThread {
                            if (resolvedUrl != null) handleResolvedUrl(resolvedUrl)
                            else {
                                readableAddress = null
                                endLocationConfirmation(false, "Could not resolve short link")
                            }
                        }
                    }
                } else {
                    handleResolvedUrl(pastedLocation)
                }
            }
        })

        // FCM
        FirebaseMessaging.getInstance().subscribeToTopic("all")
    }


    private fun getUserLocationAndFetchAddress() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                latitude = loc.latitude
                longitude = loc.longitude
                FetchBarangayAddressTask(this, latitude, longitude).execute()
            } else {
                // Fallback: request updates
                requestLocationUpdates()
            }
        }
    }

    private fun handleResolvedUrl(url: String) {
        val coords = extractCoordinatesFromUrl(url)
        if (coords != null) {
            latitude = coords.first
            longitude = coords.second
            FetchBarangayAddressTask(this, latitude, longitude).execute()
        } else {
            endLocationConfirmation(false, "Could not parse coordinates from link")
        }
    }

    private fun resolveShortLink(url: String, callback: (String?) -> Unit) {
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connect()
                val redirected = conn.getHeaderField("Location")
                conn.disconnect()
                callback(redirected)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        }.start()
    }



    override fun onDestroy() {
        super.onDestroy()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        cameraExecutor.shutdown()
        locatingDialog?.dismiss()
        locatingDialog = null
        locatingDialogMessage = null
    }

    /* ======================== Connectivity ===================== */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                hideLoadingDialog()
                if (isResolvingLocation && !locationConfirmed) {
                    updateLocatingDialog("Confirming location…")
                }
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                showLoadingDialog("No internet connection")
                if (isResolvingLocation && !locationConfirmed) {
                    updateLocatingDialog("Waiting for internet…")
                }
            }
        }
    }

    private fun isConnected(): Boolean {
        val n = connectivityManager.activeNetwork ?: return false
        val c = connectivityManager.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showLoadingDialog(message: String) {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val view = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
            builder.setView(view).setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
        loadingDialog?.findViewById<TextView>(R.id.loading_message)?.text = message
    }
    private fun hideLoadingDialog() { loadingDialog?.dismiss() }

    /* ======================== Permissions ====================== */
    private fun checkPermissionsAndGetLocation() {
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk && coarseOk) requestLocationUpdates()
        else ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE)
    }
    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        when (code) {
            LOCATION_PERMISSION_REQUEST_CODE ->
                if (r.all { it == PackageManager.PERMISSION_GRANTED }) requestLocationUpdates()
                else Toast.makeText(this, "Location permission needed.", Toast.LENGTH_SHORT).show()
            CAMERA_PERMISSION_REQUEST_CODE ->
                if (r.all { it == PackageManager.PERMISSION_GRANTED }) startCameraPreview()
                else Toast.makeText(this, "Camera permission needed.", Toast.LENGTH_SHORT).show()
        }
    }

//
//
//    /* =================== Dropdown from Realtime DB ============== */
//    private fun populateDropdownFromDB() {
//        val db = FirebaseDatabase.getInstance().reference
//            .child("TagumCityCentralFireStation")
//            .child("ManageApplication")
//            .child("FireReport")
//            .child("Option")
//
//        db.addListenerForSingleValueEvent(object : ValueEventListener {
//            override fun onDataChange(snapshot: DataSnapshot) {
//                // Case 1: single comma-separated string
//                val asString = snapshot.getValue(String::class.java)
//                if (!asString.isNullOrBlank()) {
//                    val items = asString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
//                    val adapter = ArrayAdapter(this@FireLevelActivity, android.R.layout.simple_list_item_1, items)
//                    binding.emergencyDropdown.setAdapter(adapter)
//                    binding.emergencyDropdown.setOnClickListener { binding.emergencyDropdown.showDropDown() }
//                    return
//                }
//                // Case 2: list/map children
//                if (snapshot.exists() && snapshot.childrenCount > 0) {
//                    val list = mutableListOf<String>()
//                    snapshot.children.forEach { child ->
//                        child.getValue(String::class.java)?.trim()?.takeIf { it.isNotEmpty() }?.let(list::add)
//                    }
//                    if (list.isNotEmpty()) {
//                        val adapter = ArrayAdapter(this@FireLevelActivity, android.R.layout.simple_list_item_1, list)
//                        binding.emergencyDropdown.setAdapter(adapter)
//                        binding.emergencyDropdown.setOnClickListener { binding.emergencyDropdown.showDropDown() }
//                    } else {
//                        Toast.makeText(this@FireLevelActivity, "No options found.", Toast.LENGTH_SHORT).show()
//                    }
//                } else {
//                    Toast.makeText(this@FireLevelActivity, "Option node is empty.", Toast.LENGTH_SHORT).show()
//                }
//            }
//            override fun onCancelled(error: DatabaseError) {
//                Toast.makeText(this@FireLevelActivity, "Failed to load options: ${error.message}", Toast.LENGTH_SHORT).show()
//            }
//        })
//    }

    /** Static dropdown list for fire report types */
    private fun populateDropdownStatic() {
        val fireTypes = listOf(
            "House on Fire",
            "Post on Fire",
            "Vehicle on Fire",
            "Building on Fire",
            "Grass Fire",
            "Forest Fire",
            "Electrical Fire",
            "Garbage Fire"
        )

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            fireTypes
        )

        binding.emergencyDropdown.setAdapter(adapter)
        binding.emergencyDropdown.setOnClickListener { binding.emergencyDropdown.showDropDown() }
    }


    /* ========================= CameraX ========================= */
    private fun startCameraPreview() {
        val providerFuture = ProcessCameraProvider.Companion.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera start failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureOnce() {
        val ic = imageCapture ?: return
        val file = File.createTempFile("fire_", ".jpg", cacheDir)
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()

        ic.takePicture(opts, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { Toast.makeText(this@FireLevelActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                capturedFile = file
                capturedOnce = true
                runOnUiThread {
                    binding.cameraPreview.visibility = View.GONE
                    binding.capturedPhoto.visibility = View.VISIBLE
                    binding.capturedPhoto.setImageURI(Uri.fromFile(file))
                    binding.btnCapture.text = "Retake"
                }
            }
        })
    }

    private fun retakePhoto() {
        try { capturedFile?.delete() } catch (_: Exception) {}
        capturedFile = null
        capturedOnce = false
        binding.capturedPhoto.setImageDrawable(null)
        binding.capturedPhoto.visibility = View.GONE
        binding.cameraPreview.visibility = View.VISIBLE
        startCameraPreview()
        binding.btnCapture.text = "Capture"
    }

    /* ========================= Location ======================== */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                latitude = it.latitude
                longitude = it.longitude
                fusedLocationClient.removeLocationUpdates(this)
                updateLocatingDialog("Getting Exact Location…")
                FetchBarangayAddressTask(this@FireLevelActivity, latitude, longitude).execute()
            }
        }
    }

    private fun isWithinTagumByDistance(lat: Double, lon: Double): Boolean {
        val res = FloatArray(1)
        Location.distanceBetween(lat, lon, TAGUM_CENTER_LAT, TAGUM_CENTER_LON, res)
        return res[0] <= TAGUM_RADIUS_METERS
    }
    private fun looksLikeTagum(text: String?) = !text.isNullOrBlank() && text.contains("tagum", ignoreCase = true)

    fun handleFetchedAddress(address: String?) {
        val cleaned = address?.trim().orEmpty()
        val ok = looksLikeTagum(cleaned) || isWithinTagumByDistance(latitude, longitude)
        readableAddress = when {
            cleaned.isNotBlank() -> cleaned
            ok -> "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude"
            else -> ""
        }
        if (ok) endLocationConfirmation(true, "Location confirmed${if (!readableAddress.isNullOrBlank()) ": $readableAddress" else ""}")
        else endLocationConfirmation(false, "Outside Tagum area. You can't submit a report.")
    }

    private fun beginLocationConfirmation(hint: String = "Confirming location…") {
        isResolvingLocation = true
        locationConfirmed = false
        showLocatingDialog(hint) // non-dismissible modal
    }

    private fun endLocationConfirmation(success: Boolean, message: String) {
        isResolvingLocation = false
        locationConfirmed = success
        hideLocatingDialog()
        if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /* ============ Location dialog helpers (non-dismissible) ==== */
    private fun showLocatingDialog(initialMessage: String) {
        if (locatingDialog?.isShowing == true) {
            updateLocatingDialog(initialMessage)
            return
        }
        val v = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
        locatingDialogMessage = v.findViewById(R.id.loading_message)
        locatingDialogMessage?.text = initialMessage

        locatingDialog = AlertDialog.Builder(this)
            .setView(v)
            .setCancelable(false)
            .create().apply {
                setCanceledOnTouchOutside(false)
                show()
            }


        // 👇 Add this part — find the Close TextView and handle click
        // 🔻 Handle "Close" click: dismiss dialog + finish activity
        val closeText = v.findViewById<TextView>(R.id.close_button)
        closeText?.setOnClickListener {
            locatingDialog?.dismiss()
            locatingDialog = null
            finish() // 👈 close FireLevelActivity
        }

    }

    private fun updateLocatingDialog(message: String) {
        locatingDialogMessage?.text = message

    }

    private fun hideLocatingDialog() {
        locatingDialogMessage = null
        locatingDialog?.dismiss()
        locatingDialog = null
    }

    private fun isValidGoogleMapsLink(link: String): Boolean {
        return link.startsWith("https://goo.gl/maps") ||
                link.startsWith("https://maps.google.com") ||
                link.startsWith("https://www.google.com/maps")
    }


    /* =========================== Send ========================== */
    private fun showSendConfirmationDialog() {
        if (!locationConfirmed) {
            if (!isResolvingLocation) beginLocationConfirmation()
            Toast.makeText(this, "Please wait — confirming your location…", Toast.LENGTH_SHORT).show()
            return
        }

        val type = binding.emergencyDropdown.text?.toString()?.trim().orEmpty()
        if (type.isEmpty()) {
            Toast.makeText(this, "Please choose an Involve!.", Toast.LENGTH_SHORT).show()
            return
        }

        // Require link if Not My Location
        if (!isMyLocation) {
            val link = binding.locationLinkInput.text?.toString()?.trim().orEmpty()
            if (link.isEmpty()) {
                Toast.makeText(this, "Please enter a Google Maps link for the location.", Toast.LENGTH_SHORT).show()
                return
            }
            if (readableAddress.isNullOrBlank()) {
                Toast.makeText(this, "Waiting to confirm pasted location…", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val addr = readableAddress ?: "Not available yet"

        val now = Date()
        val hasPhoto = capturedFile != null
        val msg = """
    Please confirm the details below:

    • Date: ${SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(now)}
    • Time: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)}
    • Type: $type
    • Location: $addr
    • Photo: ${if (hasPhoto) "1 attached (Base64)" else "No photo attached"}
""".trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Confirm Fire Report")
            .setMessage(msg)
            .setPositiveButton("Proceed") { _, _ -> checkAndSendAlertReport() }
            .setNegativeButton("Cancel", null)
            .show()
    }


    fun fetchAddressFromCoords(lat: Double, lon: Double, callback: (String?) -> Unit) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                val address = if (!addresses.isNullOrEmpty()) addresses[0].getAddressLine(0) else null
                runOnUiThread { callback(address) }
            } catch (e: Exception) {
                runOnUiThread { callback(null) }
            }
        }.start()
    }


    private fun checkAndSendAlertReport() {
        val now = System.currentTimeMillis()
        if (now - lastReportTime >= 5 * 60 * 1000) {
            binding.sendButton.isEnabled = false
            binding.progressIcon.visibility = View.VISIBLE
            binding.progressBar.visibility = View.VISIBLE
            binding.progressText.visibility = View.VISIBLE
            sendReportRecord(now)
        } else {
            val waitMs = 5 * 60 * 1000 - (now - lastReportTime)
            val original = binding.sendButton.text.toString()
            Toast.makeText(this, "Please wait ${waitMs / 1000} seconds before submitting again.", Toast.LENGTH_LONG).show()
            binding.sendButton.isEnabled = false
            object : CountDownTimer(waitMs, 1000) {
                override fun onTick(ms: Long) { binding.sendButton.text = "Wait (${ms / 1000})" }
                override fun onFinish() { binding.sendButton.text = original; binding.sendButton.isEnabled = true }
            }.start()
        }
    }

    /** Convert optional photo, then push to Realtime Database with nearest Firestore station ID */
    private fun sendReportRecord(currentTime: Long) {
        val photoBase64 = if (capturedFile != null && capturedFile!!.exists()) {
            compressAndEncodeBase64(capturedFile!!)
        } else {
            "" // Photo optional
        }

        val userId = auth.currentUser?.uid ?: run {
            resetOverlay()
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        val rtdb = FirebaseDatabase.getInstance().reference
        val firestore = FirebaseFirestore.getInstance()

        // 1️⃣ Fetch user from Firestore
        firestore.collection("users").document(userId).get()
            .addOnSuccessListener { userSnap ->
                val user = userSnap.toObject(User::class.java) ?: run {
                    resetOverlay()
                    Toast.makeText(this, "User data not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val formattedDate = SimpleDateFormat("MM-dd-yyyy", Locale.getDefault()).format(
                    Date(
                        currentTime
                    )
                )
                val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(
                    Date(
                        currentTime
                    )
                )
                val type = binding.emergencyDropdown.text?.toString()?.trim().orEmpty()

                val report = FireReport(
                    name = user.name ?: "",
                    email = user.email ?: "",
                    contact = user.contact ?: "",
                    type = type,
                    date = formattedDate,
                    reportTime = formattedTime,
                    latitude = latitude,
                    longitude = longitude,
                    exactLocation = readableAddress.orEmpty(),
                    location = "https://www.google.com/maps?q=$latitude,$longitude",
                    photoBase64 = photoBase64,
                    timeStamp = currentTime,
                    status = "Pending",
                    adminNotif = false,
                    fireStationName = "",
                    isRead = false,
                    fireStationId = "",
                    isMyLocation = isMyLocation,
                    userDocId = userId          // 👈 Firestore document ID// 👈 store the boolean
                )

                // 2️⃣ Find nearest active Central Fire Station in Firestore
                firestore.collection("fireStations")
                    .whereEqualTo("status", "Active")
                    .whereEqualTo("role", "Central")
                    .get()
                    .addOnSuccessListener { stationsSnap ->
                        if (stationsSnap.isEmpty) {
                            resetOverlay()
                            Toast.makeText(this, "No active Central fire stations found.", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        val stationDistances = stationsSnap.documents.mapNotNull { doc ->
                            val lat = doc.getDouble("latitude") ?: return@mapNotNull null
                            val lon = doc.getDouble("longitude") ?: return@mapNotNull null
                            val dist = FloatArray(1)
                            Location.distanceBetween(latitude, longitude, lat, lon, dist)
                            Triple(doc, dist[0], doc.getString("stationName") ?: "Unknown")
                        }

                        val nearest = stationDistances.minByOrNull { it.second } ?: run {
                            resetOverlay()
                            Toast.makeText(this, "No valid fire stations found.", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        val nearestDoc = nearest.first
                        val stationId = nearestDoc.id
                        val stationName = nearest.third

                        // 3️⃣ Update report with station info
                        report.fireStationName = stationName
                        report.fireStationId = stationId

                        // 4️⃣ Store report in RTDB
                        rtdb.child("AllReport")
                            .child("FireReport")
                            .push()
                            .setValue(report)
                            .addOnSuccessListener {
                                lastReportTime = currentTime
                                Toast.makeText(this, "Report sent to $stationName", Toast.LENGTH_SHORT).show()
                                resetOverlay()
                                startActivity(Intent(this, UserActivity::class.java))
                                finish()
                            }
                            .addOnFailureListener {
                                resetOverlay()
                                Toast.makeText(this, "Failed to submit: ${it.message}", Toast.LENGTH_SHORT).show()
                            }

                    }
                    .addOnFailureListener {
                        resetOverlay()
                        Toast.makeText(this, "Failed to fetch fire stations: ${it.message}", Toast.LENGTH_SHORT).show()
                    }

            }
            .addOnFailureListener {
                resetOverlay()
                Toast.makeText(this, "Failed to fetch user data: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }



    /* =============== Image compression → Base64 (lighter) ====== */
    private fun compressAndEncodeBase64(
        file: File,
        maxDim: Int = 1024,
        initialQuality: Int = 75,
        targetBytes: Int = 400 * 1024
    ): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { BitmapFactory.decodeStream(it, null, opts) }

        fun computeSampleSize(w: Int, h: Int, maxDim: Int): Int {
            var sample = 1
            var width = w
            var height = h
            while (width / 2 >= maxDim || height / 2 >= maxDim) {
                width /= 2; height /= 2; sample *= 2
            }
            return sample
        }
        val inSample = computeSampleSize(opts.outWidth, opts.outHeight, maxDim)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = FileInputStream(file).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return ""

        val w = decoded.width
        val h = decoded.height
        val scale = maxOf(1f, maxOf(w, h) / maxDim.toFloat())
        val outW = (w / scale).toInt().coerceAtLeast(1)
        val outH = (h / scale).toInt().coerceAtLeast(1)
        val scaled = if (w > maxDim || h > maxDim) {
            Bitmap.createScaledBitmap(decoded, outW, outH, true)
        } else decoded
        if (scaled !== decoded) decoded.recycle()

        val baos = ByteArrayOutputStream()
        var q = initialQuality
        scaled.compress(Bitmap.CompressFormat.JPEG, q, baos)
        var data = baos.toByteArray()
        while (data.size > targetBytes && q > 40) {
            baos.reset()
            q -= 10
            scaled.compress(Bitmap.CompressFormat.JPEG, q, baos)
            data = baos.toByteArray()
        }
        if (!scaled.isRecycled) scaled.recycle()
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /* ======================= Send overlay ====================== */
    private fun resetOverlay() {
        binding.progressIcon.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.progressText.visibility = View.GONE
        binding.sendButton.isEnabled = true
    }


    private fun extractCoordinatesFromUrl(url: String): Pair<Double, Double>? {
        val regex = Regex("(-?\\d+\\.\\d+),\\s*(-?\\d+\\.\\d+)")
        val match = regex.find(url)
        return if (match != null) {
            val lat = match.groupValues[1].toDouble()
            val lon = match.groupValues[2].toDouble()
            Pair(lat, lon)
        } else null
    }

    private fun populateLocationSourceDropdown() {

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            options
        )

        binding.locationSourceDropdown.setAdapter(adapter)
        binding.locationSourceDropdown.setText("My Location", false)

        // Show/hide Google Maps link card
        binding.locationSourceDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = options[position]

            isMyLocation = selected == "My Location" // 👈 update boolean flag

            if (!isMyLocation) {
                // User wants to paste a link
                binding.locationLinkCard.visibility = View.VISIBLE
            } else {
                // User wants to use GPS
                binding.locationLinkCard.visibility = View.GONE
                readableAddress = null           // reset any previous link
                beginLocationConfirmation("Getting Exact Location…")
                checkPermissionsAndGetLocation() // restart GPS fetching
            }
        }

    }


}