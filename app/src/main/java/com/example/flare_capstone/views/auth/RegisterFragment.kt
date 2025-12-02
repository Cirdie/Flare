package com.example.flare_capstone.views.auth

import android.os.Bundle
import android.text.InputFilter
import android.text.method.DigitsKeyListener
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.flare_capstone.databinding.FragmentRegisterBinding
import com.example.flare_capstone.dialog.VerifyEmailDialogFragment
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.example.flare_capstone.R

class RegisterFragment : Fragment(R.layout.fragment_register) {

    private lateinit var binding: FragmentRegisterBinding
    private lateinit var auth: FirebaseAuth
    private val firestore = FirebaseFirestore.getInstance()

    private val CONTACT_REGEX = Regex("^09\\d{9}$")
    private val PASSWORD_REGEX =
        Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9])(?=\\S+$).{8,}$")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        FirebaseApp.initializeApp(requireContext())

        binding = FragmentRegisterBinding.inflate(inflater, container, false)

        auth = FirebaseAuth.getInstance()

        binding.contact.keyListener = DigitsKeyListener.getInstance("0123456789")
        binding.contact.filters = arrayOf(InputFilter.LengthFilter(11))

        setupPasswordToggle(binding.password)
        setupPasswordToggle(binding.confirmPassword)

        binding.loginButton.setOnClickListener {
            // Navigate to Login Fragment or Activity
        }

        binding.logo.setOnClickListener {
            // Navigate to Main Activity or Fragment
        }

        binding.register.setOnClickListener { onRegisterClicked() }

        return binding.root
    }

    private fun onRegisterClicked() {
        val name = binding.name.text.toString().trim()
        val email = binding.email.text.toString().trim().lowercase()
        val contact = binding.contact.text.toString().trim()
        val password = binding.password.text.toString()
        val confirmPassword = binding.confirmPassword.text.toString()

        if (name.isEmpty() || email.isEmpty() || contact.isEmpty() ||
            password.isEmpty() || confirmPassword.isEmpty()
        ) {
            toast("Please fill all fields")
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.email.error = "Invalid email"
            toast("Invalid email format")
            return
        }

        if (!CONTACT_REGEX.matches(contact)) {
            binding.contact.error = "Invalid phone format"
            toast("Contact must start with 09 and be 11 digits")
            return
        }

        if (!PASSWORD_REGEX.matches(password)) {
            binding.password.error = "Weak password"
            toast("Password must contain upper, lower, number, special")
            return
        }

        if (password != confirmPassword) {
            binding.confirmPassword.error = "Passwords don't match"
            toast("Passwords don't match")
            return
        }

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    toast("Registration failed: ${task.exception?.message}")
                    return@addOnCompleteListener
                }

                val user = auth.currentUser
                if (user == null) {
                    toast("User session error. Try again.")
                    return@addOnCompleteListener
                }

                user.sendEmailVerification()
                    .addOnSuccessListener {
                        toast("Verification email sent to $email")

                        val data = hashMapOf(
                            "uid" to user.uid,
                            "name" to name,
                            "email" to email,
                            "contact" to contact,
                            "status" to "unverified",
                            "createdAt" to FieldValue.serverTimestamp(),
                            "verifiedAt" to null,
                            "role" to "com/example/flare_capstone/views/BFP/User"
                        )

                        firestore.collection("users")
                            .document(user.uid)
                            .set(data)
                            .addOnSuccessListener {
                                val dialog = VerifyEmailDialogFragment()
                                dialog.show(parentFragmentManager, "VerifyEmailDialog")
                            }
                            .addOnFailureListener { e ->
                                toast("Failed to save user: ${e.message}")
                            }
                    }
                    .addOnFailureListener { e ->
                        toast("Failed to send verification: ${e.message}")
                    }
            }
    }

    private fun setupPasswordToggle(editText: EditText) {
        val visibleIcon = android.R.drawable.ic_menu_view
        val hiddenIcon = android.R.drawable.ic_secure
        setEndIcon(editText, hiddenIcon)

        editText.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawable = editText.compoundDrawables[2]
                drawable?.let {
                    val start = editText.width - editText.paddingRight - it.intrinsicWidth
                    if (event.x >= start) {
                        togglePasswordVisibility(editText, visibleIcon, hiddenIcon)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    private fun togglePasswordVisibility(editText: EditText, show: Int, hide: Int) {
        val cursor = editText.selectionEnd
        if (editText.transformationMethod is PasswordTransformationMethod) {
            editText.transformationMethod = null
            setEndIcon(editText, show)
        } else {
            editText.transformationMethod = PasswordTransformationMethod.getInstance()
            setEndIcon(editText, hide)
        }
        editText.setSelection(cursor)
    }

    private fun setEndIcon(editText: EditText, iconRes: Int) {
        editText.setCompoundDrawablesWithIntrinsicBounds(0, 0, iconRes, 0)
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
