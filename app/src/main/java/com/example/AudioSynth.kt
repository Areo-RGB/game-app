package com.example

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

object AudioSynth {
    private const val SAMPLE_RATE = 22050

    suspend fun beep(frequency: Double, durationSec: Double, waveform: String = "sine", volume: Double = 0.3) {
        withContext(Dispatchers.Default) {
            val numSamples = (durationSec * SAMPLE_RATE).toInt()
            if (numSamples <= 0) return@withContext
            
            val samples = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val angle = 2.0 * Math.PI * frequency * t
                val value = when (waveform) {
                    "square" -> if (sin(angle) >= 0) 1.0 else -1.0
                    "sawtooth" -> {
                        val phase = (frequency * t) % 1.0
                        2.0 * phase - 1.0
                    }
                    else -> sin(angle) // "sine"
                }
                // Exponential decay envelope
                val decay = 1.0 - (i.toDouble() / numSamples)
                samples[i] = (value * volume * Short.MAX_VALUE * decay)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            try {
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(numSamples * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(samples, 0, numSamples)
                audioTrack.play()
                // Yield thread to let the sound play
                kotlinx.coroutines.delay((durationSec * 1000).toLong() + 30)
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
