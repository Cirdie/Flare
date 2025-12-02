package com.example.flare_capstone.adapter

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.views.fragment.user.FireReportResponseActivity
import com.example.flare_capstone.data.model.ResponseMessage
import com.example.flare_capstone.databinding.ItemFireStationBinding
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class ResponseMessageAdapter(
    private val list: MutableList<ResponseMessage>,
    private val onMarkedRead: (() -> Unit)? = null
) : RecyclerView.Adapter<ResponseMessageAdapter.ViewHolder>() {

    private val firestore = FirebaseFirestore.getInstance()
    inner class ViewHolder(val binding: ItemFireStationBinding) : RecyclerView.ViewHolder(binding.root)

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private fun formatTime(ts: Long?) = ts?.let {
        val millis = if (it < 1_000_000_000_000L) it * 1000 else it
        timeFmt.format(Date(millis))
    } ?: ""

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFireStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        val isUnread = !item.isRead

        // Station Name
        holder.binding.fireStationName.apply {
            text = item.fireStationName ?: "Fire Station"
            setTypeface(null, if (isUnread) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isUnread) Color.BLACK else Color.parseColor("#1A1A1A"))
        }

        // Message text
        holder.binding.uid.apply {
            text = item.responseMessage ?: "Loading…"
            setTypeface(null, if (isUnread) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isUnread) Color.BLACK else Color.parseColor("#1A1A1A"))
        }

        // Timestamp
        holder.binding.timestamp.text = formatTime(item.timestamp)

        holder.itemView.setOnClickListener {
            if (isUnread) {
                // Mark message read in Firestore
                firestore.collection("users")
                    .whereEqualTo("email", item.contact)
                    .get()
                    .addOnSuccessListener { docs ->
                        docs.forEach { doc ->
                            doc.reference.update("messages.${item.uid}.isRead", true)
                        }
                    }
                    .addOnFailureListener {
                        // fallback to RTDB
                        FirebaseDatabase.getInstance().reference
                            .child(item.category?.let { "AllReport/${it.capitalize()}" } ?: "AllReport/FireReport")
                            .child(item.uid).child("messages").get()
                            .addOnSuccessListener { snap ->
                                val updates = mutableMapOf<String, Any?>()
                                snap.children.forEach { m -> updates["${m.key}/isRead"] = true }
                                if (updates.isNotEmpty())
                                    FirebaseDatabase.getInstance().reference
                                        .child(item.category?.let { "AllReport/${it.capitalize()}" } ?: "AllReport/FireReport")
                                        .child(item.uid).child("messages").updateChildren(updates)
                            }
                    }

                item.isRead = true
                notifyItemChanged(position)
                onMarkedRead?.invoke()
            }

            // Open details activity
            val intent = Intent(holder.itemView.context, FireReportResponseActivity::class.java).apply {
                putExtra("UID", item.uid)
                putExtra("CONTACT", item.contact)
                putExtra("NAME", item.reporterName)
                putExtra("INCIDENT_ID", item.uid)
                putExtra("CATEGORY", item.category)
            }
            holder.itemView.context.startActivity(intent)
        }
    }


    override fun getItemCount(): Int = list.size
}
