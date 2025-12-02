package com.example.flare_capstone.views.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.FragmentLoginBinding
import com.example.flare_capstone.dialog.VerifyEmailDialogFragment
import com.example.flare_capstone.views.activity.UserActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class LoginFragment : Fragment(R.layout.fragment_login) {

    private lateinit var binding: FragmentLoginBinding
    private lateinit var auth: FirebaseAuth
    private val firestore = FirebaseFirestore.getInstance()
    private var verificationListener: ListenerRegistration? = null
    private var loadingDialog: android.app.AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        FirebaseApp.initializeApp(requireContext())

        binding = FragmentLoginBinding.inflate(inflater, container, false)

        auth = FirebaseAuth.getInstance()
        setupPasswordToggle(binding.password)

        binding.loginButton.setOnClickListener { onLoginClicked() }
        binding.registerButton.setOnClickListener {
            // You can navigate to RegisterFragment or a new Activity here
        }
        binding.forgotPassword.setOnClickListener { onForgotPassword() }

        return binding.root
    }

    override fun onStart() {
        super.onStart()
        attachVerificationListener()
    }

    override fun onStop() {
        super.onStop()
        verificationListener?.remove()
    }

    private fun attachVerificationListener() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        verificationListener = firestore.collection("users")
            .document(uid)
            .addSnapshotListener { snap, err ->

                if (err != null || snap == null || !snap.exists()) return@addSnapshotListener

                val status = snap.getString("status") ?: return@addSnapshotListener

                if (status == "verified") {
                    if (snap.getTimestamp("verifiedAt") == null) {
                        firestore.collection("users")
                            .document(uid)
                            .update("verifiedAt", Timestamp.now())
                    }

                    val dialog = parentFragmentManager.findFragmentByTag("VerifyEmailDialog")
                    if (dialog is VerifyEmailDialogFragment) {
                        dialog.dismissAllowingStateLoss()
                    }

                    routeToDashboard()
                }
            }
    }

    private fun onLoginClicked() {
        val email = binding.email.text.toString().trim().lowercase()
        val password = binding.password.text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            toast("Please fill in both fields")
            return
        }

        setLoginEnabled(false)
        showLoadingDialog()

        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                hideLoadingDialog()
                attachVerificationListener()
                checkEmailStatus()
            }
            .addOnFailureListener { e ->
                hideLoadingDialog()
                setLoginEnabled(true)
                toast(
                    when (e) {
                        is FirebaseAuthInvalidCredentialsException -> "Incorrect password."
                        is FirebaseAuthInvalidUserException -> "Account not found."
                        else -> e.message ?: "Login failed."
                    }
                )
            }
    }

    private fun checkEmailStatus() {
        val user = auth.currentUser ?: return

        user.reload().addOnSuccessListener {
            if (user.isEmailVerified) {
                val userDoc = firestore.collection("users").document(user.uid)

                userDoc.update(
                    mapOf(
                        "status" to "verified",
                        "verifiedAt" to Timestamp.now()
                    )
                )

                loginVerifiedUser(user.uid)
            } else {
                showVerifyDialogOnly()
                setLoginEnabled(true)
            }
        }
    }

    private fun loginVerifiedUser(uid: String) {
        firestore.collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    toast("Account data missing.")
                    auth.signOut()
                    setLoginEnabled(true)
                    return@addOnSuccessListener
                }

                val status = doc.getString("status") ?: "unverified"

                if (status != "verified") {
                    toast("Your account is still pending verification.")
                    setLoginEnabled(true)
                    return@addOnSuccessListener
                }

                routeToDashboard()
            }
            .addOnFailureListener {
                toast("Failed to fetch user: ${it.message}")
                setLoginEnabled(true)
            }
    }

    private fun showVerifyDialogOnly() {
        val dialog = VerifyEmailDialogFragment()
        dialog.show(parentFragmentManager, "VerifyEmailDialog")
    }

    private fun routeToDashboard() {
        startActivity(Intent(requireContext(), UserActivity::class.java))
    }

    private fun setLoginEnabled(enabled: Boolean) {
        binding.loginButton.isEnabled = enabled
        binding.loginButton.alpha = if (enabled) 1f else 0.6f
    }

    private fun onForgotPassword() {
        val email = binding.email.text.toString().trim().lowercase()

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("Enter a valid email.")
            return
        }

        startForgotPasswordCooldown()

        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                toast("Reset link sent to $email")
            }
            .addOnFailureListener {
                toast("Reset failed: ${it.message}")
                resetForgotPasswordButton()
            }
    }

    private fun startForgotPasswordCooldown() {
        binding.forgotPassword.isEnabled = false

        object : CountDownTimer(100000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                binding.forgotPassword.text = "Try again in ${seconds}s"
            }

            override fun onFinish() {
                resetForgotPasswordButton()
            }
        }.start()
    }

    private fun resetForgotPasswordButton() {
        binding.forgotPassword.isEnabled = true
        binding.forgotPassword.text = "Forgot password?"
    }

    private fun showLoadingDialog() {
        if (loadingDialog != null && loadingDialog!!.isShowing) return

        val view = layoutInflater.inflate(R.layout.progress_dialog, null)

        loadingDialog = android.app.AlertDialog.Builder(requireContext(), R.style.TransparentDialog)
            .setView(view)
            .setCancelable(false)
            .create()

        loadingDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        loadingDialog?.show()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            loadingDialog?.dismiss()
            loadingDialog = null
        }, 5000)
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun setupPasswordToggle(editText: EditText) {
        val visibleIcon = R.drawable.ic_visibility
        val hiddenIcon = R.drawable.ic_visibility_off

        editText.transformationMethod = PasswordTransformationMethod.getInstance()
        editText.setCompoundDrawablesWithIntrinsicBounds(0, 0, hiddenIcon, 0)

        editText.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableEnd = editText.compoundDrawables[2]

                if (drawableEnd != null &&
                    event.rawX >= (editText.right - drawableEnd.bounds.width())
                ) {
                    if (editText.transformationMethod is PasswordTransformationMethod) {
                        editText.transformationMethod = null
                        editText.setCompoundDrawablesWithIntrinsicBounds(0, 0, visibleIcon, 0)
                    } else {
                        editText.transformationMethod = PasswordTransformationMethod.getInstance()
                        editText.setCompoundDrawablesWithIntrinsicBounds(0, 0, hiddenIcon, 0)
                    }

                    editText.setSelection(editText.text.length)
                    return@setOnTouchListener true
                }
            }
            false
        }
    }
}
