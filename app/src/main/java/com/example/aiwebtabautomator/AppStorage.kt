package com.example.aiwebtabautomator

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableSharedFlow

object AutomationBus {
    val events = MutableSharedFlow<AutomationCommand>(extraBufferCapacity = 64)
    fun emit(command: AutomationCommand) {
        events.tryEmit(command)
    }
}

object AppPrefs {
    private const val PREFS = "automation_preferences"
    private const val MAPPINGS = "coordinate_mappings"
    private const val ROUTES = "host_routes"
    private const val HTTP_ENABLED = "http_enabled"
    private const val FILE_BRIDGE_ENABLED = "file_bridge_enabled"
    private val gson = Gson()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun saveMapping(context: Context, mapping: CoordinateMapping) {
        val all = mappings(context).toMutableMap()
        all[mapping.host] = mapping
        prefs(context).edit().putString(MAPPINGS, gson.toJson(all)).apply()
    }

    @Synchronized
    fun deleteMapping(context: Context, host: String) {
        val all = mappings(context).toMutableMap()
        val normalized = normalizeHost(host) ?: return
        all.remove(normalized)
        prefs(context).edit().putString(MAPPINGS, gson.toJson(all)).apply()
    }

    fun mappings(context: Context): Map<String, CoordinateMapping> {
        val json = prefs(context).getString(MAPPINGS, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, CoordinateMapping>>() {}.type
        return runCatching { gson.fromJson<Map<String, CoordinateMapping>>(json, type) }
            .getOrNull()
            ?: emptyMap()
    }

    fun mappingFor(context: Context, urlOrHost: String?): CoordinateMapping? {
        val host = normalizeHost(urlOrHost) ?: return null
        return mappings(context)[host]
    }

    @Synchronized
    fun saveRoute(context: Context, host: String, tabIndex: Int) {
        val normalized = normalizeHost(host) ?: return
        val current = routes(context).toMutableMap()
        current[normalized] = tabIndex
        prefs(context).edit().putString(ROUTES, gson.toJson(current)).apply()
    }

    fun routeFor(context: Context, host: String?): Int? {
        val normalized = normalizeHost(host) ?: return null
        return routes(context)[normalized]
    }

    private fun routes(context: Context): Map<String, Int> {
        val json = prefs(context).getString(ROUTES, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, Int>>() {}.type
        return runCatching { gson.fromJson<Map<String, Int>>(json, type) }.getOrNull() ?: emptyMap()
    }

    fun isHttpEnabled(context: Context): Boolean =
        prefs(context).getBoolean(HTTP_ENABLED, true)

    fun setHttpEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(HTTP_ENABLED, enabled).apply()
    }

    fun isFileBridgeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(FILE_BRIDGE_ENABLED, true)

    fun setFileBridgeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(FILE_BRIDGE_ENABLED, enabled).apply()
    }
}

object CommandStore {
    private const val PREFS = "automation_queue"
    private const val QUEUE = "pending_commands"
    private val gson = Gson()
    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enqueue(context: Context, command: AutomationCommand) = synchronized(lock) {
        val list = all(context).toMutableList()
        if (list.none { it.id == command.id }) {
            list.add(command)
            // Keep a crashed/restarted app from accumulating unbounded work.
            val bounded = list.takeLast(50)
            prefs(context).edit().putString(QUEUE, gson.toJson(bounded)).apply()
        }
    }

    fun all(context: Context): List<AutomationCommand> = synchronized(lock) {
        val json = prefs(context).getString(QUEUE, null) ?: return emptyList()
        val type = object : TypeToken<List<AutomationCommand>>() {}.type
        runCatching { gson.fromJson<List<AutomationCommand>>(json, type) }.getOrNull() ?: emptyList()
    }

    fun remove(context: Context, id: String) = synchronized(lock) {
        val remaining = all(context).filterNot { it.id == id }
        prefs(context).edit().putString(QUEUE, gson.toJson(remaining)).apply()
    }
}
