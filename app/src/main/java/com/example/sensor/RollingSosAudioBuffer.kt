package com.example.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.example.domain.SosAudioClipMetadata
import com.example.domain.SosAudioStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bounded, thread-safe circular PCM ring buffer that retains at most [maxDurationSeconds]
 * (default 15 seconds) of 16-bit mono PCM audio in memory.
 *
 * - Continuously overwrites the oldest samples once full.
 * - Never writes rolling audio to disk.
 * - Zeroes out memory immediately on [clear].
 */
class RollingPcmRingBuffer(
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val maxDurationSeconds: Int = DEFAULT_ROLLING_WINDOW_SECONDS
) {
    val capacitySamples: Int = (sampleRateHz * maxDurationSeconds).coerceAtLeast(sampleRateHz)
    val maxMemoryBytes: Int = capacitySamples * 2

    private val lock = Any()
    private val ring = ShortArray(capacitySamples)
    private var writeIndex: Int = 0
    private var validSampleCount: Int = 0
    private var totalSamplesWritten: Long = 0L

    /**
     * Writes up to [length] 16-bit PCM samples into the circular buffer, overwriting
     * the oldest samples when the 15-second window is full.
     */
    fun writeSamples(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        if (length <= 0 || offset < 0 || offset + length > samples.size) return
        synchronized(lock) {
            var remaining = length
            var srcPos = offset
            if (remaining >= capacitySamples) {
                srcPos += (remaining - capacitySamples)
                remaining = capacitySamples
            }
            val firstChunk = minOf(remaining, capacitySamples - writeIndex)
            System.arraycopy(samples, srcPos, ring, writeIndex, firstChunk)
            val secondChunk = remaining - firstChunk
            if (secondChunk > 0) {
                System.arraycopy(samples, srcPos + firstChunk, ring, 0, secondChunk)
            }
            writeIndex = (writeIndex + remaining) % capacitySamples
            validSampleCount = minOf(capacitySamples, validSampleCount + remaining)
            totalSamplesWritten += length.toLong()
        }
    }

    /**
     * Returns a chronologically ordered snapshot of the buffered audio samples
     * (from oldest retained sample to newest sample).
     */
    fun snapshotRecentSamples(): ShortArray {
        synchronized(lock) {
            if (validSampleCount <= 0) return ShortArray(0)
            val out = ShortArray(validSampleCount)
            if (validSampleCount < capacitySamples) {
                System.arraycopy(ring, 0, out, 0, validSampleCount)
            } else {
                val tailCount = capacitySamples - writeIndex
                System.arraycopy(ring, writeIndex, out, 0, tailCount)
                if (writeIndex > 0) {
                    System.arraycopy(ring, 0, out, tailCount, writeIndex)
                }
            }
            return out
        }
    }

    /**
     * Exact duration (in seconds) of valid audio currently held in the circular buffer.
     * Never reports 15.0s if fewer samples have been captured.
     */
    val bufferedDurationSeconds: Double
        get() = synchronized(lock) {
            validSampleCount.toDouble() / sampleRateHz.toDouble()
        }

    val currentSampleCount: Int
        get() = synchronized(lock) { validSampleCount }

    val totalWrittenCount: Long
        get() = synchronized(lock) { totalSamplesWritten }

    fun write(samples: ShortArray, length: Int = samples.size) {
        writeSamples(samples, 0, length)
    }

    fun bufferedSampleCount(): Int = currentSampleCount

    fun bufferedDurationSeconds(): Double = bufferedDurationSeconds

    fun snapshotSamples(): ShortArray = snapshotRecentSamples()

    /**
     * Zeroes out all buffered PCM samples and resets pointers.
     */
    fun clear() {
        synchronized(lock) {
            Arrays.fill(ring, 0.toShort())
            writeIndex = 0
            validSampleCount = 0
            totalSamplesWritten = 0L
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_ROLLING_WINDOW_SECONDS = 15
    }
}

sealed class SosAudioFinalizeOutcome {
    data class Captured(val metadata: SosAudioClipMetadata) : SosAudioFinalizeOutcome()
    data class Unavailable(val status: SosAudioStatus, val reason: String) : SosAudioFinalizeOutcome()
}

/**
 * Encodes a finalized SOS audio clip into an `.m4a` (AAC in MP4 container) file when
 * hardware `MediaCodec` + `MediaMuxer` are available on the Android device, or a standard
 * `.wav` (RIFF/WAVE 16-bit mono PCM) container as a universal fallback.
 */
object SosAudioContainerEncoder {

    const val MAX_EMAIL_ATTACHMENT_BYTES = 4 * 1024 * 1024 // 4 MB safe inline attachment threshold

    fun encodeWavBytes(samples: ShortArray, sampleRateHz: Int): ByteArray {
        return encodePcmToWavBytes(samples, sampleRateHz)
    }

    fun encodeClipToFile(
        outputDir: File,
        alertId: String,
        pcmSamples: ShortArray,
        sampleRateHz: Int,
        preTriggerSeconds: Double,
        postTriggerSeconds: Double,
        preferAacM4a: Boolean = true
    ): SosAudioClipMetadata? {
        if (pcmSamples.isEmpty() || sampleRateHz <= 0) return null
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val safeId = alertId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(80).ifBlank {
            "sos_${System.currentTimeMillis()}"
        }
        val totalDurationSeconds = pcmSamples.size.toDouble() / sampleRateHz.toDouble()
        val now = System.currentTimeMillis()

        if (preferAacM4a) {
            val m4aFile = File(outputDir, "${safeId}_sos_recording.m4a")
            val encodedM4a = tryEncodeAacM4a(pcmSamples, sampleRateHz, m4aFile)
            if (encodedM4a && m4aFile.exists() && m4aFile.length() > 128L) {
                val bytes = m4aFile.readBytes()
                val base64 = if (bytes.size <= MAX_EMAIL_ATTACHMENT_BYTES) {
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } else {
                    null
                }
                return SosAudioClipMetadata(
                    alertId = alertId,
                    capturedAtMillis = now,
                    durationSeconds = totalDurationSeconds,
                    preTriggerSeconds = preTriggerSeconds,
                    postTriggerSeconds = postTriggerSeconds,
                    sampleRateHz = sampleRateHz,
                    mimeType = "audio/mp4",
                    containerExtension = "m4a",
                    sizeBytes = m4aFile.length(),
                    localFilePath = m4aFile.absolutePath,
                    attachedToEmail = base64 != null,
                    base64Payload = base64,
                    isPartialBuffer = preTriggerSeconds < 14.5,
                    statusNote = String.format(
                        Locale.US,
                        "SOS audio captured (%.1fs M4A/AAC)",
                        totalDurationSeconds
                    )
                )
            } else {
                try {
                    m4aFile.delete()
                } catch (_: Exception) {
                }
            }
        }

        val wavFile = File(outputDir, "${safeId}_sos_recording.wav")
        val wavBytes = encodePcmToWavBytes(pcmSamples, sampleRateHz)
        FileOutputStream(wavFile).use { fos ->
            fos.write(wavBytes)
            fos.flush()
        }
        val base64 = if (wavBytes.size <= MAX_EMAIL_ATTACHMENT_BYTES) {
            Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        } else {
            null
        }
        return SosAudioClipMetadata(
            alertId = alertId,
            capturedAtMillis = now,
            durationSeconds = totalDurationSeconds,
            preTriggerSeconds = preTriggerSeconds,
            postTriggerSeconds = postTriggerSeconds,
            sampleRateHz = sampleRateHz,
            mimeType = "audio/wav",
            containerExtension = "wav",
            sizeBytes = wavFile.length(),
            localFilePath = wavFile.absolutePath,
            attachedToEmail = base64 != null,
            base64Payload = base64,
            isPartialBuffer = preTriggerSeconds < 14.5,
            statusNote = String.format(
                Locale.US,
                "SOS audio captured (%.1fs WAV/PCM)",
                totalDurationSeconds
            )
        )
    }

    /**
     * Builds a standard 44-byte RIFF/WAVE header + 16-bit little-endian mono PCM payload.
     */
    fun encodePcmToWavBytes(pcmSamples: ShortArray, sampleRateHz: Int): ByteArray {
        val dataBytesCount = pcmSamples.size * 2
        val totalDataLen = dataBytesCount + 36
        val byteRate = sampleRateHz * 2
        val out = ByteArrayOutputStream(dataBytesCount + 44)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(1) // NumChannels = 1 (Mono)
        header.putInt(sampleRateHz)
        header.putInt(byteRate)
        header.putShort(2) // BlockAlign = 2 bytes per sample
        header.putShort(16) // BitsPerSample = 16
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytesCount)
        out.write(header.array())

        val pcmByteBuffer = ByteBuffer.allocate(dataBytesCount).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmSamples) {
            pcmByteBuffer.putShort(sample)
        }
        out.write(pcmByteBuffer.array())
        return out.toByteArray()
    }

    private fun tryEncodeAacM4a(
        pcmSamples: ShortArray,
        sampleRateHz: Int,
        outputFile: File
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, sampleRateHz, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var sampleOffset = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val maxShorts = minOf(
                                pcmSamples.size - sampleOffset,
                                inputBuffer.remaining() / 2
                            )
                            if (maxShorts <= 0) {
                                val ptsUs = (sampleOffset.toLong() * 1_000_000L) / sampleRateHz.toLong()
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    ptsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                for (i in 0 until maxShorts) {
                                    val s = pcmSamples[sampleOffset + i].toInt()
                                    inputBuffer.put((s and 0xFF).toByte())
                                    inputBuffer.put(((s shr 8) and 0xFF).toByte())
                                }
                                val ptsUs = (sampleOffset.toLong() * 1_000_000L) / sampleRateHz.toLong()
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    maxShorts * 2,
                                    ptsUs,
                                    0
                                )
                                sampleOffset += maxShorts
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputBufferIndex >= 0 -> {
                        val encodedData = codec.getOutputBuffer(outputBufferIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    else -> {
                        if (inputDone) {
                            outputDone = true
                        }
                    }
                }
            }
            muxerStarted
        } catch (_: Throwable) {
            false
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (_: Throwable) {
                }
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * Manages the opt-in 15-second rolling SOS audio capture session using Android's [AudioRecord].
 *
 * Privacy & security guarantees:
 * - Never starts recording unless the user has explicitly enabled SOS Audio Recording,
 *   accepted the disclosure, granted [Manifest.permission.RECORD_AUDIO], and started monitoring.
 * - Retains only the most recent 15 seconds in [ringBuffer] in RAM; continuously overwrites older frames.
 * - Immediately clears [ringBuffer] and deletes any temporary snapshot when the user cancels a
 *   fall check-in ("I'm OK"), disables SOS audio, or stops monitoring.
 */
class RollingSosAudioRecorder(
    private val context: Context,
    val ringBuffer: RollingPcmRingBuffer = RollingPcmRingBuffer()
) {
    private val appContext = context.applicationContext ?: context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var captureJob: Job? = null
    private var activeAudioRecord: AudioRecord? = null

    @Volatile
    private var frozenCandidatePreTriggerSamples: ShortArray? = null

    private val _audioStatus = MutableStateFlow(SosAudioStatus.DISABLED)
    val audioStatus: StateFlow<SosAudioStatus> = _audioStatus.asStateFlow()

    private val _bufferedSeconds = MutableStateFlow(0.0)
    val bufferedSeconds: StateFlow<Double> = _bufferedSeconds.asStateFlow()
    val bufferedDurationSeconds: StateFlow<Double> = bufferedSeconds

    private val _lastCapturedClip = MutableStateFlow<SosAudioClipMetadata?>(null)
    val lastCapturedClip: StateFlow<SosAudioClipMetadata?> = _lastCapturedClip.asStateFlow()

    val isBufferCapturing: Boolean
        get() = captureJob?.isActive == true

    fun isBufferingActive(): Boolean = isBufferCapturing

    fun getBufferedDurationSeconds(): Double = ringBuffer.bufferedDurationSeconds

    fun hasCandidateSnapshot(): Boolean = frozenCandidatePreTriggerSamples != null

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasMicrophonePermission(): Boolean = hasRecordAudioPermission()

    fun startRollingBuffer(): SosAudioStatus {
        if (!hasRecordAudioPermission()) {
            _audioStatus.value = SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED
            return SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED
        }
        if (captureJob?.isActive == true) {
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_ENABLED
            return SosAudioStatus.AUDIO_MONITORING_ENABLED
        }
        startAudioRecordLoop()
        return _audioStatus.value
    }

    fun stopBufferingAndClear() {
        stopAndClearRollingBuffer()
    }

    fun snapshotPreTriggerCandidate() {
        snapshotCandidatePreTriggerAudio()
    }

    fun purgeExpiredLocalClips(retentionDays: Int): Int {
        return cleanupLocalRecordings(retentionDays = retentionDays, deleteAll = false)
    }

    fun deleteAllFinalizedAudioFiles(): Int {
        return cleanupLocalRecordings(deleteAll = true)
    }

    suspend fun finalizeSosAudioForAlert(
        alertId: String,
        postTriggerSeconds: Int = 0
    ): SosAudioFinalizeOutcome {
        if (!hasRecordAudioPermission() && ringBuffer.currentSampleCount == 0 && frozenCandidatePreTriggerSamples == null) {
            return SosAudioFinalizeOutcome.Unavailable(
                status = SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED,
                reason = "Microphone permission was not granted when SOS was triggered."
            )
        }
        val waitMillis = (postTriggerSeconds.coerceIn(0, 3) * 1000L)
        val clip = freezeAndFinalizeForSos(
            alertId = alertId,
            postTriggerWaitMillis = waitMillis
        )
        return if (clip != null) {
            SosAudioFinalizeOutcome.Captured(clip)
        } else {
            SosAudioFinalizeOutcome.Unavailable(
                status = SosAudioStatus.AUDIO_MONITORING_UNAVAILABLE,
                reason = "No audio frames were available in the 15-second rolling buffer when SOS was triggered."
            )
        }
    }

    /**
     * Synchronizes the rolling audio buffer state with user preferences and monitoring status.
     */
    fun syncCaptureState(
        isMonitoringActive: Boolean,
        sosAudioEnabled: Boolean,
        sosAudioConsentGranted: Boolean
    ) {
        if (!sosAudioEnabled || !sosAudioConsentGranted) {
            stopAndClearRollingBuffer()
            _audioStatus.value = SosAudioStatus.DISABLED
            return
        }

        if (!hasRecordAudioPermission()) {
            stopAndClearRollingBuffer()
            _audioStatus.value = SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED
            return
        }

        if (!isMonitoringActive) {
            stopAndClearRollingBuffer()
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_ENABLED
            return
        }

        if (captureJob?.isActive == true) {
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_ENABLED
            return
        }

        startAudioRecordLoop()
    }

    private fun startAudioRecordLoop() {
        captureJob?.cancel()
        val sampleRate = ringBuffer.sampleRateHz
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufBytes = try {
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        } catch (_: Throwable) {
            AudioRecord.ERROR_BAD_VALUE
        }

        if (minBufBytes <= 0) {
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_UNAVAILABLE
            return
        }

        val bufferSizeInBytes = maxOf(minBufBytes * 2, sampleRate * 2) // at least 1 second buffer
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSizeInBytes
            )
        } catch (_: SecurityException) {
            _audioStatus.value = SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED
            return
        } catch (_: Throwable) {
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_UNAVAILABLE
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_UNAVAILABLE
            return
        }

        try {
            recorder.startRecording()
        } catch (_: Throwable) {
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_UNAVAILABLE
            return
        }

        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_UNAVAILABLE
            return
        }

        activeAudioRecord = recorder
        _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_ENABLED

        captureJob = scope.launch {
            val readChunk = ShortArray(sampleRate / 5) // 200ms chunks (3200 samples)
            var consecutiveReadErrors = 0
            try {
                while (isActive) {
                    if (!hasRecordAudioPermission()) {
                        _audioStatus.value = SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED
                        ringBuffer.clear()
                        _bufferedSeconds.value = 0.0
                        break
                    }
                    val readCount = try {
                        recorder.read(readChunk, 0, readChunk.size)
                    } catch (_: Throwable) {
                        AudioRecord.ERROR_INVALID_OPERATION
                    }
                    if (readCount > 0) {
                        consecutiveReadErrors = 0
                        ringBuffer.writeSamples(readChunk, 0, readCount)
                        _bufferedSeconds.value = ringBuffer.bufferedDurationSeconds
                    } else if (readCount < 0) {
                        consecutiveReadErrors += 1
                        if (consecutiveReadErrors >= 5) {
                            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_UNAVAILABLE
                            break
                        }
                        delay(100L)
                    } else {
                        delay(50L)
                    }
                }
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                try {
                    recorder.release()
                } catch (_: Throwable) {
                }
                if (activeAudioRecord === recorder) {
                    activeAudioRecord = null
                }
            }
        }
    }

    /**
     * Injects synthetic or test PCM samples directly into the rolling buffer (used in unit/Robolectric tests
     * where physical hardware AudioRecord is not attached, or when verifying buffer behavior).
     */
    fun feedPcmSamplesForTesting(samples: ShortArray) {
        ringBuffer.writeSamples(samples)
        _bufferedSeconds.value = ringBuffer.bufferedDurationSeconds
        if (_audioStatus.value == SosAudioStatus.DISABLED) {
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_ENABLED
        }
    }

    /**
     * Snapshots the current 15-second pre-trigger audio buffer when a candidate fall is first detected,
     * so the exact 15 seconds leading up to the fall are preserved in memory during the check-in countdown.
     * If the user confirms "I'm OK", [discardCandidateSnapshot] immediately zeroes and discards it.
     */
    fun snapshotCandidatePreTriggerAudio() {
        val snap = ringBuffer.snapshotRecentSamples()
        frozenCandidatePreTriggerSamples = if (snap.isNotEmpty()) snap else null
    }

    /**
     * Discards any temporary pre-trigger snapshot when the user confirms "I'm OK".
     */
    fun discardCandidateSnapshot() {
        frozenCandidatePreTriggerSamples?.let { Arrays.fill(it, 0.toShort()) }
        frozenCandidatePreTriggerSamples = null
        if (_audioStatus.value == SosAudioStatus.SOS_AUDIO_CAPTURED) {
            _audioStatus.value = SosAudioStatus.AUDIO_MONITORING_ENABLED
        }
    }

    /**
     * Called ONLY when the SOS workflow is confirmed (countdown expires without confirmation
     * or user taps "Send SOS Now").
     *
     * Copies the 15-second pre-trigger audio from the rolling buffer (plus any brief post-trigger
     * audio already in the buffer), encodes it into a finalized `.m4a` or `.wav` container in
     * `cacheDir/sos_audio/`, and returns the verified [SosAudioClipMetadata].
     *
     * Never claims 15 seconds were captured if the buffer was not running or had fewer samples.
     */
    suspend fun freezeAndFinalizeForSos(
        alertId: String,
        postTriggerWaitMillis: Long = 0L,
        preferAacM4a: Boolean = true
    ): SosAudioClipMetadata? = withContext(Dispatchers.IO) {
        val preTriggerSnapshot = frozenCandidatePreTriggerSamples
        frozenCandidatePreTriggerSamples = null

        if (postTriggerWaitMillis in 1L..3000L && isBufferCapturing) {
            delay(postTriggerWaitMillis)
        }

        val latestRingSnapshot = ringBuffer.snapshotRecentSamples()

        val combinedSamples: ShortArray
        val preTriggerSeconds: Double
        val postTriggerSeconds: Double

        if (preTriggerSnapshot != null && preTriggerSnapshot.isNotEmpty()) {
            preTriggerSeconds = preTriggerSnapshot.size.toDouble() / ringBuffer.sampleRateHz.toDouble()
            // Append up to 3 seconds of post-trigger audio from latestRingSnapshot if available
            val maxPostSamples = minOf(latestRingSnapshot.size, ringBuffer.sampleRateHz * 3)
            if (maxPostSamples > 0 && latestRingSnapshot !== preTriggerSnapshot) {
                postTriggerSeconds = maxPostSamples.toDouble() / ringBuffer.sampleRateHz.toDouble()
                combinedSamples = ShortArray(preTriggerSnapshot.size + maxPostSamples)
                System.arraycopy(preTriggerSnapshot, 0, combinedSamples, 0, preTriggerSnapshot.size)
                System.arraycopy(
                    latestRingSnapshot,
                    latestRingSnapshot.size - maxPostSamples,
                    combinedSamples,
                    preTriggerSnapshot.size,
                    maxPostSamples
                )
            } else {
                postTriggerSeconds = 0.0
                combinedSamples = preTriggerSnapshot
            }
            Arrays.fill(preTriggerSnapshot, 0.toShort())
        } else if (latestRingSnapshot.isNotEmpty()) {
            preTriggerSeconds = latestRingSnapshot.size.toDouble() / ringBuffer.sampleRateHz.toDouble()
            postTriggerSeconds = 0.0
            combinedSamples = latestRingSnapshot
        } else {
            return@withContext null
        }

        val outputDir = File(appContext.cacheDir, "sos_audio")
        val metadata = SosAudioContainerEncoder.encodeClipToFile(
            outputDir = outputDir,
            alertId = alertId,
            pcmSamples = combinedSamples,
            sampleRateHz = ringBuffer.sampleRateHz,
            preTriggerSeconds = preTriggerSeconds,
            postTriggerSeconds = postTriggerSeconds,
            preferAacM4a = preferAacM4a
        )

        if (metadata != null) {
            _lastCapturedClip.value = metadata
            _audioStatus.value = SosAudioStatus.SOS_AUDIO_CAPTURED
        }
        metadata
    }

    fun updateStatus(status: SosAudioStatus) {
        _audioStatus.value = status
    }

    /**
     * Stops the active [AudioRecord] loop and zeroes out the rolling PCM buffer in memory.
     */
    fun stopAndClearRollingBuffer() {
        captureJob?.cancel()
        captureJob = null
        try {
            activeAudioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            activeAudioRecord?.release()
        } catch (_: Throwable) {
        }
        activeAudioRecord = null
        discardCandidateSnapshot()
        ringBuffer.clear()
        _bufferedSeconds.value = 0.0
    }

    /**
     * Deletes local cached SOS audio files older than [retentionDays] or all files if `deleteAll = true`.
     */
    fun cleanupLocalRecordings(retentionDays: Int = 7, deleteAll: Boolean = false): Int {
        val dir = File(appContext.cacheDir, "sos_audio")
        if (!dir.exists()) return 0
        val cutoffMillis = System.currentTimeMillis() - (retentionDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L)
        var deletedCount = 0
        dir.listFiles()?.forEach { file ->
            if (deleteAll || file.lastModified() < cutoffMillis) {
                if (file.delete()) {
                    deletedCount += 1
                }
            }
        }
        if (deleteAll) {
            _lastCapturedClip.value = null
        }
        return deletedCount
    }
}
