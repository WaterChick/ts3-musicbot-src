package ts3musicbot

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

object WebServer {
    private const val PORT = 8090
    private var server: HttpServer? = null

    fun start() {
        if (server != null) return
        server = HttpServer.create(InetSocketAddress("0.0.0.0", PORT), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            createContext("/api/queue") { ex -> handleQueue(ex) }
            createContext("/api/local-files") { ex -> handleLocalFiles(ex) }
            createContext("/") { ex -> handleStatic(ex) }
            start()
        }
        println("Web UI running on port $PORT")
    }

    fun stop() {
        server?.stop(0)
    }

    // ── routing ──────────────────────────────────────────────────────────

    private fun handleQueue(ex: HttpExchange) {
        val path = ex.requestURI.path
        val method = ex.requestMethod
        when {
            path == "/api/queue" && method == "GET" -> getQueue(ex)
            path == "/api/queue/add" && method == "POST" -> addToQueue(ex)
            path == "/api/queue/skip" && method == "POST" -> controlQueue(ex) { sq -> sq.skipSong() }
            path == "/api/queue/pause" && method == "POST" -> controlQueue(ex) { sq -> sq.pausePlayback() }
            path == "/api/queue/resume" && method == "POST" -> controlQueue(ex) { sq -> sq.resumePlayback() }
            path == "/api/queue/play" && method == "POST" -> controlQueue(ex) { sq -> sq.startQueue() }
            path == "/api/queue/stop" && method == "POST" -> controlQueue(ex) { sq -> sq.stopQueue() }
            path == "/api/queue/clear" && method == "POST" -> controlQueue(ex) { sq -> sq.clearQueue() }
            path.startsWith("/api/queue/") && method == "DELETE" -> deleteTrack(ex, path)
            else -> ex.sendError(404, "Not found")
        }
    }

    private fun handleLocalFiles(ex: HttpExchange) {
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
        sq.deleteTrack(index)
        ex.sendJson(JSONObject().put("ok", true))
    }

    private fun controlQueue(ex: HttpExchange, action: (ts3musicbot.util.SongQueue) -> Unit) {
        val sq = BotState.getSongQueue()
            ?: run { ex.sendError(503, "Bot not ready"); return }
        action(sq)
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
        val bytes = msg.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
