package com.example.flare_capstone.views.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.ActivityInvestigatorBinding
import np.com.susanthapa.curved_bottom_navigation.CbnMenuItem

class InvestigatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInvestigatorBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInvestigatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()
    }

    private fun setupBottomNavigation() {
        // Get the nav controller
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Bottom nav items matching your fragments & nav graph destinations
        val menuItems = arrayOf(
            CbnMenuItem(R.drawable.ic_home, R.drawable.avd_home , R.id.inHomeFragment),
            CbnMenuItem(R.drawable.ic_services, R.drawable.ic_services, R.id.reportFlow),
            CbnMenuItem(R.drawable.ic_dashboard, R.drawable.avd_dashboard, R.id.inInboxFragment),
            CbnMenuItem(R.drawable.ic_profile, R.drawable.avd_profile, R.id.inPorfileFragment)
        )

        // Setup menu
        binding.investigatorNavView.setMenuItems(menuItems, 0)

        // Connect bottom nav to navController
        binding.investigatorNavView.setupWithNavController(navController)
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
