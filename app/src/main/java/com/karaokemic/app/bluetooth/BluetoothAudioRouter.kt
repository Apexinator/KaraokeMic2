package com.karaokemic.app.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class BluetoothAudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var focusRequest: AudioFocusRequest? = null

    data class BluetoothDeviceInfo(val name: String, val address: String)

    /**
     * Returns connected A2DP device info, or null if none connected.
     */
    fun getConnectedA2dpDevice(): BluetoothDeviceInfo? {
        return try {
            val state = bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.A2DP)
            if (state == BluetoothProfile.STATE_CONNECTED) {
                // Try to get device name from paired devices
                val paired = bluetoothAdapter?.bondedDevices
                val device = paired?.firstOrNull()
                BluetoothDeviceInfo(
                    name = device?.name ?: "Bluetooth Speaker",
                    address = device?.address ?: ""
                )
            } else null
        } catch (e: SecurityException) {
            Log.e("BT", "Permission denied: ${e.message}")
            null
        }
    }

    fun isBluetoothA2dpConnected(): Boolean {
        return try {
            bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.A2DP) ==
                    BluetoothProfile.STATE_CONNECTED
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Configures AudioManager to route USAGE_MEDIA to Bluetooth A2DP.
     * In MODE_NORMAL, Android automatically sends media audio to A2DP.
     */
    fun setupForKaraoke() {
        // MODE_NORMAL ensures media streams go to A2DP (not phone earpiece)
        audioManager.mode = AudioManager.MODE_NORMAL

        // Make sure phone speaker is off
        audioManager.isSpeakerphoneOn = false

        // Stop any SCO connection (we want A2DP, not SCO)
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false

        // Request permanent audio focus for media
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d("BT", "Audio focus changed: $focusChange")
                }
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        }
    }

    fun release() {
        focusRequest?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(it)
            }
        }
    }
}
