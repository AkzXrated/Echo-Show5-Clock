package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

class ChimeSynthesizer(private val context: Context) {

    private val prefs = context.getSharedPreferences("ambient_prefs", Context.MODE_PRIVATE)

    // A "voice" bundles the timbre parameters + the per-note waveform recipe.
    private class Voice(
        val id: String,
        val label: String,
        val totalSec: Double,   // buffer length (long enough for the tail to ring out)
        val stagger: Double,    // seconds between note onsets
        val attack: Double,     // soft attack length (no click)
        val decay: Double,      // fundamental decay rate (smaller = longer ring)
        val brightK: Double,    // how fast high partials fade (bigger = mellower)
        val revD1: Double,      // reverb delay 1 (s)
        val revD2: Double,      // reverb delay 2 (s)
        val revFb: Float,       // reverb feedback
        val revLpf: Float,      // reverb lowpass (smaller = darker echoes)
        // (f0, nt, w, bright) -> one sample. w = 2*PI*f0*nt ; bright = fast-decaying env
        val wave: (Double, Double, Double, Double) -> Double
    )

    companion object {
        private const val TAG = "ChimeSynthesizer"
        private const val SAMPLE_RATE = 44100

        /** Default voice id. */
        const val DEFAULT_VOICE = "warm_bell"

        // C-major pentatonic across two octaves. Every combination is consonant.
        // Index:   0      1      2      3      4      5      6      7
        //          G4     A4     C5     D5     E5     G5     A5     C6
        private val PENT = doubleArrayOf(
            392.00, 440.00, 523.25, 587.33, 659.25, 783.99, 880.00, 1046.50
        )

        // Hand-written 4-note motifs (indices into PENT). Each is an intentional phrase.
        private val MOTIFS = arrayOf(
            intArrayOf(5, 4, 3, 2), // G5 E5 D5 C5 - gentle descent, settles on tonic
            intArrayOf(2, 4, 5, 7), // C5 E5 G5 C6 - bright rising arpeggio
            intArrayOf(6, 5, 4, 2), // A5 G5 E5 C5 - soft falling, soothing
            intArrayOf(2, 4, 5, 4), // C5 E5 G5 E5 - arch up and back
            intArrayOf(4, 5, 3, 2), // E5 G5 D5 C5 - small lilt, then settle
            intArrayOf(2, 3, 4, 5), // C5 D5 E5 G5 - hopeful climb
            intArrayOf(5, 6, 4, 2), // G5 A5 E5 C5 - Westminster-ish, resolves down
            intArrayOf(4, 2, 3, 0)  // E5 C5 D5 G4 - warm, low settle
        )

        // ---- Warm / soft voice family ----------------------------------------
        // (linkedMap keeps display order stable)
        private val VOICES: Map<String, Voice> = linkedMapOf(

            // The winner: clear, warm, gently shimmering bell.
            "warm_bell" to Voice(
                "warm_bell", "Warm Bell",
                4.5, 0.40, 0.020, 1.8, 4.5, 0.113, 0.173, 0.30f, 0.30f
            ) { f0, nt, w, bright ->
                sin(w) +
                0.06 * sin(2.0 * PI * f0 * 1.004 * nt) +   // faint detuned twin = warmth
                0.10 * bright * sin(2.0 * w) +             // octave shimmer, fades fast
                0.03 * bright * bright * sin(3.0 * w)      // whisper of air, fades faster
            },

            // Mellower sibling of Warm Bell: softer attack, longer ring, less sparkle.
            "soft_chime" to Voice(
                "soft_chime", "Soft Chime",
                5.0, 0.42, 0.030, 1.5, 5.5, 0.131, 0.197, 0.30f, 0.38f
            ) { f0, nt, w, bright ->
                sin(w) +
                0.05 * sin(2.0 * PI * f0 * 1.003 * nt) +   // gentle detune
                0.06 * bright * sin(2.0 * w)               // barely-there octave
            },

            // Warm Bell with a sustained sub-octave underneath = rounder, cozier body.
            "velvet_bell" to Voice(
                "velvet_bell", "Velvet Bell",
                4.8, 0.40, 0.022, 1.7, 4.5, 0.113, 0.173, 0.32f, 0.32f
            ) { f0, nt, w, bright ->
                sin(w) +
                0.15 * sin(0.5 * w) +                      // sub-octave, sustains -> depth
                0.06 * sin(2.0 * PI * f0 * 1.004 * nt) +
                0.09 * bright * sin(2.0 * w)
            },

            // Ethereal, near-pure sines with a long airy reverb tail.
            "glass" to Voice(
                "glass", "Glass (Airy)",
                5.5, 0.45, 0.040, 1.2, 3.0, 0.149, 0.227, 0.36f, 0.45f
            ) { _, _, w, bright ->
                sin(w) +
                0.04 * bright * sin(2.0 * w)
            },

            // Softest of all: slow breath-like attack, lush detuned chorus, pad-like.
            "halo" to Voice(
                "halo", "Halo (Soft Pad)",
                6.0, 0.48, 0.060, 1.0, 3.0, 0.157, 0.241, 0.38f, 0.46f
            ) { f0, nt, w, bright ->
                sin(w) +
                0.08 * sin(2.0 * PI * f0 * 1.006 * nt) +   // chorus voice up
                0.05 * sin(2.0 * PI * f0 * 0.997 * nt) +   // chorus voice down
                0.04 * bright * sin(2.0 * w)
            }
        )

        /**
         * Voice ids + display labels, in order. Use this to populate a settings
         * dropdown so it stays in sync if voices are added later.
         */
        fun voiceOptions(): List<Pair<String, String>> = VOICES.map { it.key to it.value.label }
    }

