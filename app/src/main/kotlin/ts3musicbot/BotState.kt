package ts3musicbot

import ts3musicbot.chat.ChatReader
import ts3musicbot.util.CommandList
import ts3musicbot.util.SongQueue

object BotState {
    @Volatile var chatReader: ChatReader? = null
    @Volatile var commandList: CommandList = CommandList()
    val musicDir: String = System.getenv("MUSIC_DIR") ?: "/data/music"

    fun getSongQueue(): SongQueue? = chatReader?.getSongQueue()
}
