package com.example.aiwebtabautomator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileBridge {
    private val lock = Any()

    suspend fun consumeInbox(context: Context, onCommand: (AutomationCommand) -> Unit) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                BridgeRepository.ensureDirectories(context)
                val files = BridgeRepository.inboxDir(context)
                    .listFiles { file ->
                        file.isFile && file.name.startsWith("response_") && file.name.endsWith(".txt")
                    }
                    ?.sortedBy { it.lastModified() }
                    ?.take(3)
                    .orEmpty()

                for (file in files) {
                    val message = runCatching {
                        file.readText(Charsets.UTF_8).take(64 * 1024).trim()
                    }.getOrNull()
                    if (message.isNullOrBlank()) {
                        moveToProcessed(file)
                        continue
                    }
                    val command = AutomationCommand(message = message)
                    CommandStore.enqueue(context, command)
                    onCommand(command)
                    moveToProcessed(file)
                }
            }
        }
    }

    private fun moveToProcessed(file: File) {
        val processed = File(file.parentFile, "processed")
        processed.mkdirs()
        val target = File(processed, "${file.nameWithoutExtension}_${System.currentTimeMillis()}.done")
        if (!file.renameTo(target)) file.delete()
    }
}
