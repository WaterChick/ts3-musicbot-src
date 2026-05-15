package ts3musicbot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ts3musicbot.chat.ChatReader
import ts3musicbot.util.CommandList
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object StateStore {
    private const val VERSION = 1
    private val file = File(System.getenv("STATE_FILE")?.takeIf { it.isNotBlank() } ?: "/data/state.json")
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "state-store").apply { isDaemon = true }
        }
    private val lock = Any()
    private var pendingSave: ScheduledFuture<*>? = null

    data class Snapshot(
        val volume: Int,
        val queue: List<String>,
        val nowPlayingLink: String,
        val nowPlayingPosition: Long,
        val autoResume: Boolean,
    )

    fun load(): Snapshot? {
        return try {
            if (!file.isFile) return null
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val queueArr = json.optJSONArray("queue") ?: JSONArray()
            val queue = (0 until queueArr.length()).mapNotNull { queueArr.optString(it).takeIf { s -> s.isNotBlank() } }
            val np = json.optJSONObject("nowPlaying")
            Snapshot(
                volume = json.optInt("volume", 100).coerceIn(0, 100),
                queue = queue,
                nowPlayingLink = np?.optString("link", "").orEmpty(),
                nowPlayingPosition = np?.optLong("position", 0L) ?: 0L,
                autoResume = json.optBoolean("autoResume", true),
            )
        } catch (e: Exception) {
            println("StateStore: failed to load ${file.path}: ${e.message}")
            null
        }
    }

    /** Schedule a save after [delayMs]; coalesces rapid calls (e.g., volume slider drag). */
    fun saveDebounced(delayMs: Long = 200) {
        synchronized(lock) {
            pendingSave?.cancel(false)
            pendingSave =
                scheduler.schedule(
                    {
                        try {
                            writeFile()
                        } catch (e: Exception) {
                            println("StateStore: save failed: ${e.message}")
                        }
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
        }
    }

    private fun writeFile() {
        val sq = BotState.getSongQueue()
        val queueLinks = sq?.getQueue()?.map { it.link.link }?.filter { it.isNotBlank() } ?: emptyList()
        val nowPlaying = sq?.nowPlaying()
        val json =
            JSONObject().apply {
                put("version", VERSION)
                put("volume", BotState.volume)
                put("queue", JSONArray(queueLinks))
                if (nowPlaying != null && !nowPlaying.isEmpty() && nowPlaying.link.link.isNotBlank()) {
                    put(
                        "nowPlaying",
                        JSONObject()
                            .put("link", nowPlaying.link.link)
                            .put("position", sq.getTrackPosition()),
                    )
                }
                put("autoResume", BotState.autoResume)
            }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /**
     * Restore a previously saved snapshot. Runs in a background coroutine — returns immediately.
     * Calls parseLine() with latestMsgUsername="__restore__" so that responses log to console only
     * (see ChatReader.printToChat). Tracks are added sequentially, waiting up to [perTrackTimeoutMs]
     * per track for the queue size to advance before moving on.
     */
    fun attemptRestore(
        chatReader: ChatReader,
        commandList: CommandList,
        perTrackTimeoutMs: Long = 15_000L,
    ) {
        val snapshot = load() ?: return
        val sq = chatReader.getSongQueue()

        // Restore the flags that affect later persist() calls FIRST so we don't
        // overwrite the saved value with the default mid-restore.
        BotState.autoResume = snapshot.autoResume

        if (snapshot.volume != BotState.volume) {
            sq.setVolume(snapshot.volume)
            println("StateStore: restored volume to ${snapshot.volume}.")
        }

        // Build restore list: prepend nowPlayingLink if present so playback continues with it.
        val urls = buildList {
            if (snapshot.nowPlayingLink.isNotBlank()) add(snapshot.nowPlayingLink)
            addAll(snapshot.queue.filter { it != snapshot.nowPlayingLink })
        }
        if (urls.isEmpty()) {
            println("StateStore: nothing to restore.")
            return
        }

        println("StateStore: restoring ${urls.size} track(s) from previous session...")
        val queueAddCmd = commandList.commandList["queue-add"] ?: "%queue-add"
        CoroutineScope(Dispatchers.IO).launch {
            val savedUsername = chatReader.latestMsgUsername
            chatReader.latestMsgUsername = "__restore__"
            try {
                val initialSize = sq.getQueue().size
                for ((i, url) in urls.withIndex()) {
                    val expected = initialSize + i + 1
                    chatReader.parseLine("$queueAddCmd $url")
                    val deadline = System.currentTimeMillis() + perTrackTimeoutMs
                    while (sq.getQueue().size < expected && System.currentTimeMillis() < deadline) {
                        delay(150)
                    }
                    if (sq.getQueue().size < expected) {
                        println("StateStore: track $url timed out; skipping.")
                    }
                }
                println("StateStore: restored ${sq.getQueue().size - initialSize} track(s).")
                if (snapshot.autoResume && sq.getQueue().isNotEmpty()) {
                    println("StateStore: auto-resuming playback.")
                    sq.startQueue()
                }
            } finally {
                chatReader.latestMsgUsername = savedUsername
            }
        }
    }
}
