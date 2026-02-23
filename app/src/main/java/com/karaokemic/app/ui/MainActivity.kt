package com.karaokemic.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.karaokemic.app.R
import com.karaokemic.app.databinding.ActivityMainBinding
import com.karaokemic.app.service.KaraokeService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var karaokeService: KaraokeService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            karaokeService = (binder as KaraokeService.LocalBinder).getService()
            isBound = true
            updateUI(karaokeService?.isRunning() == true)
            syncSlidersFromService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            karaokeService = null
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(KaraokeService.EXTRA_STATUS)
            when (status) {
                "running" -> updateUI(true)
                "stopped" -> updateUI(false)
                "error" -> {
                    updateUI(false)
                    val msg = intent.getStringExtra(KaraokeService.EXTRA_ERROR) ?: "Unknown error"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
                "vu" -> {
                    val db = intent.getFloatExtra(KaraokeService.EXTRA_DB_LEVEL, -60f)
                    updateVuMeter(db)
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startKaraoke()
        } else {
            Toast.makeText(this, "Microphone permission is required for karaoke.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        bindService()

        registerReceiver(
            statusReceiver,
            IntentFilter(KaraokeService.BROADCAST_STATUS)
        )
    }

    private fun setupUI() {
        // Start/Stop button
        binding.btnStartStop.setOnClickListener {
            if (karaokeService?.isRunning() == true) {
                stopKaraoke()
            } else {
                checkPermissionsAndStart()
            }
        }

        // Gain slider
        binding.seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val gain = progress / 100f * 1.5f // 0..1.5
                karaokeService?.audioProcessor?.gainLevel = gain
                binding.tvGainValue.text = "${progress}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Reverb slider
        binding.seekReverb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                karaokeService?.audioProcessor?.reverbLevel = progress / 100f
                binding.tvReverbValue.text = "${progress}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Echo slider
        binding.seekEcho.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                karaokeService?.audioProcessor?.echoLevel = progress / 100f
                binding.tvEchoValue.text = "${progress}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Pitch slider (-6 to +6 semitones, mapped to 0..12)
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val semitones = progress - 6
                karaokeService?.audioProcessor?.pitchSemitones = semitones
                binding.tvPitchValue.text = when {
                    semitones > 0 -> "+$semitones"
                    else -> "$semitones"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Set defaults
        binding.seekGain.progress = 80
        binding.seekReverb.progress = 40
        binding.seekEcho.progress = 30
        binding.seekPitch.progress = 6 // center = 0
    }

    private fun syncSlidersFromService() {
        karaokeService?.audioProcessor?.let { proc ->
            binding.seekGain.progress = (proc.gainLevel / 1.5f * 100).toInt()
            binding.seekReverb.progress = (proc.reverbLevel * 100).toInt()
            binding.seekEcho.progress = (proc.echoLevel * 100).toInt()
            binding.seekPitch.progress = proc.pitchSemitones + 6
        }
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (needed.isEmpty()) {
            startKaraoke()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startKaraoke() {
        val serviceIntent = Intent(this, KaraokeService::class.java).apply {
            action = KaraokeService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        // Bind if not already bound
        if (!isBound) bindService()
    }

    private fun stopKaraoke() {
        karaokeService?.stopKaraoke()
        // Or via intent if service not bound
        startService(Intent(this, KaraokeService::class.java).apply {
            action = KaraokeService.ACTION_STOP
        })
    }

    private fun bindService() {
        val intent = Intent(this, KaraokeService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUI(running: Boolean) {
        if (running) {
            binding.btnStartStop.text = "⏹ Stop"
            binding.btnStartStop.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            binding.tvStatus.text = "🎤 Singing..."
            binding.statusDot.setBackgroundResource(R.drawable.dot_green)
            updateBluetoothStatus()
        } else {
            binding.btnStartStop.text = "▶ Start Singing"
            binding.btnStartStop.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            binding.tvStatus.text = "Ready"
            binding.statusDot.setBackgroundResource(R.drawable.dot_grey)
            binding.vuMeterBar.progress = 0
        }
    }

    private fun updateBluetoothStatus() {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter
            val state = adapter?.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP)
            if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                binding.tvBluetooth.text = "🔵 BT Speaker Connected"
            } else {
                binding.tvBluetooth.text = "⚪ No BT Speaker"
            }
        } catch (e: SecurityException) {
            binding.tvBluetooth.text = "🔵 Bluetooth"
        }
    }

    private fun updateVuMeter(db: Float) {
        // Map -60dB..0dB to 0..100
        val progress = ((db + 60f) / 60f * 100f).toInt().coerceIn(0, 100)
        binding.vuMeterBar.progress = progress
    }

    override fun onResume() {
        super.onResume()
        updateBluetoothStatus()
        updateUI(karaokeService?.isRunning() == true)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
