package ts3musicbot

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import ts3musicbot.util.SongQueue
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Executors

object WebServer {
    private const val PORT = 8090
    private var server: HttpServer? = null
    private val webUser: String? = System.getenv("WEB_USER")?.takeIf { it.isNotBlank() }
    private val webPass: String? = System.getenv("WEB_PASS")?.takeIf { it.isNotBlank() }

    fun start() {
        if (server != null) return
        server = HttpServer.create(InetSocketAddress("0.0.0.0", PORT), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            createContext("/api/queue") { ex -> handleQueue(ex) }
            createContext("/api/volume") { ex -> handleVolume(ex) }
            createContext("/api/local-files") { ex -> handleLocalFiles(ex) }
            createContext("/") { ex -> handleStatic(ex) }
            start()
        }
        println("Web UI running on port $PORT")
    }

    fun stop() {
        server?.stop(0)
    }

    // ── auth ─────────────────────────────────────────────────────────────

    private fun HttpExchange.isAuthorized(): Boolean {
        if (webUser == null || webPass == null) return true
        val header = requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("Basic ")) return false
        val decoded = runCatching {
            String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
        }.getOrNull() ?: return false
        val colon = decoded.indexOf(':')
        if (colon < 0) return false
        return decoded.substring(0, colon) == webUser && decoded.substring(colon + 1) == webPass
    }

    private fun HttpExchange.sendUnauthorized() {
        responseHeaders.add("WWW-Authenticate", "Basic realm=\"TS3 Music Bot\"")
        val bytes = "Unauthorized".toByteArray(Charsets.UTF_8)
        sendResponseHeaders(401, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    // ── routing ──────────────────────────────────────────────────────────

    private fun handleQueue(ex: HttpExchange) {
        if (!ex.isAuthorized()) { ex.sendUnauthorized(); return }
        val path = ex.requestURI.path
        val method = ex.requestMethod
        when {
            path == "/api/queue" && method == "GET" -> getQueue(ex)
            path == "/api/queue/add" && method == "POST" -> addToQueue(ex)
            path == "/api/queue/skip" && method == "POST" -> doSkip(ex)
            path == "/api/queue/pause" && method == "POST" -> doPause(ex)
            path == "/api/queue/resume" && method == "POST" -> doResume(ex)
            path == "/api/queue/play" && method == "POST" -> doPlay(ex)
            path == "/api/queue/stop" && method == "POST" -> doStop(ex)
            path == "/api/queue/clear" && method == "POST" -> doClear(ex)
            path == "/api/queue/shuffle" && method == "POST" -> doShuffle(ex)
            path == "/api/queue/reorder" && method == "POST" -> reorderTrack(ex)
            path.startsWith("/api/queue/move-top/") && method == "POST" -> moveTrackToTop(ex, path)
            path.startsWith("/api/queue/") && method == "DELETE" -> deleteTrack(ex, path)
            else -> ex.sendError(404, "Not found")
        }
    }

    private fun handleVolume(ex: HttpExchange) {
        if (!ex.isAuthorized()) { ex.sendUnauthorized(); return }
        when (ex.requestMethod) {
            "GET" -> ex.sendJson(JSONObject().put("volume", BotState.volume))
            "POST" -> {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: run { ex.sendError(400, "Invalid JSON"); return }
                val v = json.optInt("volume", -1)
                if (v !in 0..100) { ex.sendError(400, "volume must be 0-100"); return }
                val sq = BotState.getSongQueue()
                    ?: run {
                        BotState.volume = v
                        StateStore.saveDebounced()
                        ex.sendJson(JSONObject().put("ok", true).put("volume", v))
                        return
                    }
                sq.setVolume(v)
                ex.sendJson(JSONObject().put("ok", true).put("volume", BotState.volume))
            }
            else -> ex.sendError(405, "Method not allowed")
        }
    }

    private fun handleLocalFiles(ex: HttpExchange) {
        if (!ex.isAuthorized()) { ex.sendUnauthorized(); return }
        if (ex.requestMethod != "GET") { ex.sendError(405, "Method not allowed"); return }
        val dir = File(BotState.musicDir)
        val arr = JSONArray()
        if (dir.isDirectory) {
            dir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?.forEachIndexed { i, file ->
                    arr.put(JSONObject().apply {
                        put("index", i + 1)
                        put("name", file.name)
                    })
                }
        }
        ex.sendJson(JSONObject().put("files", arr))
    }

    private fun handleStatic(ex: HttpExchange) {
        if (!ex.isAuthorized()) { ex.sendUnauthorized(); return }
        if (ex.requestMethod != "GET") { ex.sendError(405, "Method not allowed"); return }
        val html = WebServer::class.java.getResourceAsStream("/web/index.html")
            ?.readBytes()
            ?: run { ex.sendError(404, "Not found"); return }
        ex.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
        ex.sendResponseHeaders(200, html.size.toLong())
        ex.responseBody.use { it.write(html) }
    }

    // ── handlers ─────────────────────────────────────────────────────────

    private fun getQueue(ex: HttpExchange) {
        val sq = BotState.getSongQueue()
        val queueArr = JSONArray()
        sq?.getQueue()?.forEach { track ->
            queueArr.put(JSONObject().apply {
                put("title", track.title.name)
                put("artists", track.artists.toShortString())
                put("link", track.link.link)
            })
        }
        val np = sq?.nowPlaying()
        val nowPlaying = JSONObject().apply {
            put("title", np?.title?.name ?: "")
            put("artists", np?.artists?.toShortString() ?: "")
            put("link", np?.link?.link ?: "")
            put("empty", np?.isEmpty() ?: true)
        }
        val state = sq?.getState()?.name?.lowercase()?.removePrefix("queue_") ?: "stopped"
        ex.sendJson(
            JSONObject()
                .put("queue", queueArr)
                .put("nowPlaying", nowPlaying)
                .put("state", state)
        )
    }

    private fun addToQueue(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: run { ex.sendError(400, "Invalid JSON"); return }

        val reader = BotState.chatReader
            ?: run { ex.sendError(503, "Bot not ready"); return }
        val cmd = BotState.commandList.commandList["queue-add"] ?: "%queue-add"

        val command = when {
            json.has("url") -> "$cmd ${json.getString("url")}"
            json.has("local") -> "$cmd local:${json.getInt("local")}"
            else -> { ex.sendError(400, "Missing url or local field"); return }
        }

        reader.latestMsgUsername = "__webui__"
        reader.parseLine(command)
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun deleteTrack(ex: HttpExchange, path: String) {
        val index = path.substringAfterLast("/").toIntOrNull()
            ?: run { ex.sendError(400, "Invalid index"); return }
        val sq = BotState.getSongQueue()
            ?: run { ex.sendError(503, "Bot not ready"); return }
        val size = sq.getQueue().size
        if (index < 0 || index >= size) { ex.sendError(409, "Track no longer in queue"); return }
        sq.deleteTrack(index)
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun reorderTrack(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: run { ex.sendError(400, "Invalid JSON"); return }
        val from = json.optInt("from", -1)
        val to = json.optInt("to", -1)
        if (from < 0 || to < 0) { ex.sendError(400, "Invalid from/to"); return }
        val sq = BotState.getSongQueue()
            ?: run { ex.sendError(503, "Bot not ready"); return }
        val size = sq.getQueue().size
        if (from >= size || to >= size) { ex.sendError(409, "Track no longer in queue"); return }
        if (from == to) { ex.sendJson(JSONObject().put("ok", true)); return }
        sq.reorderTrack(from, to)
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun moveTrackToTop(ex: HttpExchange, path: String) {
        val index = path.substringAfterLast("/").toIntOrNull()
            ?: run { ex.sendError(400, "Invalid index"); return }
        val sq = BotState.getSongQueue()
            ?: run { ex.sendError(503, "Bot not ready"); return }
        val size = sq.getQueue().size
        if (index <= 0 || index >= size) { ex.sendError(409, "Track no longer in queue"); return }
        sq.moveToTop(index)
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun withQueue(ex: HttpExchange, block: (sq: SongQueue) -> Unit) {
        val sq = BotState.getSongQueue()
            ?: run { ex.sendError(503, "Bot not ready"); return }
        block(sq)
    }

    private fun doPlay(ex: HttpExchange) = withQueue(ex) { sq ->
        when (sq.getState()) {
            SongQueue.State.QUEUE_PLAYING -> { ex.sendError(409, "Queue is already playing"); return@withQueue }
            SongQueue.State.QUEUE_PAUSED  -> { ex.sendError(409, "Queue is paused — use Resume"); return@withQueue }
            SongQueue.State.QUEUE_STOPPED -> {}
        }
        if (sq.getQueue().isEmpty()) {
            ex.sendError(409, "Queue is empty"); return@withQueue
        }
        sq.startQueue()
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun doStop(ex: HttpExchange) = withQueue(ex) { sq ->
        if (sq.getState() == SongQueue.State.QUEUE_STOPPED) {
            ex.sendError(409, "Nothing is playing"); return@withQueue
        }
        sq.stopQueue()
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun doPause(ex: HttpExchange) = withQueue(ex) { sq ->
        when (sq.getState()) {
            SongQueue.State.QUEUE_PAUSED  -> { ex.sendError(409, "Already paused"); return@withQueue }
            SongQueue.State.QUEUE_STOPPED -> { ex.sendError(409, "Nothing to pause"); return@withQueue }
            SongQueue.State.QUEUE_PLAYING -> {}
        }
        sq.pausePlayback()
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun doResume(ex: HttpExchange) = withQueue(ex) { sq ->
        when (sq.getState()) {
            SongQueue.State.QUEUE_PLAYING -> { ex.sendError(409, "Already playing"); return@withQueue }
            SongQueue.State.QUEUE_STOPPED -> { ex.sendError(409, "Nothing to resume"); return@withQueue }
            SongQueue.State.QUEUE_PAUSED  -> {}
        }
        sq.resumePlayback()
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun doSkip(ex: HttpExchange) = withQueue(ex) { sq ->
        if (sq.getQueue().isEmpty()) {
            ex.sendError(409, "Nothing to skip"); return@withQueue
        }
        sq.skipSong()
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun doClear(ex: HttpExchange) = withQueue(ex) { sq ->
        if (sq.getQueue().isEmpty()) {
            ex.sendError(409, "Queue is already empty"); return@withQueue
        }
        sq.clearQueue()
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun doShuffle(ex: HttpExchange) = withQueue(ex) { sq ->
        if (sq.getQueue().size < 2) {
            ex.sendError(409, "Need at least 2 tracks to shuffle"); return@withQueue
        }
        sq.shuffleQueue()
        ex.sendJson(JSONObject().put("ok", true))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun HttpExchange.sendJson(body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        responseHeaders.add("Access-Control-Allow-Origin", "*")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.sendError(code: Int, msg: String) {
        val bytes = JSONObject().put("error", msg).toString().toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
