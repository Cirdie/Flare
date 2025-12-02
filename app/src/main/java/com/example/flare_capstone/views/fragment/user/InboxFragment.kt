package com.example.flare_capstone.views.fragment.user

import android.R
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
import com.google.firebase.firestore.FirebaseFirestore

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
    private val firestore = FirebaseFirestore.getInstance()

    private val FIRE_NODE = "AllReport/FireReport"
    private val OTHER_NODE = "AllReport/OtherEmergencyReport"
    private val EMS_NODE = "AllReport/EmergencyMedicalServicesReport"
    private val SMS_NODE = "AllReport/SmsReport"

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

        val categories = listOf(
            "All Report", "Fire Report", "Other Emergency Report",
            "Emergency Medical Services Report", "Sms Report"
        )
        binding.categoryDropdown.setAdapter(
            ArrayAdapter(requireContext(), R.layout.simple_list_item_1, categories)
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

        loadUserAndAttach()
    }

    private fun loadUserAndAttach() {
        val userEmail = FirebaseAuth.getInstance().currentUser?.email
        if (userEmail == null) {
            Toast.makeText(context, "User not logged in", Toast.LENGTH_SHORT).show()
            Log.d("InboxFragment", "User not logged in")
            return
        }

        Log.d("InboxFragment", "Current user email: $userEmail")

        firestore.collection("users")
            .whereEqualTo("email", userEmail)
            .get()
            .addOnSuccessListener { docs ->
                if (docs.isEmpty) {
                    Toast.makeText(context, "User data not found", Toast.LENGTH_SHORT).show()
                    Log.d("InboxFragment", "No Firestore user document found for $userEmail")
                    return@addOnSuccessListener
                }
                val userDocId = docs.documents.first().id
                Log.d("InboxFragment", "Current userDocId: $userDocId")
                attachAllReportListeners(userDocId)
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to load user data", Toast.LENGTH_SHORT).show()
                Log.e("InboxFragment", "Error fetching userDocId: ${e.message}")
            }
    }

    private fun attachAllReportListeners(userDocId: String) {
        if (_binding == null) return  // <-- Add this check
        detachAllListeners()
        allMessages.clear()
        visibleMessages.clear()
        adapter.notifyDataSetChanged()
        binding.noMessagesText.visibility = View.VISIBLE
        updateInboxBadge(0)

        val reportNodes = listOf(FIRE_NODE, OTHER_NODE, EMS_NODE, SMS_NODE)

        reportNodes.forEach { nodePath ->
            val query = database.child(nodePath)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (_binding == null) return // <-- Add this check
                    // existing code...
                }
                override fun onCancelled(error: DatabaseError) {}
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
                CategoryFilter.EMS -> it.category == "ems"
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
