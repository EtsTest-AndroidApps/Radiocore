package com.radiocore.app.activity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import com.radiocore.app.R
import com.radiocore.core.di.DaggerAndroidXAppCompatActivity

/**
 * We just use this class to show a fake progress for 1/8th of a second and just proceeds to [MainActivity] and dismiss it
 * [MainActivity] will be our landing page and will house all our fragments and other destinations.
 */
class SplashActivity : DaggerAndroidXAppCompatActivity(R.layout.splash) {
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler().postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 800)
    }
}
