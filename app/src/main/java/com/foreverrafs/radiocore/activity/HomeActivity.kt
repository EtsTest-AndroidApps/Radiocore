package com.foreverrafs.radiocore.activity

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager.widget.ViewPager
import com.crashlytics.android.Crashlytics
import com.foreverrafs.radiocore.BuildConfig
import com.foreverrafs.radiocore.R
import com.foreverrafs.radiocore.adapter.HomeSectionsPagerAdapter
import com.foreverrafs.radiocore.concurrency.CustomObserver
import com.foreverrafs.radiocore.fragment.AboutFragment
import com.foreverrafs.radiocore.fragment.HomeFragment
import com.foreverrafs.radiocore.fragment.NewsFragment
import com.foreverrafs.radiocore.player.StreamPlayer
import com.foreverrafs.radiocore.service.AudioStreamingService
import com.foreverrafs.radiocore.service.AudioStreamingService.AudioStreamingState
import com.foreverrafs.radiocore.util.Constants
import com.foreverrafs.radiocore.util.Constants.STREAM_RESULT
import com.foreverrafs.radiocore.util.RadioPreferences
import com.foreverrafs.radiocore.util.Tools
import com.foreverrafs.radiocore.util.Tools.animateButtonDrawable
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import io.fabric.sdk.android.Fabric
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import kotlinx.android.synthetic.main.activity_home.*
import kotlinx.android.synthetic.main.bottom_sheet.*
import org.joda.time.Seconds

class HomeActivity : AppCompatActivity(), View.OnClickListener {
    override fun onClick(view: View?) {
        when (view?.id) {
            R.id.btnSmallPlay, R.id.btnPlay -> {
                Log.i(TAG, "onPlay: " + mAudioStreamingState.name)
                when (mAudioStreamingState) {
                    AudioStreamingState.STATUS_PLAYING -> pausePlayback()
                    AudioStreamingState.STATUS_PAUSED,
                    AudioStreamingState.STATUS_STOPPED -> startPlayback()
                    AudioStreamingState.STATUS_LOADING -> Log.d(TAG, "Loading")
                }

            }
        }
    }


    private val TAG = "HomeActivity"
    private val PERMISSION_RECORD_AUDIO = 6900
    ///////////////////////////////////////////////////////////////////////////////////////////////
    private lateinit var mAudioServiceBroadcastReceiver: BroadcastReceiver
    //let's assume nothing is playing when application starts
    private var mAudioStreamingState: AudioStreamingState = AudioStreamingState.STATUS_STOPPED


    private var mSheetBehaviour: BottomSheetBehavior<*>? = null
    private var mCompositeDisposable: CompositeDisposable? = null
    private lateinit var mStreamPlayer: StreamPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        btnSmallPlay.setOnClickListener(this)
        btnPlay.setOnClickListener(this)

        mCompositeDisposable = CompositeDisposable()
        mStreamPlayer = StreamPlayer.getInstance(this)

        setUpCrashlytics()
        initializeViews()
        setUpInitialPlayerState()
        setUpAudioStreamingServiceReceiver()

    }

    private fun setUpCrashlytics() {
        if (!BuildConfig.DEBUG) {
            Fabric.with(this, Crashlytics())
            Log.i(TAG, "setUpCrashlytics: Enabled")
        }
    }

    private fun intiializeAudioVisualizer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioRecordingPermission()
        }

        setUpAudioVisualizer()
    }

    private fun initializeToolbar() {
        setSupportActionBar(toolbar)
        if (supportActionBar != null) {
            supportActionBar!!.title = getString(R.string.app_name)
            Tools.setSystemBarColor(this)
        }
    }

    private fun initializeTabComponents() {
        setupViewPager(viewPager!!)

        tabLayout.setupWithViewPager(viewPager)

        tabLayout.getTabAt(0)!!.setIcon(R.drawable.ic_radio_live)
        tabLayout.getTabAt(1)!!.setIcon(R.drawable.ic_news)
        tabLayout.getTabAt(2)!!.setIcon(R.drawable.ic_about)

        // set icon color pre-selected
        tabLayout.getTabAt(0)!!.icon!!.setTint(Color.RED)
        tabLayout.getTabAt(1)!!.icon!!.setTint(ContextCompat.getColor(this, R.color.grey_20))
        tabLayout.getTabAt(2)!!.icon!!.setTint(ContextCompat.getColor(this, R.color.grey_20))

        tabLayout!!.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                collapseBottomSheet()
                if (tab.position != 0)
                    tab.icon!!.setTint(Color.WHITE)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                if (tab.position != 0)
                    tab.icon!!.setTint(ContextCompat.getColor(applicationContext, R.color.grey_20))
            }

            override fun onTabReselected(tab: TabLayout.Tab) {

            }
        })
    }

    /**
     * Listen for broadcast events from the Audio Streaming Service and use the information to
     * resolve the player state accordingly
     */
    private fun setUpAudioStreamingServiceReceiver() {
        mAudioServiceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "audioBroadCastReceived:" + intent.action)
                if (intent.action == STREAM_RESULT)
                    onAudioStreamingStateReceived(intent)