    suspend fun playHourlyChime(hour12: Int, dayOfYear: Int, forcePlay: Boolean = false) =
        withContext(Dispatchers.IO) {
            val enabled = prefs.getBoolean("chime_enabled", true)
            if (!enabled && !forcePlay) {
                Log.d(TAG, "Chime skipped: disabled in settings.")
                return@withContext
            }

            val volumeFraction = prefs.getInt("chime_volume", 80) / 100f

            val voiceId = prefs.getString("chime_voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
            val voice = VOICES[voiceId] ?: VOICES.getValue(DEFAULT_VOICE)

            // Pick a melody by the hour, so each hour has its own recognisable tune.
            val motif = MOTIFS[(hour12 - 1).mod(MOTIFS.size)]
            val notes = DoubleArray(4) { PENT[motif[it]] }
            Log.d(TAG, "Chime hour=$hour12 voice=${voice.id} motif=${motif.joinToString()}")

            val buffer = synthesize(notes, voice)
            playBuffer(buffer, volumeFraction)
        }

    private fun synthesize(notes: DoubleArray, voice: Voice): FloatArray {
        val total = (voice.totalSec * SAMPLE_RATE).toInt()
        val buf = FloatArray(total)

        for (v in 0 until 4) {
            val f0 = notes[v]
            val onset = (v * voice.stagger * SAMPLE_RATE).toInt()

            for (n in onset until total) {
                val nt = (n - onset).toDouble() / SAMPLE_RATE

                val amp = if (nt < voice.attack) {
                    sin((PI / 2.0) * (nt / voice.attack))          // smooth attack, no click
                } else {
                    exp(-voice.decay * (nt - voice.attack))        // long gentle decay
                }

                val bright = exp(-voice.brightK * nt)              // high partials fade fast
                val w = 2.0 * PI * f0 * nt

                buf[n] += (voice.wave(f0, nt, w, bright) * amp * 0.25).toFloat()
            }
        }

        applyReverb(buf, voice)
        normalize(buf, 0.9f)
        return buf
    }

    /** Two lowpass-damped feedback delays: warm ambience with no metallic comb ringing. */
    private fun applyReverb(buf: FloatArray, voice: Voice) {
        val d1 = (voice.revD1 * SAMPLE_RATE).toInt()
        val d2 = (voice.revD2 * SAMPLE_RATE).toInt()
        val fb = voice.revFb
        val lpfCoeff = voice.revLpf
        var lp = 0f

        for (i in buf.indices) {
            var wet = 0f
            if (i >= d1) wet += buf[i - d1] * 0.6f
            if (i >= d2) wet += buf[i - d2] * 0.5f
            lp += lpfCoeff * (wet - lp)
            buf[i] += fb * lp
        }
    }

    /** Scale so the peak sits at [target] - prevents clipping without harsh hard-limiting. */
    private fun normalize(buf: FloatArray, target: Float) {
        var peak = 0f
        for (s in buf) peak = max(peak, abs(s))
        if (peak <= 0f) return
        val g = target / peak
        for (i in buf.indices) buf[i] *= g
    }

    private suspend fun playBuffer(buf: FloatArray, volumeFraction: Float) {
        val pcm = ShortArray(buf.size)
        for (i in buf.indices) {
            pcm[i] = (buf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }

        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val trackBuf = max(minBuf, 32768)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(trackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.setVolume(volumeFraction)
            track.play()

            var written = 0
            while (written < pcm.size) {
                val toWrite = minOf(4096, pcm.size - written)
                val r = track.write(pcm, written, toWrite)
                if (r <= 0) break
                written += r
            }

            val tailMs = (trackBuf / 2.0 / SAMPLE_RATE * 1000).toLong() + 250L
            delay(tailMs)

            try { track.stop() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack error", e)
        } finally {
            track?.release()
        }
    }
}
