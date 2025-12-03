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
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class ResponseMessageAdapter(
    private val list: MutableList<ResponseMessage>,
    private val onMarkedRead: (() -> Unit)? = null
) : RecyclerView.Adapter<ResponseMessageAdapter.ViewHolder>() {

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    private fun formatTime(ts: Long?): String {
        ts ?: return ""
        val millis = if (ts < 1_000_000_000_000L) ts * 1000 else ts
        return timeFmt.format(Date(millis))
    }

    inner class ViewHolder(val binding: ItemFireStationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFireStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        val isUnread = !item.isRead

        holder.binding.fireStationName.apply {
            text = item.fireStationName ?: "Fire Station"
            setTypeface(null, if (isUnread) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isUnread) Color.BLACK else Color.parseColor("#1A1A1A"))
        }

        // Determine message display text
        val displayText = when (item.messageType) {
            "audio" -> if (item.sender?.lowercase() == "user") "You: Sent a voice message" else "Sent a voice message"
            "image" -> if (item.sender?.lowercase() == "user") "You: Sent an image" else "Sent an image"
            "emoji" -> if (item.sender?.lowercase() == "user") "You: Sent an emoji" else "Sent an emoji"
            else -> if (item.sender?.lowercase() == "user") "You: ${item.responseMessage}" else item.responseMessage ?: "No message"
        }


        holder.binding.uid.apply {
            text = displayText
            setTypeface(null, if (isUnread) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isUnread) Color.BLACK else Color.parseColor("#1A1A1A"))
        }

        holder.binding.timestamp.text = formatTime(item.timestamp)

        holder.itemView.setOnClickListener {
            if (isUnread) {
                // Mark message as read in Realtime DB
                item.stationNode?.let { node ->
                    FirebaseDatabase.getInstance().reference
                        .child("AllReport/${item.category?.capitalize()}")
                        .child(node).child("messages").child(item.uid.toString())
                        .child("isRead").setValue(true)
                }

                item.isRead = true
                notifyItemChanged(position)
                onMarkedRead?.invoke()
            }

            // Open details
            val intent = Intent(holder.itemView.context, FireReportResponseActivity::class.java).apply {
                putExtra("INCIDENT_ID", item.incidentId)
                putExtra("USER_EMAIL", item.userEmail)
                putExtra("REPORTER_NAME", item.reporterName)
                putExtra("UID", item.uid)
                putExtra("NAME", item.fireStationName)
                putExtra("CATEGORY", item.category)
            }
            holder.itemView.context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = list.size
}
