package com.example.aiwebtabautomator

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object BridgeRepository {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun externalRoot(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun inboxDir(context: Context): File = File(externalRoot(context), "inbox")

    fun outboxDir(context: Context): File = File(externalRoot(context), "outbox")

    fun processedDir(context: Context): File = File(inboxDir(context), "processed")

    fun ensureDirectories(context: Context) {
        inboxDir(context).mkdirs()
        outboxDir(context).mkdirs()
        processedDir(context).mkdirs()
    }

    fun writeResult(context: Context, command: AutomationCommand, result: AutomationResult) {
        scope.launch {
            ensureDirectories(context)
            val safeId = command.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(outboxDir(context), "response_${System.currentTimeMillis()}_$safeId.json")
            file.writeText(gson.toJson(result), Charsets.UTF_8)

            val callback = command.callbackUrl?.trim().orEmpty()
            if (callback.startsWith("https://") || callback.startsWith("http://")) {
                runCatching {
                    val body = gson.toJson(result)
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder().url(callback).post(body).build()
                    client.newCall(request).execute().use { /* best-effort callback */ }
                }
            }
        }
    }
}
