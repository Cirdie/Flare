package com.example.flare_capstone.views.fragment.user

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresPermission
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.example.flare_capstone.R
import com.example.flare_capstone.adapter.NotificationAdapter
import com.example.flare_capstone.adapter.UiNotification
import com.example.flare_capstone.data.model.EmergencyMedicalServicesActivity
import com.example.flare_capstone.databinding.FragmentHomeBinding
import com.example.flare_capstone.databinding.ViewNotificationsPanelBinding
import com.example.flare_capstone.views.auth.MainActivity
import com.example.flare_capstone.views.fragment.profile.MyReportActivity
import com.example.flare_capstone.views.fragment.settings.AboutAppActivity
import com.example.flare_capstone.views.fragment.user.FireLevelActivity
import com.example.flare_capstone.views.fragment.user.OtherEmergencyActivity
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.maps.android.PolyUtil
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class HomeFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var navView: NavigationView? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var map: GoogleMap
    private var mapReady = false
    private var locationUpdatesStarted = false

    private var userLatitude = 0.0
    private var userLongitude = 0.0
    private var userMarker: Marker? = null

    private var iconStation: BitmapDescriptor? = null
    private var iconNearest: BitmapDescriptor? = null
    private var iconUser: BitmapDescriptor? = null

    private val stationMarkers = mutableMapOf<String, Marker>()
    private val stationCoords = mutableMapOf<String, LatLng>()
    private var cameraFittedOnce = false

    private var selectedStationTitle: String? = null
    private var activePolyline: Polyline? = null
    private var activeDistanceMeters: Long? = null
    private var activeDurationSec: Long? = null
    private val routeRequestSeq = AtomicInteger(0)

    private val COLOR_ACTIVE = Color.BLUE
    private val ROUTE_WIDTH_PX = 10f

    private val RES_STATION = R.drawable.ic_station_longest
    private val RES_STATION_NEAREST = R.drawable.ic_station_shortest
    private val RES_USER_LOCATION = R.drawable.ic_user_location

    private val DEFAULT_CENTER_PH = LatLng(12.8797, 121.7740)
    private val DEFAULT_ZOOM_COUNTRY = 5.8f
    private val DEFAULT_ZOOM_CITY = 14f

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private var tagumRings: List<List<LatLng>>? = null

    /* =========================================================
     * Permissions
     * ========================================================= */
    private val locationPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            enableMyLocationSafely()
            startLocationUpdatesSafely()
            primeLocationOnce()
        } else {
            postToast("Location permission denied")
        }
    }

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        val mapFrag = childFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
            ?: SupportMapFragment.newInstance().also {
                childFragmentManager.beginTransaction().replace(R.id.map, it, "home_map").commitNow()
            }
        mapFrag.getMapAsync(this)

        setupButtons()
        setupDrawer(view)
    }

    private fun setupButtons() {
        val onClick: (String, () -> Class<*>) -> Unit = { label, activityClass ->
            if (!binding.fireButton.isEnabled) {
                postToast("All fire stations are inactive. Reporting unavailable.")
            } else {
                handleEmergencyAction(activityClass)
            }
        }

        binding.fireButton.setOnClickListener { onClick("Fire") { FireLevelActivity::class.java } }
        binding.accidentButton.setOnClickListener { onClick("EMS") { EmergencyMedicalServicesActivity::class.java } }
        binding.otherButton.setOnClickListener { onClick("Other") { OtherEmergencyActivity::class.java } }

        binding.ivNotifications.setOnClickListener {
            showNotificationsPopup(binding.ivNotifications)
        }
    }

    /* =========================================================
     * Notification Popup
     * ========================================================= */
    private var notifPopup: PopupWindow? = null

    private fun showNotificationsPopup(anchor: View) {
        val panel = ViewNotificationsPanelBinding.inflate(layoutInflater)
        val adapter = NotificationAdapter(
            context = requireContext(),
            dbRT = FirebaseDatabase.getInstance(),
            dbFS = FirebaseFirestore.getInstance(),
            items = mutableListOf(),
            onClick = { /* TODO: Open report detail */ }
        )

        panel.rvNotifications.layoutManager = LinearLayoutManager(requireContext())
        panel.rvNotifications.adapter = adapter

        var allItems: List<UiNotification> = emptyList()

        val typeOptions = listOf("All Reports", "Fire Report", "Other Emergency Report", "Emergency Medical Services Report")
        val statusOptions = listOf("All Statuses", "Ongoing", "Completed")

        fun setupDropdown(act: AutoCompleteTextView, til: TextInputLayout, options: List<String>) {
            act.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options))
            act.setText(options.first(), false)
            til.setOnClickListener { act.showDropDown() }
            act.setOnClickListener { act.showDropDown() }
        }

        setupDropdown(panel.actFilter, panel.tilFilter, typeOptions)
        setupDropdown(panel.actStatus, panel.tilStatus, statusOptions)

        fun applyFilters(typeSel: String, statusSel: String) {
            var filtered = allItems
            filtered = when (typeSel) {
                "Fire Report" -> filtered.filter { it.title == "Fire Report" }
                "Other Emergency Report" -> filtered.filter { it.title == "Other Emergency Report" }
                "Emergency Medical Services Report" -> filtered.filter { it.title == "Emergency Medical Services Report" }
                else -> filtered
            }
            filtered = when (statusSel) {
                "Ongoing" -> filtered.filter { it.status.lowercase() == "ongoing" }
                "Completed" -> filtered.filter { it.status.lowercase() == "completed" }
                else -> filtered
            }
            adapter.submit(filtered)
        }

        panel.actFilter.setOnItemClickListener { _, _, pos, _ ->
            applyFilters(typeOptions[pos], panel.actStatus.text?.toString().orEmpty().ifBlank { "All Statuses" })
        }
        panel.actStatus.setOnItemClickListener { _, _, pos, _ ->
            applyFilters(panel.actFilter.text?.toString().orEmpty().ifBlank { "All Reports" }, statusOptions[pos])
        }

        val authEmail = auth.currentUser?.email ?: ""
        if (authEmail.isBlank()) {
            adapter.submit(emptyList())
        } else {
            firestore.collection("users").whereEqualTo("email", authEmail).get()
                .addOnSuccessListener { snap ->
                    if (snap.isEmpty) return@addOnSuccessListener
                    val userDoc = snap.documents[0]
                    val userName = userDoc.getString("name").orEmpty()
                    val userContact = userDoc.getString("contact").orEmpty()
                    if (userName.isBlank() || userContact.isBlank()) return@addOnSuccessListener

                    val dbRef = FirebaseDatabase.getInstance().getReference("AllReport")
                    val types = listOf(
                        "FireReport" to R.drawable.ic_fire_24,
                        "OtherEmergencyReport" to R.drawable.ic_warning_24,
                        "EmergencyMedicalServicesReport" to R.drawable.ic_car_crash_24
                    )

                    val collected = mutableListOf<UiNotification>()
                    var pending = types.size
                    types.forEach { (typeKey, iconRes) ->
                        dbRef.child(typeKey).addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                snapshot.children.forEach { report ->
                                    val statusNorm = report.child("status").getValue(String::class.java)?.trim()?.lowercase() ?: ""
                                    if (statusNorm !in listOf("ongoing", "completed")) return@forEach
                                    val reportName = report.child("name").getValue(String::class.java).orEmpty()
                                    val reportContact = report.child("contact").getValue(String::class.java).orEmpty()
                                    if (!reportName.equals(userName, ignoreCase = true) || reportContact != userContact) return@forEach
                                    val date = report.child("date").getValue(String::class.java).orEmpty()
                                    val time = report.child("reportTime").getValue(String::class.java).orEmpty()
                                    val exactLocation = report.child("exactLocation").getValue(String::class.java)
                                        ?: report.child("fireStationName").getValue(String::class.java)
                                        ?: "Unknown"
                                    val (mapLink, typeTitle, title) = when (typeKey) {
                                        "FireReport" -> Triple(report.child("location").getValue(String::class.java).orEmpty(),
                                            report.child("type").getValue(String::class.java).orEmpty(), "Fire Report")
                                        "OtherEmergencyReport" -> Triple(report.child("location").getValue(String::class.java).orEmpty(),
                                            report.child("emergencyType").getValue(String::class.java).orEmpty(), "Other Emergency Report")
                                        "EmergencyMedicalServicesReport" -> Triple(report.child("location").getValue(String::class.java).orEmpty(),
                                            report.child("type").getValue(String::class.java).orEmpty(), "Emergency Medical Services Report")
                                        else -> Triple("", "Unknown Type", "Unknown Report")
                                    }
                                    val read = report.child("read").getValue(Boolean::class.java) ?: false
                                    val key = report.key.orEmpty()
                                    collected += UiNotification(
                                        title = title, type = typeTitle,
                                        whenText = listOf(date, time).filter { it.isNotBlank() }.joinToString(" "),
                                        locationText = exactLocation, location = mapLink,
                                        unread = !read, iconRes = iconRes, station = "",
                                        key = key, typeKey = typeKey, status = statusNorm
                                    )
                                }
                                if (--pending == 0) {
                                    allItems = collected
                                    applyFilters(panel.actFilter.text?.toString().orEmpty().ifBlank { "All Reports" },
                                        panel.actStatus.text?.toString().orEmpty().ifBlank { "All Statuses" })
                                }
                            }
                            override fun onCancelled(error: DatabaseError) { if (--pending == 0) {
                                allItems = collected
                                applyFilters(panel.actFilter.text?.toString().orEmpty().ifBlank { "All Reports" },
                                    panel.actStatus.text?.toString().orEmpty().ifBlank { "All Statuses" })
                            }}
                        })
                    }
                }
        }

        val widthPx = (320 * resources.displayMetrics.density).toInt()
        val popup = PopupWindow(panel.root, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 16f
        }
        anchor.post { popup.showAsDropDown(anchor, 0, 0, Gravity.END) }
        notifPopup = popup
    }

    /* =========================================================
     * Emergency Actions
     * ========================================================= */
    private fun handleEmergencyAction(activityClass: () -> Class<*>) {
        if (userLatitude == 0.0 && userLongitude == 0.0) {
            postToast("Getting your location…"); return
        }
        if (!isInsideTagum()) {
            postToast("You can’t submit a report outside Tagum."); return
        }
        startActivity(Intent(requireActivity(), activityClass()))
    }

    /* =========================================================
     * Drawer
     * ========================================================= */
    private fun setupDrawer(view: View) {
        val topBar = view.findViewById<MaterialToolbar?>(R.id.topAppBar) ?: requireActivity().findViewById(R.id.topAppBar)
        val drawer: DrawerLayout? = view.findViewById(R.id.drawer_layout) ?: requireActivity().findViewById(R.id.drawer_layout)
        val nav: NavigationView? = view.findViewById(R.id.nav_view) ?: requireActivity().findViewById(R.id.nav_view)

        topBar?.setNavigationOnClickListener { drawer?.open() }
        nav?.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_report_fire -> handleEmergencyAction { FireLevelActivity::class.java }
                R.id.nav_my_reports -> startActivity(Intent(requireContext(), MyReportActivity::class.java))
                R.id.nav_about -> startActivity(Intent(requireContext(), AboutAppActivity::class.java))
            }
            drawer?.closeDrawers(); true
        }
        nav?.let { populateUserHeader(it) }
        navView = nav
        startUserHeaderListener()
    }

    /* =========================================================
     * Logout
     * ========================================================= */
    private fun logout() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_logout, null)
        dialogView.findViewById<ImageView>(R.id.logoImageView).setImageResource(R.drawable.ic_logo)
        AlertDialog.Builder(requireActivity())
            .setView(dialogView)
            .setPositiveButton("Yes") { _, _ ->
                requireActivity().getSharedPreferences("shown_notifications", Context.MODE_PRIVATE)
                    .edit().clear().putInt("unread_message_count", 0).apply()
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(requireActivity(), MainActivity::class.java))
                postToast("You have been logged out.")
                requireActivity().finish()
            }
            .setNegativeButton("No") { d, _ -> d.dismiss() }
            .show()
    }

    /* =========================================================
     * Map Setup
     * ========================================================= */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        mapReady = true

        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = true

        if (hasLocationPermission()) {
            enableMyLocationSafely()
            startLocationUpdatesSafely()
            primeLocationOnce()
        } else {
            requestLocationPerms()
        }

        loadTagumBoundaryFromRaw()
        fetchFireStations()

        map.setOnMarkerClickListener { marker ->
            val title = marker.title ?: return@setOnMarkerClickListener false
            if (title == "You are here") return@setOnMarkerClickListener false

            val dest = stationCoords[title]
            if (dest == null) { postToast("Station location not found."); return@setOnMarkerClickListener true }
            if (userLatitude == 0.0 || userLongitude == 0.0) { postToast("Waiting for your location…"); return@setOnMarkerClickListener true }

            if (selectedStationTitle == title && activePolyline != null) {
                clearActiveRoute()
                selectedStationTitle = null
                routeRequestSeq.incrementAndGet()
                postToast("Route hidden")
                return@setOnMarkerClickListener true
            }

            selectedStationTitle = title
            drawSingleRouteOSRM(LatLng(userLatitude, userLongitude), dest, title) {
                val km = (activeDistanceMeters ?: 0) / 1000.0
                val mins = (activeDurationSec ?: 0) / 60.0
                postToast("$title • ${"%.1f".format(km)} km • ${"%.0f".format(mins)} min")
            }

            true
        }
    }

    /* =========================================================
     * Fetch Fire Stations
     * ========================================================= */
    private fun fetchFireStations() {
        if (!mapReady) return

        firestore.collection("fireStations").get().addOnSuccessListener { result ->
            stationMarkers.clear()
            stationCoords.clear()
            ensureIcons()

            var centralActive = false
            for (doc in result) {
                val stationName = doc.getString("stationName") ?: doc.id
                val lat = when (val v = doc.get("latitude")) {
                    is Double -> v; is Long -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
                }
                val lng = when (val v = doc.get("longitude")) {
                    is Double -> v; is Long -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
                }
                if (lat == null || lng == null) continue
                val status = doc.getString("status") ?: "Inactive"
                val role = doc.getString("role") ?: ""
                val pos = LatLng(lat, lng)
                stationCoords[stationName] = pos
                if (role.equals("Central", ignoreCase = true) && status.equals("Active", ignoreCase = true))
                    centralActive = true

                val icon = if (status.equals("Active", ignoreCase = true)) iconStation
                else bitmapFromDrawable(R.drawable.ic_station_longest, Color.GRAY, 25, 30)

                stationMarkers[stationName] = map.addMarker(MarkerOptions().position(pos).title(stationName).icon(icon).anchor(0.5f, 1f))!!
            }

            binding.fireButton.isEnabled = centralActive
            binding.accidentButton.isEnabled = centralActive
            binding.otherButton.isEnabled = centralActive
        }
    }

    private fun ensureIcons() {
        if (iconStation == null) iconStation = bitmapFromDrawable(RES_STATION, null, 25, 30)
        if (iconNearest == null) iconNearest = bitmapFromDrawable(RES_STATION_NEAREST, null, 25, 30)
        if (iconUser == null) iconUser = bitmapFromDrawable(RES_USER_LOCATION, null, 30, 35)
    }

    private fun clearActiveRoute() {
        activePolyline?.remove()
        activePolyline = null
        activeDistanceMeters = null
        activeDurationSec = null
    }

    private fun postToast(msg: String) {
        if (!isAdded) return
        requireActivity().runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
    }

    /* =========================================================
     * Location Handling
     * ========================================================= */
    private fun hasLocationPermission(): Boolean {
        val ctx = context ?: return false
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPerms() { locationPermsLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationSafely() { if (hasLocationPermission() && ::map.isInitialized) map.isMyLocationEnabled = true }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesSafely() {
        if (!hasLocationPermission() || locationUpdatesStarted) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateDistanceMeters(1f).build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        locationUpdatesStarted = true
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            userLatitude = loc.latitude
            userLongitude = loc.longitude

            val userLatLng = LatLng(userLatitude, userLongitude)
            if (userMarker == null) userMarker = map.addMarker(MarkerOptions().position(userLatLng).title("You are here").icon(iconUser))
            else userMarker?.position = userLatLng

            if (!cameraFittedOnce) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, DEFAULT_ZOOM_CITY))
                cameraFittedOnce = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun primeLocationOnce() {
        if (!hasLocationPermission()) return
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                userLatitude = location.latitude
                userLongitude = location.longitude
                val userLatLng = LatLng(userLatitude, userLongitude)
                userMarker = map.addMarker(MarkerOptions().position(userLatLng).title("You are here").icon(iconUser))
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, DEFAULT_ZOOM_CITY))
                cameraFittedOnce = true
            } else Handler(Looper.getMainLooper()).postDelayed({ if (!cameraFittedOnce) map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER_PH, DEFAULT_ZOOM_COUNTRY)) }, 2000)
        }
    }

    /* =========================================================
     * Geofence / Tagum City Polygon
     * ========================================================= */
    private fun loadTagumBoundaryFromRaw() {
        try {
            val input = resources.openRawResource(R.raw.tagum_boundary)
            val jsonText = input.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonText)
            val features = json.getJSONArray("features")
            val ringsList = mutableListOf<List<LatLng>>()

            for (i in 0 until features.length()) {
                val geom = features.getJSONObject(i).getJSONObject("geometry")
                val type = geom.getString("type")
                val coordsArray = geom.getJSONArray("coordinates")
                if (type == "Polygon") {
                    for (j in 0 until coordsArray.length()) {
                        val ring = coordsArray.getJSONArray(j)
                        val latLngList = mutableListOf<LatLng>()
                        for (k in 0 until ring.length()) {
                            val coord = ring.getJSONArray(k)
                            latLngList.add(LatLng(coord.getDouble(1), coord.getDouble(0)))
                        }
                        ringsList.add(latLngList)
                    }
                }
            }
            tagumRings = ringsList
            drawTagumPolygon(ringsList)
        } catch (e: Exception) { postToast("Failed to load Tagum boundary: ${e.message}") }
    }

    private fun drawTagumPolygon(rings: List<List<LatLng>>) {
        for (ring in rings) map.addPolygon(PolygonOptions().addAll(ring).strokeColor(Color.argb(160, 255, 140, 0)).fillColor(Color.argb(60, 255, 165, 0)).strokeWidth(4f))
    }

    private fun isInsideTagum(): Boolean {
        val point = LatLng(userLatitude, userLongitude)
        val rings = tagumRings ?: return false
        for (ring in rings) if (pointInPolygon(point, ring)) return true
        return false
    }

    private fun pointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        var intersectCount = 0
        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            val a = polygon[i]
            val b = polygon[j]
            if (((a.latitude > point.latitude) != (b.latitude > point.latitude)) &&
                (point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude)) intersectCount++
        }
        return intersectCount % 2 == 1
    }

    /* =========================================================
     * User Header (Drawer)
     * ========================================================= */
    private fun populateUserHeader(navView: NavigationView) {
        val headerView = navView.getHeaderView(0)
        headerView.findViewById<TextView>(R.id.headerEmail).text = auth.currentUser?.email ?: "Guest"
    }

    private fun startUserHeaderListener() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val header = navView?.getHeaderView(0) ?: return@addSnapshotListener
                val name = snapshot.getString("name") ?: ""
                val email = snapshot.getString("email") ?: ""
                val photo = snapshot.getString("profile")
                header.findViewById<TextView>(R.id.headerName).text = name
                header.findViewById<TextView>(R.id.headerEmail).text = email
                val imgView = header.findViewById<ImageView>(R.id.headerAvatar)
                if (!photo.isNullOrBlank()) {
                    if (photo.startsWith("data:image")) {
                        try {
                            val bytes = Base64.decode(photo.substringAfter(","), Base64.DEFAULT)
                            Glide.with(this).asBitmap().load(bytes).transform(CircleCrop()).into(imgView)
                        } catch (_: Exception) {}
                    } else Glide.with(this).load(photo).transform(CircleCrop()).into(imgView)
                } else imgView.setImageResource(R.drawable.ic_profile)
            }
        }
    }

    private fun bitmapFromDrawable(
        @DrawableRes resId: Int,
        tintColor: Int? = null,
        widthDp: Int = 24,
        heightDp: Int = 24
    ): BitmapDescriptor {
        val drawable = AppCompatResources.getDrawable(requireContext(), resId)!!
        val wrapped = DrawableCompat.wrap(drawable)
        tintColor?.let { DrawableCompat.setTint(wrapped, it) }

        val widthPx = widthDp.dp()
        val heightPx = heightDp.dp()
        wrapped.setBounds(0, 0, widthPx, heightPx)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        wrapped.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun drawSingleRouteOSRM(
        origin: LatLng,
        destination: LatLng,
        title: String,
        onRouteReady: (() -> Unit)? = null
    ) {
        val seq = routeRequestSeq.incrementAndGet()
        val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=polyline"

        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val result = reader.readText()
                reader.close()
                conn.disconnect()

                val json = JSONObject(result)
                val routes = json.getJSONArray("routes")
                if (routes.length() == 0) return@Thread
                val route = routes.getJSONObject(0)
                val distance = route.getDouble("distance") // meters
                val duration = route.getDouble("duration") // seconds
                val geometry = route.getString("geometry")

                val decoded = PolyUtil.decode(geometry) // Requires com.google.maps.android:android-maps-utils

                requireActivity().runOnUiThread {
                    if (seq != routeRequestSeq.get()) return@runOnUiThread
                    clearActiveRoute()
                    activePolyline = map.addPolyline(
                        PolylineOptions().addAll(decoded).color(COLOR_ACTIVE).width(ROUTE_WIDTH_PX)
                    )
                    activeDistanceMeters = distance.toLong()
                    activeDurationSec = duration.toLong()
                    onRouteReady?.invoke()
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread { postToast("Route failed: ${e.message}") }
            }
        }.start()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
