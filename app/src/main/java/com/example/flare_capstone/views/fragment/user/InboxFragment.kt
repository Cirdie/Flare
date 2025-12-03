package com.example.flare_capstone.views.fragment.user

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.flare_capstone.views.activity.UserActivity
import com.example.flare_capstone.data.model.ResponseMessage
import com.example.flare_capstone.adapter.ResponseMessageAdapter
import com.example.flare_capstone.databinding.FragmentInboxBinding
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class InboxFragment : Fragment() {

    private var _binding: FragmentInboxBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ResponseMessageAdapter
    private val allMessages = mutableListOf<ResponseMessage>()
    private val visibleMessages = mutableListOf<ResponseMessage>()
    private val liveListeners = mutableListOf<Pair<Query, ValueEventListener>>()

    private enum class CategoryFilter { ALL, FIRE, OTHER, EMS, SMS }
    private var currentCategoryFilter = CategoryFilter.ALL

    private enum class FilterMode { ALL, READ, UNREAD }
    private var currentFilter = FilterMode.ALL

    private var unreadMessageCount = 0
    private lateinit var database: DatabaseReference

    private val reportNodes = listOf(
        "AllReport/FireReport",
        "AllReport/OtherEmergencyReport",
        "AllReport/EmergencyMedicalServicesReport",
        "AllReport/SmsReport"
    )

    private var currentUserEmail: String? = null
    private var currentUserDocId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        database = FirebaseDatabase.getInstance().reference

        adapter = ResponseMessageAdapter(visibleMessages) {
            applyFilter()
            unreadMessageCount = allMessages.count { !it.isRead }
            updateInboxBadge(unreadMessageCount)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        setupTabsAndDropdown()
        loadCurrentUser()
    }

    private fun setupTabsAndDropdown() {
        // Tabs for read/unread
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentFilter = when (tab.position) {
                    1 -> FilterMode.READ
                    2 -> FilterMode.UNREAD
                    else -> FilterMode.ALL
                }
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Category dropdown
        val categories = listOf(
            "All Report", "Fire Report", "Other Emergency Report",
            "Emergency Medical Services Report", "Sms Report"
        )
        binding.categoryDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, categories)
        )
        binding.categoryDropdown.setText("All Report", false)
        binding.categoryDropdown.setOnItemClickListener { _, _, pos, _ ->
            currentCategoryFilter = when (pos) {
                1 -> CategoryFilter.FIRE
                2 -> CategoryFilter.OTHER
                3 -> CategoryFilter.EMS
                4 -> CategoryFilter.SMS
                else -> CategoryFilter.ALL
            }
            applyFilter()
        }
    }

    private fun loadCurrentUser() {
        currentUserEmail = FirebaseAuth.getInstance().currentUser?.email
        if (currentUserEmail == null) {
            Toast.makeText(context, "User not logged in", Toast.LENGTH_SHORT).show()
            Log.d("InboxFragment", "User not logged in")
            return
        }

        Log.d("InboxFragment", "Current user email: $currentUserEmail")

        // We assume userDocId = email for matching (if not, query Firestore users collection)
        currentUserDocId = currentUserEmail
        attachReportListeners()
    }


    private fun attachReportListeners() {
        if (_binding == null) return
        detachAllListeners()
        allMessages.clear()
        visibleMessages.clear()
        adapter.notifyDataSetChanged()
        binding.noMessagesText.visibility = View.VISIBLE
        updateInboxBadge(0)

        reportNodes.forEach { nodePath ->
            val query = database.child(nodePath)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (_binding == null) return

                    Log.d("InboxFragment", "Node updated: $nodePath, children: ${snapshot.childrenCount}")

                    // Remove old messages for this category
                    val category = when (nodePath.substringAfterLast("/")) {
                        "FireReport" -> "fire"
                        "OtherEmergencyReport" -> "other"
                        "EmergencyMedicalServicesReport" -> "ems"
                        "SmsReport" -> "sms"
                        else -> "all"
                    }

                    allMessages.removeAll { it.category == category }

                    snapshot.children.forEach { reportSnap ->
                        // Check if report belongs to current user
                        val reporterEmail = reportSnap.child("email").getValue(String::class.java)
                        val fireStationName = reportSnap.child("fireStationName").getValue(String::class.java)
                        val reporterName = reportSnap.child("name").getValue(String::class.java)
                        if (reporterEmail != currentUserEmail) return@forEach

                        // Find the latest message from station
                        val messagesSnap = reportSnap.child("messages")
                        var latestMsgSnap: DataSnapshot? = null
                        var latestTs: Long = 0L

                        messagesSnap.children.forEach { msgSnap ->
                            val ts = msgSnap.child("timestamp").getValue(Long::class.java) ?: 0L
                            if (ts > latestTs) {
                                latestTs = ts
                                latestMsgSnap = msgSnap
                            }
                        }

                        latestMsgSnap?.let { msgSnap ->
                            val text = msgSnap.child("text").getValue(String::class.java) ?: "No message provided"
                            val messageType = msgSnap.child("messageType").getValue(String::class.java) ?: "No type"
                            val sender = msgSnap.child("sender").getValue(String::class.java) ?: "No sender"

                            val msg = ResponseMessage(
                                uid = msgSnap.key,
                                responseMessage = text,
                                messageType = messageType,
                                reporterName = reporterName,
                                fireStationName = fireStationName,
                                sender = sender,
                                userEmail = currentUserDocId,
                                timestamp = latestTs,
                                isRead = msgSnap.child("isRead").getValue(Boolean::class.java) ?: false,
                                category = category,
                                incidentId = reportSnap.key
                            )

                            allMessages.add(msg)
                        }
                    }

                    applyFilter()
                    unreadMessageCount = allMessages.count { !it.isRead }
                    updateInboxBadge(unreadMessageCount)
                    Log.d("InboxFragment", "Total messages: ${allMessages.size}, Visible: ${visibleMessages.size}")
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("InboxFragment", "Failed to load node $nodePath: ${error.message}")
                }
            }

            query.addValueEventListener(listener)
            liveListeners.add(query to listener)
        }
    }

    private fun applyFilter() {
        if (_binding == null) return
        visibleMessages.clear()

        val base = when (currentFilter) {
            FilterMode.ALL -> allMessages
            FilterMode.READ -> allMessages.filter { it.isRead }
            FilterMode.UNREAD -> allMessages.filter { !it.isRead }
        }

        val filtered = base.filter {
            when (currentCategoryFilter) {
                CategoryFilter.ALL -> true
                CategoryFilter.FIRE -> it.category == "fire"
                CategoryFilter.OTHER -> it.category == "other"
                CategoryFilter.EMS -> it.category == "emergencymedicalservicesreport" || it.category == "ems"
                CategoryFilter.SMS -> it.category == "sms"
            }
        }

        visibleMessages.addAll(filtered.sortedByDescending { it.timestamp ?: 0L })
        adapter.notifyDataSetChanged()
        binding.noMessagesText.visibility = if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateInboxBadge(count: Int) {
        (activity as? UserActivity)?.let { act ->
            val badge = act.binding.bottomNavigation.getOrCreateBadge(com.example.flare_capstone.R.id.inboxFragment)
            badge.isVisible = count > 0
            badge.number = count
            badge.maxCharacterCount = 3
        }
    }

    private fun detachAllListeners() {
        liveListeners.forEach { (q, l) -> runCatching { q.removeEventListener(l) } }
        liveListeners.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        detachAllListeners()
        _binding = null
    }
}
