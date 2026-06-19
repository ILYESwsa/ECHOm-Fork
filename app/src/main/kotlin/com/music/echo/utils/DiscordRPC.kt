package iad1tya.echo.music.utils

import android.content.Context
import android.util.Log
import iad1tya.echo.music.db.entities.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Discord Rich Presence via the self-bot websocket / user-auth gateway.
 * Uses the token stored in DataStore (DiscordTokenKey).
 *
 * Flow:
 *  1. POST /api/v9/users/@me  with the token to validate and fetch username/avatar.
 *  2. Open WebSocket to wss://gateway.discord.gg/?v=9&encoding=json
 *  3. Identify, then send OP 3 (Presence Update) whenever song changes.
 *
 * NOTE: Self-bot usage violates Discord ToS. This is provided as-is for
 * personal/local use matching existing Echo Music upstream intent.
 */
class DiscordRPC(
    private val context: Context,
    private val token: String,
) {
    companion object {
        private const val TAG = "DiscordRPC"
        private const val API_BASE = "https://discord.com/api/v9"
        private const val WS_URL  = "wss://gateway.discord.gg/?v=9&encoding=json"

        // Activity type IDs
        private const val ACTIVITY_LISTENING = 2
        private const val ACTIVITY_PLAYING   = 0
        private const val ACTIVITY_WATCHING  = 3
        private const val ACTIVITY_COMPETING = 5

        fun resolveVariables(text: String, song: Song): String {
            val artist = song.artists.joinToString(", ") { it.name }
            return text
                .replace("{title}",  song.title)
                .replace("{artist}", artist)
                .replace("{album}",  song.album?.title ?: "")
                .replace("{id}",     song.id)
        }
    }

    // ── WebSocket state ───────────────────────────────────────────────────────
    private var wsThread: Thread? = null
    private var wsWriter: OutputStreamWriter? = null
    @Volatile private var running = false
    @Volatile private var heartbeatInterval = 41250L
    private var sessionId: String? = null
    private var lastSequence: Int? = null

    // ── Cached user info ──────────────────────────────────────────────────────
    private var cachedUsername: String = ""
    private var cachedAvatar: String   = ""

    // ── Last pushed presence (for re-push on reconnect) ──────────────────────
    private var lastPresencePayload: JSONObject? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun isRpcRunning(): Boolean = running && wsThread?.isAlive == true

    /**
     * Validate the token with a lightweight REST call.
     * Returns (username, avatarUrl) on success, throws on failure.
     */
    suspend fun validateToken(): Pair<String, String> = withContext(Dispatchers.IO) {
        val conn = (URL("$API_BASE/users/@me").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", token)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", SuperProperties.userAgent)
            setRequestProperty("X-Super-Properties", SuperProperties.superPropertiesBase64)
            connectTimeout = 10_000
            readTimeout    = 10_000
        }
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            error("Token validation failed ($code): $err")
        }
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        val username   = json.optString("global_name").takeIf { it.isNotBlank() }
            ?: json.optString("username", "Unknown")
        val userId     = json.optString("id", "")
        val avatarHash = json.optString("avatar", "")
        val avatarUrl  = if (avatarHash.isNotBlank())
            "https://cdn.discordapp.com/avatars/$userId/$avatarHash.png?size=64"
        else ""
        cachedUsername = username
        cachedAvatar   = avatarUrl
        Pair(username, avatarUrl)
    }

    /**
     * Push a presence update. Starts the WS if not already running.
     */
    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long,
        playbackSpeed: Float = 1.0f,
        useDetails: Boolean = false,
        status: String = "online",
        button1Text: String = "",
        button1Visible: Boolean = true,
        button2Text: String = "",
        button2Visible: Boolean = true,
        activityType: String = "listening",
        activityName: String = "",
    ): Result<Unit> = runCatching {
        val payload = buildPresencePayload(
            song, currentPlaybackTimeMillis, playbackSpeed,
            useDetails, status, button1Text, button1Visible,
            button2Text, button2Visible, activityType, activityName,
        )
        lastPresencePayload = payload

        if (!isRpcRunning()) {
            startWebSocket(payload)
        } else {
            sendWsMessage(payload)
        }
    }

    /** Temporarily clear the presence (pause) */
    fun close() {
        try { sendWsMessage(buildClearPresencePayload()) } catch (_: Exception) {}
    }

    /** Fully disconnect and stop the WebSocket thread */
    fun closeRPC() {
        running = false
        try { sendWsMessage(buildClearPresencePayload()) } catch (_: Exception) {}
        wsThread?.interrupt()
        wsThread = null
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun startWebSocket(initialPresence: JSONObject) {
        running = true
        wsThread = Thread {
            try {
                runWebSocket(initialPresence)
            } catch (e: InterruptedException) {
                Log.d(TAG, "WS thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "WS error: ${e.message}")
            } finally {
                running = false
            }
        }.also {
            it.isDaemon = true
            it.name = "DiscordRPC-WS"
            it.start()
        }
    }

    private fun runWebSocket(initialPresence: JSONObject) {
        // Simple HTTP upgrade to WebSocket
        val wsConn = URL(WS_URL).openConnection() as HttpURLConnection
        wsConn.apply {
            requestMethod = "GET"
            setRequestProperty("Connection",           "Upgrade")
            setRequestProperty("Upgrade",              "websocket")
            setRequestProperty("Sec-WebSocket-Key",    "dGhlIHNhbXBsZSBub25jZQ==")
            setRequestProperty("Sec-WebSocket-Version","13")
            setRequestProperty("User-Agent",           SuperProperties.userAgent)
            connectTimeout = 10_000
            readTimeout    = 0 // keep-alive
        }

        // For the actual gateway we use OkHttp-style via java.net.URI + threads
        // Since we can't import OkHttp directly here without dependency issues,
        // use the discord4j-compatible manual approach via raw socket frames.
        // In practice, Echo already has OkHttp in the project, so we delegate.
        runGatewayWithOkHttp(initialPresence)
    }

    /**
     * Uses a raw OkHttp WebSocket (already a dependency via media3/coil).
     * Handles OP 10 Hello → OP 2 Identify → OP 3 Presence → heartbeat loop.
     */
    private fun runGatewayWithOkHttp(initialPresence: JSONObject) {
        val client = okhttp3.OkHttpClient.Builder()
            .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url(WS_URL)
            .header("User-Agent", SuperProperties.userAgent)
            .build()

        val latch = java.util.concurrent.CountDownLatch(1)

        val wsListener = object : okhttp3.WebSocketListener() {
            lateinit var ws: okhttp3.WebSocket

            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                ws = webSocket
                Log.d(TAG, "Gateway connected")
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                ws = webSocket
                handleGatewayMessage(ws, text, initialPresence)
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "Gateway failure: ${t.message}")
                running = false
                latch.countDown()
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gateway closed: $code $reason")
                running = false
                latch.countDown()
            }
        }

        val webSocket = client.newWebSocket(request, wsListener)

        // Store reference so we can write from updateSong
        wsWriterHolder = webSocket

        // Block this thread until the socket closes
        latch.await()
        client.dispatcher.executorService.shutdown()
    }

    @Volatile private var wsWriterHolder: okhttp3.WebSocket? = null

    private fun sendWsMessage(payload: JSONObject) {
        wsWriterHolder?.send(payload.toString())
            ?: Log.w(TAG, "sendWsMessage: no active WebSocket")
    }

    private var heartbeatJob: Thread? = null

    private fun handleGatewayMessage(
        ws: okhttp3.WebSocket,
        text: String,
        initialPresence: JSONObject,
    ) {
        val json = JSONObject(text)
        val op  = json.optInt("op", -1)
        val seq = if (json.isNull("s")) null else json.optInt("s")
        if (seq != null) lastSequence = seq

        when (op) {
            10 -> {
                // Hello — start heartbeat
                heartbeatInterval = json.getJSONObject("d").getLong("heartbeat_interval")
                startHeartbeat(ws)
                // Identify
                ws.send(buildIdentifyPayload().toString())
            }
            0  -> {
                // Dispatch
                val t = json.optString("t")
                if (t == "READY") {
                    sessionId = json.getJSONObject("d").optString("session_id")
                    Log.d(TAG, "Gateway READY, session=$sessionId")
                    // Send the initial presence right after READY
                    ws.send(initialPresence.toString())
                }
            }
            1  -> {
                // Heartbeat request
                ws.send(buildHeartbeatPayload().toString())
            }
            7  -> {
                // Reconnect
                ws.close(4000, "reconnect requested")
            }
            9  -> {
                // Invalid session
                Log.w(TAG, "Invalid session — re-identifying")
                Thread.sleep(2000)
                ws.send(buildIdentifyPayload().toString())
            }
        }
    }

    private fun startHeartbeat(ws: okhttp3.WebSocket) {
        heartbeatJob?.interrupt()
        heartbeatJob = Thread {
            try {
                // Initial jitter
                Thread.sleep((heartbeatInterval * Math.random()).toLong())
                while (running) {
                    ws.send(buildHeartbeatPayload().toString())
                    Thread.sleep(heartbeatInterval)
                }
            } catch (_: InterruptedException) {}
        }.also {
            it.isDaemon = true
            it.name = "DiscordRPC-HB"
            it.start()
        }
    }

    // ── Payload builders ──────────────────────────────────────────────────────

    private fun buildIdentifyPayload(): JSONObject = JSONObject().apply {
        put("op", 2)
        put("d", JSONObject().apply {
            put("token", token)
            put("capabilities", 16381)
            put("properties", SuperProperties.superProperties)
            put("presence", JSONObject().apply {
                put("status",     "online")
                put("since",      0)
                put("activities", JSONArray())
                put("afk",        false)
            })
            put("compress", false)
            put("client_state", JSONObject().apply {
                put("guild_versions", JSONObject())
            })
        })
    }

    private fun buildHeartbeatPayload(): JSONObject = JSONObject().apply {
        put("op", 1)
        put("d",  lastSequence ?: JSONObject.NULL)
    }

    private fun buildClearPresencePayload(): JSONObject = JSONObject().apply {
        put("op", 3)
        put("d", JSONObject().apply {
            put("status",     "online")
            put("since",      0)
            put("activities", JSONArray())
            put("afk",        false)
        })
    }

    private fun buildPresencePayload(
        song: Song,
        currentPlaybackTimeMillis: Long,
        playbackSpeed: Float,
        useDetails: Boolean,
        status: String,
        button1Text: String,
        button1Visible: Boolean,
        button2Text: String,
        button2Visible: Boolean,
        activityType: String,
        activityName: String,
    ): JSONObject {
        val artist = song.artists.joinToString(", ") { it.name }
        val songUrl = "https://music.youtube.com/watch?v=${song.id}"

        val activityTypeId = when (activityType.lowercase()) {
            "playing"    -> ACTIVITY_PLAYING
            "watching"   -> ACTIVITY_WATCHING
            "competing"  -> ACTIVITY_COMPETING
            else         -> ACTIVITY_LISTENING
        }

        val resolvedName = when {
            activityName.isNotBlank() -> resolveVariables(activityName, song)
            activityTypeId == ACTIVITY_LISTENING -> "Echo Music"
            else -> song.title
        }

        val activity = JSONObject().apply {
            put("name",  resolvedName)
            put("type",  activityTypeId)
            put("flags", 1)

            // Details + state
            if (useDetails) {
                put("details", resolveVariables("{title}", song))
                put("state",   resolveVariables("{artist}", song))
            } else {
                put("details", song.title)
                put("state",   artist)
            }

            // Timestamps — show elapsed
            val nowMs = System.currentTimeMillis()
            val startMs = nowMs - currentPlaybackTimeMillis.coerceAtLeast(0L)
            put("timestamps", JSONObject().apply {
                put("start", startMs)
            })

            // Assets
            put("assets", JSONObject().apply {
                val artUrl = song.thumbnailUrl?.takeIf { it.isNotBlank() } ?: ""
                if (artUrl.isNotBlank()) {
                    put("large_image", artUrl)
                    put("large_text",  song.title)
                } else {
                    put("large_image", "echo_music_logo")
                    put("large_text",  "Echo Music")
                }
                put("small_image", "echo_music_logo")
                put("small_text",  "Echo Music")
            })

            // Buttons
            val buttons     = JSONArray()
            val buttonLinks = JSONArray()

            if (button1Visible && button1Text.isNotBlank()) {
                val label = resolveVariables(button1Text, song)
                buttons.put(JSONObject().apply {
                    put("label", label.take(32))
                    put("url",   songUrl)
                })
                buttonLinks.put(songUrl)
            }
            if (button2Visible && button2Text.isNotBlank()) {
                val label = resolveVariables(button2Text, song)
                val url2  = "https://echo.music.app"
                buttons.put(JSONObject().apply {
                    put("label", label.take(32))
                    put("url",   url2)
                })
                buttonLinks.put(url2)
            }
            if (buttons.length() > 0) {
                put("buttons",     buttons)
                put("metadata", JSONObject().apply {
                    put("button_urls", buttonLinks)
                })
            }
        }

        return JSONObject().apply {
            put("op", 3)
            put("d", JSONObject().apply {
                put("status",     status.takeIf { it in listOf("online","idle","dnd","invisible") } ?: "online")
                put("since",      0)
                put("activities", JSONArray().put(activity))
                put("afk",        false)
            })
        }
    }
}