//                else if (intent.action == STREAM_META_DATA)
//                    onStreamMetaDataReceived(intent)
            }
        }
    }

    /**
     * Check if Audio Streaming Service is running and change the AudioStreamingState accordingly
     * Note: We Initially set it to STATUS_STOPPED, assuming that nothing is playing when we first run
     */
    private fun setUpInitialPlayerState() {
        val radioPreferences = RadioPreferences(this)

        mAudioStreamingState = if (mStreamPlayer.playBackState == StreamPlayer.PlaybackState.PLAYING)
            AudioStreamingState.STATUS_PLAYING
        else
            AudioStreamingState.STATUS_STOPPED


        if (!Tools.isServiceRunning(AudioStreamingService::class.java, this)) {
            if (radioPreferences.isAutoPlayOnStart &&
                    mAudioStreamingState != AudioStreamingState.STATUS_PLAYING)
                startPlayback()

            return
        }
    }

    /**
     * Update the stream progress seekbar and timer accordingly.
     * Also checks if the stream timer is up which triggers a shutdown of the app
     */
    private fun startUpdateStreamProgress() {
        Log.d(TAG, "startUpdateStreamProgress: ")
        mStreamPlayer.streamDurationStringsObservable
                .subscribe(object : CustomObserver<Array<out String?>>() {
                    override fun onSubscribe(d: Disposable) {
                        mCompositeDisposable?.add(d)
                    }

                    override fun onNext(strings: Array<out String?>) {
                        val streamTimer = Integer.parseInt(RadioPreferences(this@HomeActivity).streamingTimer!!) * 3600
                        val currentPosition = Seconds.seconds((mStreamPlayer.currentPosition / 1000).toInt())
                        seekBarProgress.max = streamTimer
                        seekBarProgress.progress = currentPosition.seconds

                        textStreamProgress?.text = strings[1]
                        textStreamDuration?.text = strings[0]
                    }
                })
    }

    private fun startPlayback() {
        val audioServiceIntent = Intent(this, AudioStreamingService::class.java)
        audioServiceIntent.action = Constants.ACTION_PLAY
        ContextCompat.startForegroundService(this, audioServiceIntent)
    }

    private fun pausePlayback() {
        val audioServiceIntent = Intent(this, AudioStreamingService::class.java)
        audioServiceIntent.action = Constants.ACTION_PAUSE
        ContextCompat.startForegroundService(this, audioServiceIntent)
    }


    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop: ")
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mAudioServiceBroadcastReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        visualizer.release()

        mCompositeDisposable?.clear()
    }


    /**
     * Initialize all views by findViewById or @Bind when using ButterKnife.
     * Note: All view Initializing must be performed in this module or it's submodules
     */
    private fun initializeViews() {
        val textAnimationIn = AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left)

        val textAnimationOut = AnimationUtils.loadAnimation(this, android.R.anim.slide_out_right)

        textSwitcherPlayerState.inAnimation = textAnimationIn
        textSwitcherPlayerState.outAnimation = textAnimationOut
        textSwitcherPlayerState.setCurrentText("Hello")

        initializeTabComponents()
        initializeToolbar()
        initializeBottomSheet()



        seekBarProgress.isEnabled = false
    }

    private fun setUpAudioVisualizer() {
        val audioSessionId = StreamPlayer.getInstance(applicationContext).audioSessionId
        try {
            if (audioSessionId != -1)
                visualizer?.setAudioSessionId(audioSessionId)
        } catch (exception: Exception) {
            Log.e(TAG, "setUpAudioVisualizer: " + exception.message)
        }

    }

    private fun requestAudioRecordingPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_RECORD_AUDIO)
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                setUpAudioVisualizer()
            else
                Log.i(TAG, "onRequestPermissionsResult: Denied. Unable to initialize visualizer")
        }
    }

    /**
     * bottom sheet state change listener
     * We are transitioning between collapsed and settled states, well that is what we are interested in, isn't it?
     */
    private fun initializeBottomSheet() {
        mSheetBehaviour = BottomSheetBehavior.from(layoutBottomSheet!!)

        mSheetBehaviour!!.setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                val appBarLayout = findViewById<AppBarLayout>(R.id.appbar)

                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    appBarLayout.setExpanded(false, true)
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    appBarLayout.setExpanded(true, true)
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                performAlphaTransition(slideOffset)
                rotateSmallLogo(slideOffset)
            }
        })
    }

    /**
     * rotate the collapse button clockwise when collapsing and counter-clockwise when expanding
     *
     * @param slideOffset the initial angle where rotation begins
     */
    private fun rotateSmallLogo(slideOffset: Float) {
        val rotationAngle = slideOffset * -360
        smallLogo!!.rotation = rotationAngle
    }

    /**
     * Alpha 0 is transparent whilst 1 is visible so let's reverse the offset value obtained
     * with some basic math for the peek items while maintaining the original value for the sheet menu items
     * so that they crossfade
     */
    private fun performAlphaTransition(slideOffset: Float) {
        val alpha = 1 - slideOffset
        bottomSheetPlaybackItems!!.alpha = alpha
    }

    /**
     * Explicitly collapse the bottom sheet
     */
    fun collapseBottomSheet() {
        if (mSheetBehaviour?.state != BottomSheetBehavior.STATE_COLLAPSED)
            mSheetBehaviour?.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    /**
     * Called when a broadcast is received from the AudioStreamingService so that the
     * UI can be resolved accordingly to correspond with the states
     *
     * @param intent The intent received fromt he audio service (STATUS_PAUSED, STATUS_PLAYING ETC)
     */
    private fun onAudioStreamingStateReceived(intent: Intent) {
        val receivedState = intent.getStringExtra(Constants.STREAMING_STATUS)
        mAudioStreamingState = AudioStreamingState.valueOf(receivedState!!)

        when (mAudioStreamingState) {
            AudioStreamingState.STATUS_PLAYING -> {
                Log.d(TAG, "onAudioStreamingStateReceived: Playing")
                Tools.toggleViewsVisibility(View.INVISIBLE, smallProgressBar)
                animateButtonDrawable(btnPlay, ContextCompat.getDrawable(this, R.drawable.avd_play_pause)!!)
                animateButtonDrawable(btnSmallPlay, ContextCompat.getDrawable(this, R.drawable.avd_play_pause_small)!!)

                intiializeAudioVisualizer()

                //start updating seekbar when something is actually playing
                startUpdateStreamProgress()
                textSwitcherPlayerState?.setText(getString(R.string.state_live))
                (textSwitcherPlayerState.currentView as TextView).setTextColor(ContextCompat.getColor(this, R.color.green_200))
            }
            AudioStreamingState.STATUS_STOPPED -> {
                Log.d(TAG, "onAudioStreamingStateReceived: STOPPED")
                animateButtonDrawable(btnPlay, ContextCompat.getDrawable(applicationContext, R.drawable.avd_pause_play)!!)
                animateButtonDrawable(btnSmallPlay, ContextCompat.getDrawable(this, R.drawable.avd_pause_play_small)!!)

                Tools.toggleViewsVisibility(View.INVISIBLE, smallProgressBar)
                textSwitcherPlayerState.setText(getString(R.string.state_stopped))
                (textSwitcherPlayerState.currentView as TextView).setTextColor(ContextCompat.getColor(this, R.color.pink_600))
            }
            AudioStreamingState.STATUS_LOADING -> {
                textSwitcherPlayerState.setText(getString(R.string.state_buffering))
                (textSwitcherPlayerState.currentView as TextView).setTextColor(ContextCompat.getColor(this, R.color.pink_200))
                Tools.toggleViewsVisibility(View.VISIBLE, smallProgressBar)
            }

            AudioStreamingState.STATUS_PAUSED -> {
                animateButtonDrawable(btnPlay, ContextCompat.getDrawable(this, R.drawable.avd_pause_play)!!)
                animateButtonDrawable(btnSmallPlay, ContextCompat.getDrawable(this, R.drawable.avd_pause_play_small)!!)

                textSwitcherPlayerState.setText(getString(R.string.state_paused))
                (textSwitcherPlayerState.currentView as TextView).setTextColor(ContextCompat.getColor(this, R.color.yellow_400))
                Tools.toggleViewsVisibility(View.INVISIBLE, smallProgressBar)
            }

        }
    }


    private fun setupViewPager(viewPager: ViewPager) {
        val viewPagerAdapter = HomeSectionsPagerAdapter(supportFragmentManager)
        viewPagerAdapter.addFragment(HomeFragment(), "Live")    // index 0
        viewPagerAdapter.addFragment(NewsFragment(), "News")   // index 1
        viewPagerAdapter.addFragment(AboutFragment(), "About")   // index 2

        viewPager.adapter = viewPagerAdapter
    }

    override fun onResume() {
        Log.i(TAG, "onResume: ")
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(mAudioServiceBroadcastReceiver,
                IntentFilter(STREAM_RESULT)
        )

    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
        }

        return super.onOptionsItemSelected(item)
    }
}
