package com.example.flare_capstone.data.model

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
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
import com.example.flare_capstone.databinding.ActivityEmergencyMedicalServicesBinding
import com.example.flare_capstone.views.activity.UserActivity
import com.example.flare_capstone.views.fragment.user.EmergencyMedicalServicesReport
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EmergencyMedicalServicesActivity : AppCompatActivity() {

    /* ---------------- View / Firebase / Location ---------------- */
    private lateinit var binding: ActivityEmergencyMedicalServicesBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var connectivityManager: ConnectivityManager

    /* ---------------- Dropdown ---------------- */
    private var selectedType: String? = null

    /* ---------------- Location State ---------------- */
    private var latitude = 0.0
    private var longitude = 0.0
    private var exactLocation: String = ""   // reverse-geocoded or “Within Tagum vicinity – <maps>”

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val CAMERA_PERMISSION_REQUEST_CODE = 1002

    /* ---------------- Tagum geofence ---------------- */
    private val TAGUM_CENTER_LAT = 7.447725
    private val TAGUM_CENTER_LON = 125.804150
    private val TAGUM_RADIUS_METERS = 11_000f
    private var tagumOk = false
    private var locationConfirmed = false
    private var isResolvingLocation = false

    /* ---------------- Dialogs ---------------- */
    private var loadingDialog: AlertDialog? = null
    private var locatingDialog: AlertDialog? = null
    private var locatingDialogMessage: TextView? = null

    /* ---------------- CameraX (optional) ---------------- */
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var capturedFile: File? = null
    private var capturedOnce = false

    /* ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityEmergencyMedicalServicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Network watcher
        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Location gate
        beginLocationConfirmation()
        checkPermissionsAndGetLocation()

        // Dropdown options from RTDB (Canocotan/ManageApplication/EmergencyMedicalServicesReport/Option)
//        populateDropdownFromDB()
        populateDropdownStatic()

        // Camera (optional)
        checkCameraPermissionAndStart()
        binding.btnCapture.setOnClickListener { if (capturedOnce) retakePhoto() else captureOnce() }

        // Buttons
        binding.cancelButton.setOnClickListener {
            startActivity(Intent(this, UserActivity::class.java))
            finish()
        }
        binding.sendButton.setOnClickListener { onSendClicked() }

        // Enable/disable based on state
        updateSendEnabled()

        // Toolbar back (optional)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        locatingDialog?.dismiss()
        locatingDialog = null
        locatingDialogMessage = null
        cameraExecutor.shutdown()
    }

    /* ======================== Connectivity ===================== */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                hideLoadingDialog()
                if (isResolvingLocation && !locationConfirmed) updateLocatingDialog("Getting Exact Location…")
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                showLoadingDialog("No internet connection")
                if (isResolvingLocation && !locationConfirmed) updateLocatingDialog("Waiting for internet…")
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

        // 👇 Add this part — find the Close TextView and handle click
        // 🔻 Handle "Close" click: dismiss dialog + finish activity
        val v = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
        val closeText = v.findViewById<TextView>(R.id.close_button)
        closeText?.setOnClickListener {
            locatingDialog?.dismiss()
            locatingDialog = null
            finish() // 👈 close FireLevelActivity
        }
    }
    private fun hideLoadingDialog() { loadingDialog?.dismiss() }

    /* ======================== Permissions & Location =========== */
    private fun checkPermissionsAndGetLocation() {
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk && coarseOk) requestOneFix()
        else ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestOneFix() {
        // Same flow as FireLevel: request a single high-accuracy update, then reverse-geocode.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMaxUpdates(1)
            .build()
        updateLocatingDialog("Getting GPS fix…")

        fusedLocationClient.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val l = result.lastLocation ?: return
                latitude = l.latitude
                longitude = l.longitude
                fusedLocationClient.removeLocationUpdates(this)

                // Match Fire screen wording
                updateLocatingDialog("Getting Exact Location…")
                FetchBarangayAddressTask(
                    this@EmergencyMedicalServicesActivity,
                    latitude,
                    longitude
                ).execute()
            }
        }, mainLooper)
    }

    /** Called by reverse-geocoding task */
    fun handleFetchedAddress(address: String?) {
        // Same gate as in FireLevel
        val cleaned = address?.trim().orEmpty()
        val textOk = cleaned.isNotBlank() && cleaned.contains("tagum", ignoreCase = true)
        val geoOk  = isWithinTagumByDistance(latitude, longitude)
        tagumOk = textOk || geoOk

        exactLocation = when {
            cleaned.isNotBlank() -> cleaned
            tagumOk && geoOk     -> "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude"
            else                 -> ""
        }

        if (tagumOk) {
            val suffix = if (exactLocation.isNotBlank()) ": $exactLocation" else ""
            endLocationConfirmation(true, "Location confirmed$suffix")
        } else {
            endLocationConfirmation(false, "Outside Tagum area. You can't submit a report.")
        }
    }

    private fun isWithinTagumByDistance(lat: Double, lon: Double): Boolean {
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, TAGUM_CENTER_LAT, TAGUM_CENTER_LON, out)
        return out[0] <= TAGUM_RADIUS_METERS
    }

    /* =================== Non-dismissible locating dialog ======= */
    private fun beginLocationConfirmation(hint: String = "Confirming location…") {
        isResolvingLocation = true
        locationConfirmed = false
        showLocatingDialog(hint) // cannot be dismissed until endLocationConfirmation
    }

    private fun endLocationConfirmation(success: Boolean, toast: String = "") {
        isResolvingLocation = false
        locationConfirmed = success
        hideLocatingDialog()
        if (toast.isNotBlank()) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
        updateSendEnabled()
    }

    private fun showLocatingDialog(initialText: String) {
        if (locatingDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
        locatingDialogMessage = view.findViewById(R.id.loading_message)
        locatingDialogMessage?.text = initialText

        locatingDialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create().also { it.show() }
    }
    private fun updateLocatingDialog(text: String) {
        locatingDialogMessage?.text = text
    }
    private fun hideLocatingDialog() {
        locatingDialogMessage = null
        locatingDialog?.dismiss()
        locatingDialog = null
    }

//
//    /* =================== Dropdown from RTDB (EMS Option) ======= */
//    private fun populateDropdownFromDB() {
//        val ref = FirebaseDatabase.getInstance().reference
//            .child("CanocotanFireStation")
//            .child("ManageApplication")
//            .child("EmergencyMedicalServicesReport")
//            .child("Option")
//
//        ref.addListenerForSingleValueEvent(object : ValueEventListener {
//            override fun onDataChange(s: DataSnapshot) {
//                val asString = s.getValue(String::class.java)
//                val items = when {
//                    !asString.isNullOrBlank() ->
//                        asString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
//                    s.exists() && s.childrenCount > 0 -> {
//                        val list = mutableListOf<String>()
//                        s.children.forEach { c ->
//                            c.getValue(String::class.java)?.trim()?.takeIf { it.isNotEmpty() }?.let(list::add)
//                        }
//                        list
//                    }
//                    else -> emptyList()
//                }
//                if (items.isEmpty()) {
//                    Toast.makeText(this@EmergencyMedicalServicesActivity, "No options found.", Toast.LENGTH_SHORT).show()
//                    return
//                }
//                val adapter = ArrayAdapter(this@EmergencyMedicalServicesActivity, android.R.layout.simple_list_item_1, items)
//                binding.emergencyMedicalServicesDropdown.setAdapter(adapter)
//                binding.emergencyMedicalServicesDropdown.setOnClickListener {
//                    binding.emergencyMedicalServicesDropdown.showDropDown()
//                }
//                binding.emergencyMedicalServicesDropdown.setOnItemClickListener { _, _, pos, _ ->
//                    selectedType = items[pos]
//                    updateSendEnabled()
//                }
//            }
//            override fun onCancelled(e: DatabaseError) {
//                Toast.makeText(this@EmergencyMedicalServicesActivity, "Failed to load options: ${e.message}", Toast.LENGTH_SHORT).show()
//            }
//        })
//    }


    /** Static dropdown list for fire report types */
    private fun populateDropdownStatic() {
        val fireTypes = listOf(
            "Vehicular Accident",
            "Patient Transport",
        )

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            fireTypes
        )

        binding.emergencyMedicalServicesDropdown.setAdapter(adapter)
        binding.emergencyMedicalServicesDropdown.setOnClickListener {
            binding.emergencyMedicalServicesDropdown.showDropDown()
        }

        // ✅ When the user selects an item:
        binding.emergencyMedicalServicesDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedType = fireTypes[position]
            binding.toolbar.title = "Emergency Medical Services"  // optional visual feedback
            updateSendEnabled() // ✅ enable the Send button when selection made
        }
    }

    /* ========================= CameraX ========================= */
    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

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
        val file = File.createTempFile("ems_", ".jpg", cacheDir)
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(opts, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { Toast.makeText(this@EmergencyMedicalServicesActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show() }
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
        runCatching { capturedFile?.delete() }
        capturedFile = null
        capturedOnce = false
        binding.capturedPhoto.setImageDrawable(null)
        binding.capturedPhoto.visibility = View.GONE
        binding.cameraPreview.visibility = View.VISIBLE
        startCameraPreview()
        binding.btnCapture.text = "Capture"
    }

    /* =========================== Send ========================== */
    private fun onSendClicked() {
        if (!locationConfirmed || !tagumOk) {
            Toast.makeText(this, "Please wait — confirming your location…", Toast.LENGTH_SHORT).show()
            if (!isResolvingLocation) beginLocationConfirmation()
            return
        }
        val type = selectedType?.trim().orEmpty()
        if (type.isEmpty()) {
            Toast.makeText(this, "Please choose an Involve!.", Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date(now))
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        val addr = exactLocation.ifBlank { "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude" }
        val hasPhoto = capturedFile != null

        val msg = """
            Please confirm the details below:

            • Type: $type
            • Date: $dateStr
            • Time: $timeStr
            • Location: $addr
            • Photo: ${if (hasPhoto) "1 attached (Base64)" else "No photo attached"}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Confirm Emergency Medical Report")
            .setMessage(msg)
            .setPositiveButton("Proceed") { _, _ -> sendToCanocotan(now, type) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendToCanocotan(currentTime: Long, type: String) {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseDatabase.getInstance().getReference("Users").child(uid).get()
            .addOnSuccessListener { snap ->
                val user = snap.getValue(User::class.java) ?: run {
                    Toast.makeText(this, "User data not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val dateFmt = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
                val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                val photoB64 = capturedFile?.takeIf { it.exists() }?.let { compressAndEncodeBase64(it) } ?: ""

                val report = EmergencyMedicalServicesReport(
                    type = type,
                    name = user.name.orEmpty(),
                    contact = user.contact.orEmpty(),
                    date = dateFmt.format(Date(currentTime)),
                    reportTime = timeFmt.format(Date(currentTime)),
                    latitude = latitude.toString(),
                    longitude = longitude.toString(),
                    location = "https://www.google.com/maps?q=$latitude,$longitude",
                    exactLocation = exactLocation,
                    timestamp = currentTime,
                    status = "Pending",
                    read = false,
                    fireStationName = "",
                    photoBase64 = photoB64
                )

                // ✅ Get nearest ACTIVE station first, fallback to nearest if none active
                val stationsRef = FirebaseDatabase.getInstance().getReference("FireStations")
                stationsRef.get().addOnSuccessListener { snapshot ->
                    val stationList = mutableListOf<Triple<DataSnapshot, Float, Boolean>>() // (snapshot, distance, isActive)

                    for (stationSnap in snapshot.children) {
                        val lat = stationSnap.child("latitude").getValue(String::class.java)?.toDoubleOrNull() ?: continue
                        val lon = stationSnap.child("longitude").getValue(String::class.java)?.toDoubleOrNull() ?: continue
                        val active = stationSnap.child("status").getValue(String::class.java)?.equals("Active", true) ?: false

                        val results = FloatArray(1)
                        Location.distanceBetween(latitude, longitude, lat, lon, results)
                        stationList.add(Triple(stationSnap, results[0], active))
                    }

                    if (stationList.isEmpty()) {
                        Toast.makeText(this, "No fire stations found.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    // Sort by distance ascending
                    stationList.sortBy { it.second }

                    // ✅ Pick nearest active, fallback to nearest
                    val target = stationList.find { it.third } ?: stationList.first()
                    val stationSnap = target.first
                    val stationKey = stationSnap.key ?: return@addOnSuccessListener
                    val stationName = stationSnap.child("stationName").getValue(String::class.java) ?: "Unknown Station"

                    val db = FirebaseDatabase.getInstance().reference

                    // ✅ Update fireStationName dynamically
                    report.fireStationName = stationName

                    // ✅ Push only under the station’s AllReport/EmergencyMedicalServicesReport
                    val stationRef = db.child("FireStations")
                        .child(stationKey)
                        .child("AllReport")
                        .child("EmergencyMedicalServicesReport")
                        .push()

                    stationRef.setValue(report)
                        .addOnSuccessListener {
                            Toast.makeText(this, "Report sent to $stationName", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, UserActivity::class.java))
                            finish()
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Failed to submit to $stationName: ${it.message}", Toast.LENGTH_SHORT).show()
                        }

                }.addOnFailureListener {
                    Toast.makeText(this, "Failed to fetch fire stations: ${it.message}", Toast.LENGTH_SHORT).show()
                }

            }.addOnFailureListener {
                Toast.makeText(this, "Failed to fetch user data: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }





    /* =========================== Utils ========================= */
    private fun compressAndEncodeBase64(
        file: File,
        maxDim: Int = 1024,
        initialQuality: Int = 75,
        targetBytes: Int = 400 * 1024
    ): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }

        fun sampleSize(w: Int, h: Int, maxD: Int): Int {
            var s = 1; var W = w; var H = h
            while (W / 2 >= maxD || H / 2 >= maxD) { W /= 2; H /= 2; s *= 2 }
            return s
        }
        val decode = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = FileInputStream(file).use { BitmapFactory.decodeStream(it, null, decode) } ?: return ""
        val (w, h) = bmp.width to bmp.height
        val scale = maxOf(1f, maxOf(w, h) / maxDim.toFloat())
        val outW = (w / scale).toInt().coerceAtLeast(1)
        val outH = (h / scale).toInt().coerceAtLeast(1)
        val scaled = if (w > maxDim || h > maxDim) Bitmap.createScaledBitmap(bmp, outW, outH, true) else bmp
        if (scaled !== bmp) bmp.recycle()
        val baos = ByteArrayOutputStream()
        var q = initialQuality
        scaled.compress(Bitmap.CompressFormat.JPEG, q, baos)
        var data = baos.toByteArray()
        while (data.size > targetBytes && q > 40) {
            baos.reset(); q -= 10
            scaled.compress(Bitmap.CompressFormat.JPEG, q, baos)
            data = baos.toByteArray()
        }
        if (!scaled.isRecycled) scaled.recycle()
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /* =================== Permission result ===================== */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    requestOneFix()
                } else {
                    Toast.makeText(this, "Location permission needed.", Toast.LENGTH_SHORT).show()
                }
            }
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    startCameraPreview()
                } else {
                    Toast.makeText(this, "Camera permission needed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateSendEnabled() {
        val hasCoords = !(latitude == 0.0 && longitude == 0.0)
        binding.sendButton.isEnabled = tagumOk && hasCoords && locationConfirmed
    }
}