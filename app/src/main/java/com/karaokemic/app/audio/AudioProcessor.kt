package com.karaokemic.app.audio

import kotlin.math.roundToInt

/**
 * Real-time DSP processor for karaoke effects.
 * Applies gain, echo, and reverb to a PCM buffer.
 */
class AudioProcessor {

    // --- Effect Parameters (0f..1f unless noted) ---
    var gainLevel: Float = 0.8f       // Master volume (0 = silent, 1 = full, >1 = boost)
    var echoLevel: Float = 0.3f       // Echo wet mix (0 = off)
    var reverbLevel: Float = 0.4f     // Reverb wet mix (0 = off)
    var pitchSemitones: Int = 0       // Pitch shift in semitones (-6 to +6)

    private val sampleRate = 44100

    // Echo: circular delay buffer (~300ms)
    private val echoDelayMs = 300
    private val echoBufferSize = (sampleRate * echoDelayMs / 1000)
    private val echoBuffer = ShortArray(echoBufferSize)
    private var echoWritePos = 0

    // Reverb: multiple delay lines for simple Schroeder reverb
    private val reverbDelays = intArrayOf(1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116)
    private val reverbBuffers = Array(reverbDelays.size) { idx -> ShortArray(reverbDelays[idx]) }
    private val reverbPositions = IntArray(reverbDelays.size)
    private val reverbFeedback = 0.5f

    fun process(input: ShortArray, size: Int): ShortArray {
        val output = ShortArray(size)

        for (i in 0 until size) {
            var sample = input[i].toFloat()

            // 1. Apply gain
            sample *= gainLevel

            // 2. Apply reverb (Schroeder comb filters)
            if (reverbLevel > 0f) {
                var reverbSum = 0f
                for (r in reverbBuffers.indices) {
                    val pos = reverbPositions[r]
                    val delayed = reverbBuffers[r][pos].toFloat()
                    reverbBuffers[r][pos] = clampToShort(sample + delayed * reverbFeedback)
                    reverbPositions[r] = (pos + 1) % reverbDelays[r]
                    reverbSum += delayed
                }
                sample = sample * (1f - reverbLevel) + (reverbSum / reverbBuffers.size) * reverbLevel
            }

            // 3. Apply echo
            if (echoLevel > 0f) {
                val echoPast = echoBuffer[echoWritePos].toFloat()
                echoBuffer[echoWritePos] = clampToShort(sample + echoPast * 0.4f)
                echoWritePos = (echoWritePos + 1) % echoBufferSize
                sample = sample * (1f - echoLevel) + echoPast * echoLevel
            }

            output[i] = clampToShort(sample)
        }

        return output
    }

    fun reset() {
        echoBuffer.fill(0)
        for (buf in reverbBuffers) buf.fill(0)
        echoWritePos = 0
        reverbPositions.fill(0)
    }

    private fun clampToShort(value: Float): Short {
        return value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
