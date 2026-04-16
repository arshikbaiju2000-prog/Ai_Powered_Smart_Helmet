package com.example.testkotlinapp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class AudioHandler {

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioTrack: AudioTrack? = null
    private val outputStream = ByteArrayOutputStream()
    private var recordJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startRecording(coroutineScope: CoroutineScope) {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2 // Use a slightly larger buffer
        )

        outputStream.reset()
        audioRecord?.startRecording()
        isRecording = true

        recordJob = coroutineScope.launch(Dispatchers.IO) {
            val audioData = ByteArray(bufferSize)
            while (isActive && isRecording) {
                val read = audioRecord?.read(audioData, 0, bufferSize) ?: 0
                if (read > 0) {
                    outputStream.write(audioData, 0, read)
                }
            }
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    suspend fun stopRecordingAndGetPCM(): ByteArray = withContext(Dispatchers.IO) {
        isRecording = false
        recordJob?.join()
        return@withContext outputStream.toByteArray()
    }

    fun playAudio(pcmData: ByteArray) {
        val playbackSampleRate = 24000
        val bufferSize = AudioTrack.getMinBufferSize(
            playbackSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack?.release()
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            playbackSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        audioTrack?.play()
        audioTrack?.write(pcmData, 0, pcmData.size)
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
