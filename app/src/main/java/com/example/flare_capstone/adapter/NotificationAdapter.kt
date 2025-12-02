package com.example.flare_capstone.adapter

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.ItemNotificationBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FirebaseFirestore

data class UiNotification(
    val title: String,
    val type: String,
    val whenText: String,
    val locationText: String,
    val location: String? = null,
    var unread: Boolean = true, // changed from val to var
    val iconRes: Int = R.drawable.ic_alert_24,
    val station: String = "",
    val key: String,
    val typeKey: String,
    val status: String
)

class NotificationAdapter(
    private val context: Context,
    private val dbRT: FirebaseDatabase,
    private val dbFS: FirebaseFirestore,
    private var items: MutableList<UiNotification>,
    private val onClick: (UiNotification) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.VH>() {

    private var currentUserDocId: String? = null

    inner class VH(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                    val item = items[pos]

                    // Mark as read in RTDB if admin notification
                    markAsRead(item)

                    // Update local unread status
                    item.unread = false
                    notifyItemChanged(pos)

                    // Trigger callback
                    onClick(item)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding

        refreshFromFirebase()

        b.tvTitle.text = item.title
        b.tvMeta.text = item.type
        b.tvDate.text = item.whenText
        b.tvStatus.text = "${item.status.replaceFirstChar { it.uppercase() }}"
        b.btnFeedback.visibility = if (item.status.lowercase() == "ongoing") View.GONE else View.VISIBLE
        b.tvLocation.text = item.locationText
        b.unreadDot.visibility = if (item.unread) View.VISIBLE else View.GONE
        (b.root as? MaterialCardView)?.strokeColor =
            if (item.unread) ContextCompat.getColor(context, R.color.yellow_400) // yellow border
            else ContextCompat.getColor(context, R.color.transparent)             // no border


        b.btnMap.setOnClickListener {
            showMapDialog(item.location)
        }

        b.btnFeedback.setOnClickListener {
            val userDocId = currentUserDocId ?: run {
                Toast.makeText(context, "User document not found", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val ref = dbRT.getReference("AllReport")
                .child(item.typeKey)
                .child(item.key)
                .child("UserFeedback")
                .child(userDocId)

            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val rating = snapshot.child("rating").getValue(Long::class.java)?.toInt() ?: 0
                        val message = snapshot.child("message").getValue(String::class.java).orEmpty()
                        showFeedbackDialogReadOnly(context, rating, message) {}
                    } else showFeedbackDialogEditable(context, item) {}
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(context, "Couldn't open feedback", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    override fun getItemCount() = items.size

    fun submit(list: List<UiNotification>) {
        items.clear()
        items.addAll(list.sortedByDescending { it.whenText })
        notifyDataSetChanged()
    }

    /** Fetch Firestore user doc and then attach RTDB listeners */
    fun refreshFromFirebase() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val email = currentUser.email ?: return

        dbFS.collection("users").whereEqualTo("email", email).get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) return@addOnSuccessListener
                currentUserDocId = snap.documents[0].id
            }
    }

    private fun markAsRead(item: UiNotification) {
        val ref = dbRT.getReference("AllReport")
            .child(item.typeKey) // e.g., "FireReport"
            .child(item.key)     // <reportKey>

        val updates = mapOf("read" to true)
        ref.updateChildren(updates)
            .addOnFailureListener {
                Toast.makeText(context, "Failed to mark notification as read", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showFeedbackDialogEditable(ctx: Context, item: UiNotification, onDone: (Boolean) -> Unit = {}) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_feedback, null, false)
        val ratingBar = view.findViewById<RatingBar>(R.id.ratingBar)
        val etMsg = view.findViewById<TextInputEditText>(R.id.etFeedback)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(view)
            .setPositiveButton("Send", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            send.setOnClickListener {
                val rating = ratingBar.rating.toInt()
                val message = etMsg.text?.toString()?.trim().orEmpty()
                if (rating == 0 && message.isBlank()) {
                    Toast.makeText(ctx, "Add a rating or message", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val uid = currentUserDocId ?: return@setOnClickListener
                val payload = hashMapOf(
                    "rating" to rating,
                    "message" to message,
                    "userDocId" to uid,
                    "createdAt" to ServerValue.TIMESTAMP
                )

                dbRT.getReference("AllReport")
                    .child(item.typeKey)
                    .child(item.key)
                    .child("UserFeedback")
                    .child(uid)
                    .setValue(payload)
                    .addOnCompleteListener { t ->
                        if (t.isSuccessful) {
                            Toast.makeText(ctx, "Thanks for your feedback!", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onDone(true)
                        } else Toast.makeText(ctx, "Failed to send feedback", Toast.LENGTH_SHORT).show()
                    }
            }
        }
        dialog.show()
    }

    private fun showFeedbackDialogReadOnly(ctx: Context, rating: Int, message: String?, onClose: () -> Unit = {}) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_feedback, null, false)
        val ratingBar = view.findViewById<RatingBar>(R.id.ratingBar)
        val etMsg = view.findViewById<TextInputEditText>(R.id.etFeedback)
        ratingBar.rating = rating.toFloat()
        ratingBar.setIsIndicator(true)
        etMsg.setText(message.orEmpty())
        etMsg.isEnabled = false
        view.findViewById<TextView>(R.id.tvRateTitle)?.text = "Your Feedback"

        MaterialAlertDialogBuilder(ctx).setView(view)
            .setPositiveButton("Close") { d, _ -> d.dismiss(); onClose() }
            .show()
    }

    private fun showMapDialog(mapUrl: String?) {
        if (mapUrl.isNullOrBlank()) {
            Toast.makeText(context, "No map link available", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_map, null, false)
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        val fragmentManager = (context as? FragmentActivity)?.supportFragmentManager
        val mapFragment = fragmentManager?.findFragmentById(R.id.mapFragment) as? SupportMapFragment
            ?: SupportMapFragment.newInstance().also {
                fragmentManager?.beginTransaction()?.replace(R.id.mapFragment, it)?.commit()
            }

        mapFragment?.getMapAsync { googleMap ->
            try {
                val uri = Uri.parse(mapUrl)
                val coords = uri.getQueryParameter("q")?.split(",") ?: listOf()
                if (coords.size == 2) {
                    val lat = coords[0].toDoubleOrNull()
                    val lng = coords[1].toDoubleOrNull()
                    if (lat != null && lng != null) {
                        val location = LatLng(lat, lng)
                        googleMap.addMarker(MarkerOptions().position(location).title("Report Location"))
                        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to parse map location", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
