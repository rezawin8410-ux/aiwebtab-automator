package com.example.aiwebtabautomator

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Minimal loopback-only HTTP server. It intentionally avoids a heavyweight server framework.
 * Termux can reach 127.0.0.1:8080 on the same Android device.
 */
class LocalHttpServer(
    private val port: Int = 8080,
    private val onCommand: (AutomationCommand) -> Unit
) {
    private val gson = Gson()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            runCatching {
                ServerSocket().also { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                    serverSocket = socket
                }
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handle(client) }
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            runCatching {
                client.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }

                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty()
                if (method == "GET" && path.substringBefore('?') == "/health") {
                    respond(client, 200, mapOf("ok" to true, "service" to "AI Web Tab Automator"))
                    return
                }
                if (method != "POST" || path.substringBefore('?') != "/send") {
                    respond(client, 404, mapOf("ok" to false, "error" to "Use POST /send or GET /health"))
                    return
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength <= 0 || contentLength > 64 * 1024) {
                    respond(client, 413, mapOf("ok" to false, "error" to "Body must be 1..65536 bytes"))
                    return
                }
                val body = CharArray(contentLength)
                var read = 0
                while (read < body.size) {
                    val n = reader.read(body, read, body.size - read)
                    if (n <= 0) break
                    read += n
                }
                val command = parseCommand(String(body, 0, read), path)
                    ?: run {
                        respond(client, 400, mapOf("ok" to false, "error" to "Missing message"))
                        return
                    }
                onCommand(command)
                respond(client, 202, mapOf("ok" to true, "accepted" to true, "requestId" to command.id))
            }.onFailure {
                runCatching { respond(client, 500, mapOf("ok" to false, "error" to "Server error")) }
            }
        }
    }

    private fun parseCommand(body: String, path: String): AutomationCommand? {
        val query = parseForm(path.substringAfter('?', ""))
        val values = if (body.trimStart().startsWith("{")) {
            runCatching {
                val obj = JsonParser.parseString(body).asJsonObject
                mapOf(
                    "message" to obj.get("message")?.asString,
                    "tabIndex" to obj.get("tabIndex")?.asString,
                    "host" to obj.get("host")?.asString,
                    "callbackUrl" to obj.get("callbackUrl")?.asString
                )
            }.getOrNull() ?: emptyMap()
        } else {
            parseForm(body)
        }
        val message = values["message"]?.takeIf { it.isNotBlank() } ?: query["message"]
        if (message.isNullOrBlank()) return null
        val tab = (values["tabIndex"] ?: query["tabIndex"])?.toIntOrNull()
        return AutomationCommand(
            message = message,
            tabIndex = tab,
            host = values["host"] ?: query["host"],
            callbackUrl = values["callbackUrl"] ?: query["callbackUrl"]
        )
    }

    private fun parseForm(value: String): Map<String, String> =
        value.split('&')
            .filter { it.isNotBlank() }
            .mapNotNull { pair ->
                val pieces = pair.split('=', limit = 2)
                if (pieces.size != 2) return@mapNotNull null
                URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces[1], "UTF-8")
            }
            .toMap()

    private fun respond(socket: Socket, status: Int, payload: Map<String, Any>) {
        val body = gson.toJson(payload)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Internal Server Error"
        }
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $status $reason\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(body)
        writer.flush()
    }
}
