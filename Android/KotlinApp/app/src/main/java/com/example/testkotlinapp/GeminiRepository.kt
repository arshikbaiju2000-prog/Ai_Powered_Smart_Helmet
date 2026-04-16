package com.example.testkotlinapp

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiRepository {

    // Placeholder for User API Key. 
    private val GOOGLE_API_KEY = "AIzaSyAph7idmCSOJYRnqQnoByPP7VPqeJwys7w"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class GeminiResponse(val text: String?, val audioPcmBase64: String?)

    private fun encodeToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitDepth: Int = 16): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * (bitDepth / 8)
        val header = ByteArray(44)
        
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * (bitDepth / 8)).toByte(); header[33] = 0
        header[34] = bitDepth.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte()
        header[43] = ((pcmData.size shr 24) and 0xff).toByte()
        
        return header + pcmData
    }

    suspend fun generateContent(pcmData16kHz: ByteArray): Result<GeminiResponse> = withContext(Dispatchers.IO) {
        if (pcmData16kHz.isEmpty()) {
            return@withContext Result.failure(Exception("No audio recorded"))
        }

        val wavData = encodeToWav(pcmData16kHz)
        val base64InputAudio = Base64.encodeToString(wavData, Base64.NO_WRAP)
        
        // --- STEP 1: Audio to Text ---
        val jsonPayloadText = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "You are a helpful AI assistant in a smart helmet. Please concisely respond to the user's audio input.")
                        })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "audio/wav")
                                put("data", base64InputAudio)
                            })
                        })
                    })
                })
            })
        }

        var aiTextResponse = ""
        var apiError: String? = null

        val modelsToTry = listOf(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GOOGLE_API_KEY",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$GOOGLE_API_KEY"
        )

        for (urlText in modelsToTry) {
            val request1 = Request.Builder()
                .url(urlText)
                .post(jsonPayloadText.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
                
            try {
                val response1 = client.newCall(request1).execute()
                if (response1.isSuccessful) {
                    val bodyStr = response1.body?.string() ?: ""
                    val responseJson = JSONObject(bodyStr)
                    
                    val candidates = responseJson.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                if (parts.getJSONObject(i).has("text")) {
                                    aiTextResponse += parts.getJSONObject(i).getString("text")
                                }
                            }
                        }
                    }
                    if (aiTextResponse.isNotBlank()) {
                        apiError = null
                        break // Success!
                    }
                } else {
                    val err = response1.body?.string()
                    apiError = "Text API Error: ${response1.code} $err"
                    android.util.Log.e("GeminiRepository", apiError)
                    if (response1.code == 429) {
                        kotlinx.coroutines.delay(2000L) // Wait 2s on 429 before trying the next model fallback
                    }
                }
            } catch (e: Exception) {
                apiError = "Exception: ${e.message}"
            }
        }
        
        aiTextResponse = aiTextResponse.trim()
        if (aiTextResponse.isEmpty()) {
             return@withContext Result.failure(Exception(apiError ?: "No readable text generated by AI"))
        }

        // --- STEP 2: Text to Audio using gemini-2.5-flash-preview-tts ---
        val urlAudio = "https://generativelanguage.googleapis.com/v1alpha/models/gemini-2.5-flash-preview-tts:generateContent?key=$GOOGLE_API_KEY"
        
        val jsonPayloadAudio = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            // Strip markdown and emojis just in case they hang the TTS engine
                            val cleanText = aiTextResponse.replace(Regex("[*#_~]"), "")
                            put("text", cleanText)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    put("AUDIO")
                })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", "Aoede")
                        })
                    })
                })
            })
        }
        
        val request2 = Request.Builder()
            .url(urlAudio)
            .post(jsonPayloadAudio.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
            
        var audioBase64: String? = null
        try {
            val response2 = client.newCall(request2).execute()
            if (!response2.isSuccessful) {
                val err = response2.body?.string()
                android.util.Log.e("GeminiRepository", "TTS API Error: ${response2.code} $err")
                // Fallback: at least return the text if TTS fails
                return@withContext Result.success(GeminiResponse(aiTextResponse, null))
            }

            val bodyStr = response2.body?.string() ?: ""
            val responseJson = JSONObject(bodyStr)
            
            val candidates = responseJson.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("inlineData")) {
                            val inlineData = part.getJSONObject("inlineData")
                            if (inlineData.optString("mimeType", "").startsWith("audio/")) {
                                audioBase64 = inlineData.getString("data")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiRepository", "TTS Exception: ${e.message}")
        }
        
        return@withContext Result.success(GeminiResponse(aiTextResponse, audioBase64))
    }
}
