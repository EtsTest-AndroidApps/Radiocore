package com.radiocore.app.fragment

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.foreverrafs.radiocore.R
import com.foreverrafs.radiocore.databinding.BottomSheetBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.radiocore.app.activity.MainActivity
import com.radiocore.app.adapter.HomePagerAdapter
import com.radiocore.app.viewmodels.AppViewModel
import com.radiocore.core.util.RadioPreferences
import com.radiocore.core.util.animateButtonDrawable
import com.radiocore.core.util.isServiceRunning
import com.radiocore.core.util.toggleViewsVisibility
import com.radiocore.news.ui.NewsListFragment
import com.radiocore.player.AudioServiceConnection
import com.radiocore.player.AudioStreamingService
import com.radiocore.player.AudioStreamingService.AudioStreamingState.*
import com.radiocore.player.StreamPlayer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.bottom_sheet.*
import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.bogerchan.niervisualizer.NierVisualizerManager
import me.bogerchan.niervisualizer.renderer.columnar.ColumnarType2Renderer
import org.joda.time.Seconds
import timber.log.Timber
import javax.inject.Inject


@AndroidEntryPoint
class MainFragment : Fragment(R.layout.fragment_main), View.OnClickListener {

    companion object {
        private const val PERMISSION_RECORD_AUDIO = 6900
    }

    private val mAudioServiceIntent: Intent by lazy {
        Intent(context, AudioStreamingService::class.java)
    }

    private lateinit var mSheetBehaviour: BottomSheetBehavior<*>

    private var shouldStartPlayback: Boolean = false

    @Inject
    lateinit var mStreamPlayer: StreamPlayer

    @Inject
    lateinit var mRadioPreferences: RadioPreferences

    private var audioService: AudioStreamingService? = null

    private val viewModel: AppViewModel by activityViewModels()

    private var visualizerManager: NierVisualizerManager? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        btnSmallPlay.setOnClickListener(this)
        btnPlay.setOnClickListener(this)

        visualizer.setZOrderOnTop(true)

