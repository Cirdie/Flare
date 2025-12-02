package com.example.flare_capstone.views.fragment.user

import android.Manifest
import android.animation.LayoutTransition
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.ActivityFireReportResponseBinding
import com.example.flare_capstone.views.activity.UserActivity
import com.google.firebase.database.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FireReportResponseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFireReportResponseBinding
    private lateinit var database: DatabaseReference

    private lateinit var uid: String
    private lateinit var reporterName: String
    private lateinit var incidentId: String
    private var fromNotification: Boolean = false

    private var base64Image: String = ""
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var isRecording = false
    private var isPaused = false
    private var pauseStartMs = 0L
    private var recordStartMs = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private lateinit var messagesListener: ValueEventListener

    companion object {
        const val CAMERA_REQUEST_CODE = 100
        const val CAMERA_PERMISSION_REQUEST_CODE = 101
        const val GALLERY_REQUEST_CODE = 102
        const val RECORD_AUDIO_PERMISSION_CODE = 103

        const val FIRE_REPORT = "AllReport/FireReport"
        const val OTHER_REPORT = "AllReport/OtherEmergencyReport"
        const val EMS_REPORT = "AllReport/MedicalServicesReport"
        const val SMS_REPORT = "AllReport/SmsReport"
    }

    private var reportNode: String = FIRE_REPORT

    data class ChatMessage(
        var type: String? = null,
        var text: String? = null,
        var imageBase64: String? = null,
        var audioBase64: String? = null,
        var uid: String? = null,
        var reporterName: String? = null,
        var timestamp: Long? = null,
        var isRead: Boolean? = false
    )

    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - recordStartMs
            val sec = (elapsed / 1000).toInt()
            val mm = sec / 60
            val ss = sec % 60
            binding.recordTimer.text = String.format("%02d:%02d", mm, ss)
            timerHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFireReportResponseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance().reference

        uid = intent.getStringExtra("UID") ?: ""
        reporterName = intent.getStringExtra("REPORTER_NAME") ?: "User"
        incidentId = intent.getStringExtra("INCIDENT_ID") ?: ""
        fromNotification = intent.getBooleanExtra("fromNotification", false)
        reportNode = intent.getStringExtra("REPORT_NODE") ?: FIRE_REPORT

        binding.fireStationName.text = reporterName

        if (incidentId.isEmpty()) {
            Toast.makeText(this, "No Incident ID provided.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        markMessagesAsRead()
        attachMessagesListener()
        setupInputUI()
        setupMediaButtons()

        binding.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    // -----------------------------
    // Input UI
    // -----------------------------
    private fun setupInputUI() {
        binding.chatInputArea.layoutTransition?.enableTransitionType(LayoutTransition.CHANGING)

        binding.messageInput.setOnFocusChangeListener { _, hasFocus ->
            val expanded = hasFocus || binding.messageInput.text?.isNotBlank() == true
            setTypingUi(expanded)
            setExpandedUi(expanded)
        }

        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val expanded = !s.isNullOrBlank() || binding.messageInput.hasFocus()
                setTypingUi(expanded)
                setExpandedUi(expanded)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        ViewCompat.setOnApplyWindowInsetsListener(binding.chatInputArea) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val expanded = imeVisible || binding.messageInput.text?.isNotBlank() == true
            setTypingUi(expanded)
            setExpandedUi(expanded)
            insets
        }

        binding.arrowBackIcon.setOnClickListener {
            binding.messageInput.clearFocus()
            binding.chatInputArea.hideKeyboard()
            setExpandedUi(false)
        }

        binding.sendButton.setOnClickListener {
            val userMessage = binding.messageInput.text.toString().trim()
            when {
                userMessage.isNotEmpty() && base64Image.isNotEmpty() -> {
                    pushChatMessage(type = "reply", text = userMessage, imageBase64 = base64Image)
                }
                userMessage.isNotEmpty() -> {
                    pushChatMessage(type = "reply", text = userMessage)
                }
                base64Image.isNotEmpty() -> {
                    pushChatMessage(type = "reply", text = "", imageBase64 = base64Image)
                }
                else -> Toast.makeText(this, "Message or image required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setExpandedUi(expanded: Boolean) {
        binding.cameraIcon.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.galleryIcon.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.voiceRecordIcon.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.arrowBackIcon.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.messageInput.maxLines = if (expanded) 5 else 3
    }

    private fun setTypingUi(isTyping: Boolean) {
        val hasText = binding.messageInput.text?.isNotBlank() == true
        binding.sendButton.isEnabled = hasText
        binding.sendButton.alpha = if (hasText) 1f else 0.4f
    }

    private fun View.hideKeyboard() {
        val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    // -----------------------------
    // Media buttons
    // -----------------------------
    private fun setupMediaButtons() {
        binding.cameraIcon.setOnClickListener { openCameraWithPermission() }
        binding.galleryIcon.setOnClickListener { openGallery() }
        binding.voiceRecordIcon.setOnClickListener { startRecordingWithPermission() }
        binding.recordPause.setOnClickListener { togglePauseResume() }
        binding.recordCancel.setOnClickListener { cancelRecording() }
        binding.recordSend.setOnClickListener { finishRecordingAndSend() }
    }

    // -----------------------------
    // Camera & Gallery
    // -----------------------------
    private fun openCameraWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) openCamera()
        else ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    private fun openCamera() {
        startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), CAMERA_REQUEST_CODE)
    }

    private fun openGallery() {
        startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), GALLERY_REQUEST_CODE)
    }

    // -----------------------------
    // Recording
    // -----------------------------
    private fun startRecordingWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startRecording()
        else ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_PERMISSION_CODE
        )
    }

    private fun startRecording() {
        try {
            recordFile = File.createTempFile("voice_", ".m4a", cacheDir)
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recordFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            isPaused = false
            recordStartMs = System.currentTimeMillis()
            binding.recordTimer.text = "00:00"
            timerHandler.post(timerRunnable)
            binding.recordBar.visibility = View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            cleanupRecorder()
        }
    }

    private fun togglePauseResume() {
        if (!isRecording || Build.VERSION.SDK_INT < 24) return
        try {
            if (!isPaused) {
                recorder?.pause()
                isPaused = true
                pauseStartMs = System.currentTimeMillis()
                timerHandler.removeCallbacks(timerRunnable)
                binding.recordPause.setImageResource(R.drawable.ic_resume)
            } else {
                recorder?.resume()
                isPaused = false
                recordStartMs += System.currentTimeMillis() - pauseStartMs
                pauseStartMs = 0L
                binding.recordPause.setImageResource(R.drawable.ic_pause)
                timerHandler.post(timerRunnable)
            }
        } catch (_: Exception) {}
    }

    private fun cancelRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        cleanupRecorder()
        recordFile?.delete()
        isRecording = false
        isPaused = false
        timerHandler.removeCallbacks(timerRunnable)
        binding.recordBar.visibility = View.GONE
    }

    private fun finishRecordingAndSend() {
        val file = recordFile ?: return
        try { recorder?.stop() } catch (_: Exception) {}
        cleanupRecorder()
        isRecording = false
        isPaused = false
        timerHandler.removeCallbacks(timerRunnable)
        binding.recordBar.visibility = View.GONE

        val audioB64 = Base64.encodeToString(file.readBytes(), Base64.DEFAULT)
        pushChatMessage(type = "reply", text = "", audioBase64 = audioB64)
        file.delete()
    }

    private fun cleanupRecorder() { recorder?.release(); recorder = null }

    // -----------------------------
    // Firebase messages
    // -----------------------------
    private fun messagesPath(): DatabaseReference =
        database.child(reportNode).child(incidentId).child("messages")

    private fun attachMessagesListener() {
        messagesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                binding.scrollContent.removeAllViews()
                snapshot.children.forEach { ds ->
                    val msg = ds.getValue(ChatMessage::class.java) ?: return@forEach
                    displayMessage(msg)
                    if (msg.isRead == false) ds.ref.child("isRead").setValue(true)
                }
                binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@FireReportResponseActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
        messagesPath().addValueEventListener(messagesListener)
    }

    private fun pushChatMessage(
        type: String,
        text: String = "",
        imageBase64: String = "",
        audioBase64: String = ""
    ) {
        val now = System.currentTimeMillis()
        val msg = ChatMessage(type, text.takeIf { it.isNotBlank() }, imageBase64.takeIf { it.isNotBlank() }, audioBase64.takeIf { it.isNotBlank() }, uid, reporterName, now, false)
        messagesPath().push().setValue(msg)
        base64Image = ""
        binding.messageInput.text.clear()
    }

    private fun displayMessage(msg: ChatMessage) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 15, 20, 15)
            gravity = if (msg.type.equals("reply", true)) Gravity.END else Gravity.START
        }

        msg.text?.takeIf { it.isNotEmpty() }?.let {
            val tv = TextView(this).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Color.WHITE)
                setPadding(20, 15, 20, 15)
                background = resources.getDrawable(
                    if (msg.type.equals("reply", true)) R.drawable.received_message_bg else R.drawable.sent_message_bg,
                    null
                )
            }
            layout.addView(tv)
        }

        msg.imageBase64?.let { base64 ->
            convertBase64ToBitmap(base64)?.let { bitmap ->
                val iv = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    layoutParams = LinearLayout.LayoutParams(
                        (resources.displayMetrics.density * 250).toInt(),
                        (resources.displayMetrics.density * 200).toInt()
                    )
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                layout.addView(iv)
            }
        }

        msg.audioBase64?.let { audioB64 ->
            val playBtn = TextView(this).apply {
                text = "▶️ Play Voice Message"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Color.WHITE)
                setPadding(20, 15, 20, 15)
                background = resources.getDrawable(
                    if (msg.type.equals("reply", true)) R.drawable.received_message_bg else R.drawable.sent_message_bg,
                    null
                )
                setOnClickListener {
                    try {
                        val audioBytes = Base64.decode(audioB64, Base64.DEFAULT)
                        val tempFile = File.createTempFile("audio_", ".m4a", cacheDir)
                        tempFile.writeBytes(audioBytes)
                        MediaPlayer().apply {
                            setDataSource(tempFile.absolutePath)
                            prepare()
                            start()
                        }
                    } catch (_: Exception) {}
                }
            }
            layout.addView(playBtn)
        }

        val timestampTv = TextView(this).apply {
            text = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(Date(msg.timestamp ?: System.currentTimeMillis()))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.LTGRAY)
            gravity = if (msg.type.equals("reply", true)) Gravity.END else Gravity.START
        }
        layout.addView(timestampTv)

        binding.scrollContent.addView(layout)
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun convertBase64ToBitmap(base64: String?): Bitmap? {
        if (base64.isNullOrEmpty()) return null
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun markMessagesAsRead() {
        messagesPath().orderByChild("isRead").equalTo(false)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.forEach { it.ref.child("isRead").setValue(true) }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::messagesListener.isInitialized) messagesPath().removeEventListener(messagesListener)
    }

    override fun onBackPressed() {
        if (fromNotification) {
            val intent = Intent(this, UserActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        } else super.onBackPressed()
    }
}
