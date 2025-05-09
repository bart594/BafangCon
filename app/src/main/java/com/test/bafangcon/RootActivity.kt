package com.test.bafangcon

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.test.bafangcon.databinding.ActivityRootBinding

class RootActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRootBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            // Start with ScanFragment
            navigateToScanFragment()
        }
    }

    fun navigateToScanFragment() {
        supportFragmentManager.commit {
            replace(R.id.fragment_container, ScanFragment::class.java, null)
            // Don't add to back stack if it's the initial fragment or if coming back from main
            // setReorderingAllowed(true) // Optional
        }
    }

    fun navigateToMainFragment() {
        supportFragmentManager.commit {
            replace(R.id.fragment_container, MainFragment::class.java, null)
            // Optionally add ScanFragment to the back stack so back button goes back to scan
            // addToBackStack("scan_to_main")
            // setReorderingAllowed(true)
        }
    }


    fun navigateToControllerInfoFragment() {
        supportFragmentManager.commit {
            replace(R.id.fragment_container, ControllerInfoFragment::class.java, null)
            addToBackStack("main_to_controller") // Add transaction to back stack
            setReorderingAllowed(true)
        }
    }
    fun navigateToMeterInfoFragment() {
        supportFragmentManager.commit {
            replace(R.id.fragment_container, MeterInfoFragment::class.java, null) // Change to MeterInfoFragment when created
            addToBackStack("main_to_meter")
            setReorderingAllowed(true)
        }
    }

    fun navigateToGearsInfoFragment() {
        supportFragmentManager.commit {
            replace(R.id.fragment_container, GearsInfoFragment::class.java, null) // Change to GearsInfoFragment when created
            addToBackStack("main_to_gears")
            setReorderingAllowed(true)
        }
    }



    // Handle back press if needed (e.g., to prevent going back from Scan screen)
    // override fun onBackPressed() {
    //     val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
    //     if (currentFragment is MainFragment) {
    //         // If on main screen, disconnect and go back to scan? Or exit?
    //         // viewModel.disconnect() // Maybe handle disconnect here?
    //         super.onBackPressed() // Or navigateToScanFragment()
    //     } else {
    //          super.onBackPressed() // Default behavior (e.g., exit from ScanFragment)
    //     }
    // }
}