        initializeViews()
    }


    private fun prepareVisualizer() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioRecordingPermission()
            return
        }

        visualizerManager?.let {
            it.resume()
            return
        }

        visualizerManager = NierVisualizerManager()

        val state = visualizerManager?.init(mStreamPlayer.audioSessionId)


        visualizer.setZOrderOnTop(true)
        visualizer.holder.setFormat(PixelFormat.TRANSLUCENT)

        if (state == NierVisualizerManager.SUCCESS) {
            visualizerManager?.start(
                    visualizer, arrayOf(ColumnarType2Renderer())
            )
        }
    }


    private fun initializeToolbar() {
        (activity as AppCompatActivity).setSupportActionBar(toolbar)
    }

    private fun initializeTabComponents() {
        setupViewPager(viewPager)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                0 -> tab.text = resources.getString(R.string.live)
                1 -> tab.text = resources.getString(R.string.news)
                2 -> tab.text = resources.getString(R.string.about)
            }
        }.attach()

        with(tabLayout) {
            getTabAt(0)?.setIcon(R.drawable.ic_radio_live)
            getTabAt(1)?.setIcon(R.drawable.ic_news)
            getTabAt(2)?.setIcon(R.drawable.ic_about)

            getTabAt(0)?.icon?.setTint(Color.RED)
            getTabAt(1)?.icon?.setTint(ContextCompat.getColor(context, R.color.grey_20))
            getTabAt(2)?.icon?.setTint(ContextCompat.getColor(context, R.color.grey_20))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                collapseBottomSheet()
                if (tab.position != 0)
                    tab.icon?.setTint(Color.WHITE)
                else
                    appBarLayout.setExpanded(true, true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                if (tab.position != 0)
                    tab.icon?.setTint(ContextCompat.getColor(requireContext(), R.color.grey_20))
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                Timber.i("onTabReselected: No-Op")
            }
        })
    }

    /**
     * Check if Audio Streaming Service is running and change the AudioStreamingState accordingly
     * Note: We Initially set it to STATUS_STOPPED, assuming that nothing is playing when we first run
     */
    @FlowPreview
    private fun setUpInitialPlayerState() {
        shouldStartPlayback = mRadioPreferences.autoPlayOnStart

        val mainActivityPendingIntent = PendingIntent.getActivity(requireContext(), 3333,
                Intent(requireContext(), MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)

        viewModel.audioServiceConnection = AudioServiceConnection(mainActivityPendingIntent) {
            audioService = viewModel.audioServiceConnection.audioService

            audioService?.apply {
                metaData.observe(viewLifecycleOwner, Observer { string ->
                    viewModel.updateStreamMetaData(string)
                })

                if (shouldStartPlayback)
                    startPlayback()

                playBackState.observe(viewLifecycleOwner, Observer { streamingState ->

                    viewModel.updatePlaybackState(streamingState)
                    when (streamingState) {
                        STATUS_PLAYING -> streamPlaying()
                        STATUS_STOPPED -> streamStopped()
                        STATUS_LOADING -> streamLoading()
                        else -> {
                            Timber.e("setUpInitialPlayerState: Unknown Playback state")
                        }
                    }
                })
            }

        }

        if (!isServiceRunning(AudioStreamingService::class.java, requireContext())) {
            if (mRadioPreferences.autoPlayOnStart)
                startPlayback()
        }
    }

    /**
     * Update the stream progress seekbar and timer accordingly.
     * Also checks if the stream timer is up which triggers a shutdown of the app
     */
    @FlowPreview
    private fun startUpdateStreamProgress() {
        lifecycleScope.launch {
            mStreamPlayer.streamDurationStringsFlow.collect { durationStrings ->
                val streamTimer = Integer.parseInt(RadioPreferences(requireContext()).streamingTimer!!) * 3600
                val currentPosition = Seconds.seconds((mStreamPlayer.currentPosition / 1000).toInt())
                seekBarProgress?.max = streamTimer
                seekBarProgress?.progress = currentPosition.seconds

                textStreamProgress?.text = durationStrings[1]
                textStreamDuration?.text = durationStrings[0]
            }
        }
    }

    private fun startPlayback() {
        if (viewModel.audioServiceConnection.isBound) {
            audioService?.startPlayback()

            return
        }

        startAudioService()
    }

    private fun startAudioService() {
        shouldStartPlayback = true
        ContextCompat.startForegroundService(requireContext(), mAudioServiceIntent)
        activity?.bindService(mAudioServiceIntent, viewModel.audioServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopPlayback() {
        viewModel.audioServiceConnection.audioService?.stopPlayback()
    }


    @FlowPreview
    override fun onStart() {
        super.onStart()
        setUpInitialPlayerState()
    }

    override fun onDestroy() {
        if (mStreamPlayer.playBackState != StreamPlayer.PlaybackState.PLAYING) {
            stopPlayback()
        }

        visualizerManager?.release()

        super.onDestroy()
    }


    /**
     * Initialize all views by findViewById or @Bind when using ButterKnife.
     * Note: All view Initializing must be performed in context module or it's submodules
     */
    private fun initializeViews() {
        val textAnimationIn = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_in_left)
        val textAnimationOut = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_out_right)

        with(textSwitcherPlayerState) {
            inAnimation = textAnimationIn
            outAnimation = textAnimationOut
            setCurrentText("RadioCore")
        }

        initializeTabComponents()
        initializeToolbar()
        initializeBottomSheet()

        seekBarProgress.isEnabled = false
    }

    private fun requestAudioRecordingPermission() {
        ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_RECORD_AUDIO)
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                prepareVisualizer()
            } else
                Timber.i("onRequestPermissionsResult: Denied. Unable to initialize visualizer")
        }
    }

    /**
     * bottom sheet state change listener
     * We are transitioning between collapsed and settled states, well that is what we are interested in, isn't it?
     */
    private fun initializeBottomSheet() {
        mSheetBehaviour = BottomSheetBehavior.from(layoutBottomSheet)

        //initialize the contact texts on the bottom sheet
        tvEmail.text = getString(R.string.email_and_value, getString(R.string.org_email))
        tvPhone.text = getString(R.string.phone_and_value, getString(R.string.org_phone))
        tvWebsite.text = getString(R.string.website_and_value, getString(R.string.org_website))

        BottomSheetBinding.inflate(layoutInflater).apply {
            lifecycleOwner = viewLifecycleOwner
        }.viewModel = this.viewModel

        mSheetBehaviour.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    appBarLayout.setExpanded(false, true)
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    appBarLayout.setExpanded(true, true)
                    visualizer.visibility = View.VISIBLE
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                performAlphaTransition(slideOffset)
                rotateSmallLogo(slideOffset)
                visualizer.visibility = View.INVISIBLE
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
        smallLogo?.rotation = rotationAngle
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
        if (mSheetBehaviour.state != BottomSheetBehavior.STATE_COLLAPSED)
            mSheetBehaviour.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun streamLoading() {
        visualizerManager?.pause()
        Timber.i("Stream State Changed: BUFFERING")
        textSwitcherPlayerState.setText(getString(R.string.state_buffering))
        (textSwitcherPlayerState.currentView as TextView).setTextColor(ContextCompat.getColor(requireContext(), R.color.pink_200))
        toggleViewsVisibility(View.VISIBLE, progressBuffering)
    }

    private fun streamStopped() {
        visualizerManager?.pause()
        Timber.d("Stream State Changed: STOPPED")
        animateButtonDrawable(btnPlay, ContextCompat.getDrawable(requireContext(), R.drawable.avd_pause_play)!!)
        animateButtonDrawable(btnSmallPlay, ContextCompat.getDrawable(requireContext(), R.drawable.avd_pause_play_small)!!)

        toggleViewsVisibility(View.INVISIBLE, progressBuffering)
        textSwitcherPlayerState.setText(getString(R.string.state_stopped))
        (textSwitcherPlayerState.currentView as TextView).setTextColor(ContextCompat.getColor(requireContext(), R.color.pink_600))
    }


    @FlowPreview
    private fun streamPlaying() {
        Timber.d("Stream State Changed: Playing")

        prepareVisualizer()
        toggleViewsVisibility(View.INVISIBLE, progressBuffering)
        animateButtonDrawable(btnPlay, ContextCompat.getDrawable(requireContext(), R.drawable.avd_play_pause)!!)
        animateButtonDrawable(btnSmallPlay, ContextCompat.getDrawable(requireContext(), R.drawable.avd_play_pause_small)!!)


        //start updating seekbar when something is actually playing
        startUpdateStreamProgress()
        textSwitcherPlayerState?.setText(getString(R.string.state_live))
        (textSwitcherPlayerState.currentView as TextView).setTextColor(ContextCompat.getColor(requireContext(), R.color.green_200))
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.btnSmallPlay, R.id.btnPlay -> {
                Timber.i("onClick: ${viewModel.playbackState.value.toString()}")

                when (viewModel.playbackState.value) {
                    STATUS_PLAYING -> stopPlayback()
                    STATUS_STOPPED -> startPlayback()
                    STATUS_LOADING -> Timber.d("Stream already loading; Ignore click event")
                }
            }
        }
    }


    private fun setupViewPager(viewPager: ViewPager2) {
        val viewPagerAdapter = HomePagerAdapter(requireActivity()).apply {
            addFragment(HomeFragment(), "Live")
            addFragment(NewsListFragment(), "News")
            addFragment(AboutFragment(), "About")
        }

        viewPager.adapter = viewPagerAdapter
    }

    override fun onResume() {
        super.onResume()

        visualizerManager?.resume()

        if (mStreamPlayer.playBackState == StreamPlayer.PlaybackState.PLAYING &&
                !viewModel.audioServiceConnection.isBound) {
            activity?.bindService(mAudioServiceIntent, viewModel.audioServiceConnection, Context.BIND_AUTO_CREATE)
        }
    }

}
