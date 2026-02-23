package com.example

import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// This must be outside the module function
@Serializable
data class GameMessage(
    val type: String,
    val word: String? = null,
    val player: String? = null,
    val playerId: String? = null,
    val score: Int? = null,
    val players: List<PlayerInfo>? = null,
    val hotPlayerIndex: Int? = null,
    val revealed: List<Boolean>? = null,
    val wordIndex: Int? = null,
    val timeRemaining: Int? = null,
    val gameStartTime: Long? = null,
    val theme: String? = null
)

@Serializable
data class PlayerInfo(
    val id: String,
    val name: String
)

data class Player(
    val id: String,
    val name: String,
    var lastHeartbeat: Long = System.currentTimeMillis(),
    val session: DefaultWebSocketServerSession
)

data class RoomState(
    val players: MutableList<Player> = mutableListOf(),
    var currentHotPlayerIndex: Int = 0,
    var currentWord: String = "",
    var revealedWords: MutableList<Boolean> = mutableListOf(),
    var gameStartTime: Long? = null  // When the game timer started (null = not started)
)

@Serializable
data class RoundEntry(
    val id: String,
    val score: Int,
    val players: List<String>,
    val mode: String,
    val roomId: String? = null,
    val sessionHash: String,
    val timestamp: Long
)

@Serializable
data class PhraseEvent(
    val phrase: String,
    val status: String,
    val timeSeconds: Int
)

@Serializable
data class RoundSubmission(
    val score: Int,
    val players: List<String>,
    val mode: String,
    val roomId: String? = null,
    val phrases: List<PhraseEvent> = emptyList()
)

@Serializable
data class RoundSubmissionResponse(
    val status: String,
    val roundId: String? = null,
    val percentile: Int? = null,
    val error: String? = null
)

@Serializable
data class GroupedScore(
    val score: Int,
    val roundCount: Int
)

@Serializable
data class LeaderboardResponse(
    val topRounds: List<RoundEntry>,
    val grouped: List<GroupedScore>,
    val totalRounds: Int
)

@Serializable
data class RoundLog(
    val id: String,
    val timestamp: Long,
    val mode: String,
    val roomId: String?,
    val sessionHash: String,
    val playerCount: Int,
    val score: Int,
    val phrases: List<PhraseEvent>
)

@Serializable
data class PhraseStats(
    val phrase: String,
    val totalSeen: Int,
    val gotIt: Int,
    val skipped: Int,
    val timedOut: Int,
    val avgTimeSeconds: Double,
    val successRate: Double
)

@Serializable
data class DateRange(
    val from: Long,
    val to: Long
)

@Serializable
data class AnalyticsResponse(
    val phrases: List<PhraseStats>,
    val totalRounds: Int,
    val dateRange: DateRange
)

fun generateSessionHash(remoteHost: String): String {
    val salt = "hotwords-session-salt"
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest("$salt:$remoteHost".toByteArray())
    return hash.take(3).joinToString("") { "%02x".format(it) }
}

// JSONL persistence for phrase analytics
private val dataDir = if (File("/opt/hotwords/data").exists()) File("/opt/hotwords/data") else File("data")
private val roundLogFile = File(dataDir, "rounds.jsonl")
private val roundLogLock = Any()
private val jsonLenient = Json { ignoreUnknownKeys = true }
private val validStatuses = setOf("got-it", "skipped", "timed-out")

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    dataDir.mkdirs()
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
        maxFrameSize = 65536
        pingPeriod = Duration.ofSeconds(30)
    }
    install(ContentNegotiation) {
        json()
    }

    // Round-based leaderboard storage (key: UUID -> round entry)
    val roundEntries = ConcurrentHashMap<String, RoundEntry>()

    // Shared game state
    val rooms = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()
    val roomScores = ConcurrentHashMap<String, Int>()
    val roomWords = ConcurrentHashMap<String, String>()  // Per-room active word
    val roomStates = ConcurrentHashMap<String, RoomState>()  // Per-room player state
    val roomThemes = ConcurrentHashMap<String, String>()  // Per-room phrase theme
    val sessionToPlayerId = ConcurrentHashMap<DefaultWebSocketServerSession, String>()  // Session to player ID mapping

    // Rate limiting for POST /api/scores: IP -> list of timestamps
    val scoreRateLimit = ConcurrentHashMap<String, MutableList<Long>>()

    // Track when rooms became empty for cleanup
    val roomEmptySince = ConcurrentHashMap<String, Long>()

    // Per-connection message rate limiting: session -> list of timestamps
    val wsMessageTimestamps = ConcurrentHashMap<DefaultWebSocketServerSession, MutableList<Long>>()

    // TTL cleanup coroutine - removes inactive players every 5 seconds
    val cleanupJob = CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(5000)
            val now = System.currentTimeMillis()
            val ttlMs = 15000L  // 15 seconds TTL

            // Clean up round entries older than 24 hours
            val ttl24h = 24 * 60 * 60 * 1000L
            roundEntries.entries.removeIf { now - it.value.timestamp > ttl24h }

            // Cap round entries at 50000 — evict oldest if exceeded
            if (roundEntries.size > 50_000) {
                val oldest = roundEntries.values.sortedBy { it.timestamp }.take(roundEntries.size - 50_000)
                oldest.forEach { roundEntries.remove(it.id) }
            }

            roomStates.forEach { (roomId, state) ->
                val playersToRemove = state.players.filter { now - it.lastHeartbeat > ttlMs }
                if (playersToRemove.isNotEmpty()) {
                    playersToRemove.forEach { player ->
                        state.players.remove(player)
                        sessionToPlayerId.remove(player.session)
                    }

                    // Adjust hot player index if needed
                    if (state.players.isNotEmpty()) {
                        state.currentHotPlayerIndex = state.currentHotPlayerIndex % state.players.size
                    } else {
                        state.currentHotPlayerIndex = 0
                    }

                    // Broadcast updated player list
                    val playerInfoList = state.players.map { PlayerInfo(it.id, it.name) }
                    val playerListMsg = GameMessage(
                        type = "PLAYER_LIST",
                        players = playerInfoList,
                        hotPlayerIndex = state.currentHotPlayerIndex
                    )
                    rooms[roomId]?.forEach { session ->
                        try {
                            CoroutineScope(Dispatchers.IO).launch {
                                session.sendSerialized(playerListMsg)
                            }
                        } catch (e: Exception) {
                            // Ignore send errors
                        }
                    }
                }

                // Track empty rooms for cleanup
                val roomSessions = rooms[roomId]
                if (state.players.isEmpty() && (roomSessions == null || roomSessions.isEmpty())) {
                    roomEmptySince.putIfAbsent(roomId, now)
                } else {
                    roomEmptySince.remove(roomId)
                }
            }

            // Clean up rooms that have been empty for >60 seconds
            roomEmptySince.entries.removeIf { (roomId, emptySince) ->
                if (now - emptySince > 60_000L) {
                    rooms.remove(roomId)
                    roomStates.remove(roomId)
                    roomWords.remove(roomId)
                    roomScores.remove(roomId)
                    roomThemes.remove(roomId)
                    true
                } else false
            }

            // Clean up stale rate limit entries
            scoreRateLimit.entries.removeIf { (_, timestamps) ->
                timestamps.removeIf { now - it > 60_000L }
                timestamps.isEmpty()
            }
        }
    }

    environment.monitor.subscribe(ApplicationStopped) {
        cleanupJob.cancel()
    }
    // Original adult phrases saved in phrases/original-phrases.txt
    val gameWords = listOf(
        // Animals & Creatures
        "Baby shark", "Grumpy cat", "Flying squirrel", "Hungry hippo",
        "Silly goose", "Lazy sloth", "Sneaky snake", "Crazy monkey",
        "Dancing penguin", "Barking dog", "Fuzzy bunny", "Angry bird",
        "Spider web", "Dinosaur egg", "Dragon fire", "Unicorn horn",
        // Food & Yummy Stuff
        "Pizza party", "Ice cream cone", "Chocolate cake", "Candy bar",
        "Popcorn bucket", "Banana split", "Jelly bean", "Hot dog",
        "French fries", "Bubble gum", "Cotton candy", "Apple juice",
        "Peanut butter", "Mac and cheese", "Taco Tuesday", "Pancake stack",
        // Silly Actions
        "Belly flop", "Cannonball splash", "Pillow fight", "Food fight",
        "Dance battle", "Thumb war", "Snow angel", "Belly laugh",
        "High five", "Fist bump", "Happy dance", "Silly walk",
        "Jumping jacks", "Cartwheel", "Belly button", "Funny face",
        // Movies & Shows
        "Toy Story", "Finding Nemo", "Lion King", "Frozen",
        "Spider Man", "Super Mario", "Harry Potter", "Star Wars",
        "Jurassic Park", "Inside Out", "Kung Fu Panda", "Shrek",
        "Moana", "Encanto", "Minecraft", "Pokemon",
        // School & Play
        "Recess time", "Show and tell", "Field trip", "Snow day",
        "Summer break", "Lunch box", "Back pack", "Playground slide",
        "Dodgeball", "Hide and seek", "Tag you're it", "Capture the flag",
        "Treasure hunt", "Water balloon", "Nerf gun", "Lego tower",
        // Space & Adventure
        "Rocket ship", "Shooting star", "Black hole", "Alien invasion",
        "Moon landing", "Time travel", "Treasure map", "Pirate ship",
        "Magic wand", "Secret door", "Haunted house", "Roller coaster",
        "Water slide", "Zip line", "Bungee jump", "Hot air balloon",
        // Sounds & Expressions
        "Plot twist", "Mind blown", "Game over", "Level up",
        "Power up", "Mic drop", "Brain freeze", "Oops",
        "No way", "So cool", "Super gross", "Epic fail",
        "Piece of cake", "Easy peasy", "Oh snap", "Yolo",
        // Sports & Games
        "Slam dunk", "Home run", "Hole in one", "Touchdown",
        "Belly slide", "Cannonball", "Marco Polo", "Red rover",
        "King of the hill", "Musical chairs", "Simon says", "Freeze tag",
        "Rock paper scissors", "Thumb wrestling", "Arm wrestling", "Tug of war",
        // Nature & Weather
        "Rainbow", "Thunderstorm", "Snowball fight", "Sand castle",
        "Mud puddle", "Volcano eruption", "Earthquake", "Tornado",
        "Tidal wave", "Northern lights", "Shooting star", "Lightning bolt",
        // Weird & Wacky
        "Stinky cheese", "Slime monster", "Booger flick", "Burp contest",
        "Fart noise", "Wet willy", "Rubber duck", "Whoopee cushion",
        "Tickle monster", "Stink bug", "Snot rocket", "Armpit fart"
    )

    // RKO Company Culture phrases — PLACEHOLDER, replace with real phrases
    val rkoWords = listOf(
        "Circle back", "Move the needle", "Deep dive",
        "Low hanging fruit", "Run it up the flagpole", "Boil the ocean",
        "Think outside the box", "Synergy", "Alignment",
        "Take it offline", "Touch base", "Level set",
        "Net net", "Action item", "Parking lot",
        "Bandwidth check", "Hard stop", "Pivot",
        "Value add", "Best practice", "Core competency",
        "Drill down", "Ecosystem", "Leverage",
        "Pain point", "Stakeholder", "Deliverable",
        "Game plan", "On the radar", "In the weeds",
        "Big picture thinking", "Run the numbers", "Close the loop",
        "Open the kimono", "Eat our own dog food", "Drink the Kool Aid",
        "Raise the bar", "Move the goalposts", "Shift the paradigm",
        "Cross pollinate", "Future proof", "Right size"
    )

    fun getWordForRoom(roomId: String): String {
        return if (roomThemes[roomId] == "rko") rkoWords.random() else gameWords.random()
    }

    val appVersion = Application::class.java.`package`?.implementationVersion ?: "dev"

    routing {
        staticResources("/", "static") {
            default("index.html")
        }

        get("/health") {
            call.respond(mapOf("status" to "ok", "version" to appVersion))
        }

        post("/api/scores") {
            try {
                val remoteIp = call.request.local.remoteHost
                val now = System.currentTimeMillis()

                // Rate limit: max 10 requests per minute per IP
                val timestamps = scoreRateLimit.computeIfAbsent(remoteIp) { mutableListOf() }
                val rateLimited = synchronized(timestamps) {
                    timestamps.removeIf { now - it > 60_000L }
                    if (timestamps.size >= 10) {
                        true
                    } else {
                        timestamps.add(now)
                        false
                    }
                }
                if (rateLimited) {
                    call.respond(HttpStatusCode.TooManyRequests, RoundSubmissionResponse(
                        status = "error",
                        error = "Rate limit exceeded"
                    ))
                    return@post
                }

                val submission = call.receive<RoundSubmission>()

                // Validate submission fields
                if (submission.mode !in listOf("local", "online")) {
                    call.respond(HttpStatusCode.BadRequest, RoundSubmissionResponse(
                        status = "error", error = "Invalid mode"
                    ))
                    return@post
                }
                val validatedScore = submission.score.coerceIn(0, 999)
                val validatedPlayers = submission.players
                    .take(20)
                    .map { it.take(20) }

                val sessionHash = generateSessionHash(remoteIp)
                val roundId = UUID.randomUUID().toString()

                val entry = RoundEntry(
                    id = roundId,
                    score = validatedScore,
                    players = validatedPlayers,
                    mode = submission.mode,
                    roomId = submission.roomId?.take(30),
                    sessionHash = sessionHash,
                    timestamp = now
                )
                roundEntries[roundId] = entry

                // Persist phrase-level data to JSONL
                if (submission.phrases.isNotEmpty()) {
                    val validatedPhrases = submission.phrases
                        .take(200)
                        .filter { it.status in validStatuses }
                        .map { it.copy(
                            phrase = it.phrase.take(100),
                            timeSeconds = it.timeSeconds.coerceIn(0, 3600)
                        ) }
                    val roundLog = RoundLog(
                        id = roundId,
                        timestamp = now,
                        mode = submission.mode,
                        roomId = submission.roomId?.take(30),
                        sessionHash = sessionHash,
                        playerCount = validatedPlayers.size,
                        score = validatedScore,
                        phrases = validatedPhrases
                    )
                    try {
                        synchronized(roundLogLock) {
                            roundLogFile.appendText(Json.encodeToString(RoundLog.serializer(), roundLog) + "\n")
                        }
                    } catch (e: Exception) {
                        // Log but don't fail the request
                        println("Failed to write round log: ${e.message}")
                    }
                }

                // Compute percentile among rounds in last 24h (same mode/room)
                val ttl24h = 24 * 60 * 60 * 1000L
                val comparable = roundEntries.values.filter { r ->
                    r.mode == submission.mode &&
                    (now - r.timestamp) <= ttl24h &&
                    (if (submission.mode == "online") r.roomId == submission.roomId else true)
                }
                val total = comparable.size
                val atOrBelow = comparable.count { it.score <= validatedScore }
                val percentile = if (total > 0) (atOrBelow * 100) / total else 100

                call.respond(RoundSubmissionResponse(
                    status = "ok",
                    roundId = roundId,
                    percentile = percentile
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, RoundSubmissionResponse(
                    status = "error",
                    error = e.message
                ))
            }
        }

        get("/api/leaderboard") {
            val mode = call.request.queryParameters["mode"] ?: "local"
            val room = call.request.queryParameters["room"]
            val now = System.currentTimeMillis()
            val ttl24h = 24 * 60 * 60 * 1000L

            val filtered = roundEntries.values
                .filter { it.mode == mode }
                .filter { (now - it.timestamp) <= ttl24h }
                .filter { if (mode == "online" && room != null) it.roomId == room else true }
                .sortedWith(compareByDescending<RoundEntry> { it.score }.thenBy { it.timestamp })

            val totalRounds = filtered.size

            if (totalRounds <= 10) {
                // Show all individually, no grouping needed
                call.respond(LeaderboardResponse(
                    topRounds = filtered,
                    grouped = emptyList(),
                    totalRounds = totalRounds
                ))
            } else {
                // Top 10 individually, but extend for ties at the boundary
                val cutoffScore = filtered.getOrNull(9)?.score ?: 0
                val topRounds = filtered.takeWhile { it.score > cutoffScore } +
                    filtered.filter { it.score == cutoffScore }
                val cappedTop = topRounds.take(15)
                val topIds = cappedTop.map { it.id }.toSet()

                // Group remaining scores
                val remaining = filtered.filter { it.id !in topIds }
                val grouped = remaining
                    .groupBy { it.score }
                    .map { (score, rounds) -> GroupedScore(score, rounds.size) }
                    .sortedByDescending { it.score }

                call.respond(LeaderboardResponse(
                    topRounds = cappedTop,
                    grouped = grouped,
                    totalRounds = totalRounds
                ))
            }
        }

        get("/api/analytics/phrases") {
            try {
                if (!roundLogFile.exists()) {
                    call.respond(AnalyticsResponse(
                        phrases = emptyList(),
                        totalRounds = 0,
                        dateRange = DateRange(0, 0)
                    ))
                    return@get
                }

                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)

                val rounds = synchronized(roundLogLock) {
                    roundLogFile.readLines()
                }.mapNotNull { line ->
                    try { jsonLenient.decodeFromString(RoundLog.serializer(), line) } catch (e: Exception) { null }
                }.filter { it.timestamp >= since }

                if (rounds.isEmpty()) {
                    call.respond(AnalyticsResponse(
                        phrases = emptyList(),
                        totalRounds = 0,
                        dateRange = DateRange(since, System.currentTimeMillis())
                    ))
                    return@get
                }

                // Aggregate by normalized phrase
                data class Agg(var gotIt: Int = 0, var skipped: Int = 0, var timedOut: Int = 0, var totalTime: Int = 0, var gotItCount: Int = 0)
                val byPhrase = mutableMapOf<String, Agg>()

                for (round in rounds) {
                    for (pe in round.phrases) {
                        val key = pe.phrase.lowercase().trim()
                        val agg = byPhrase.getOrPut(key) { Agg() }
                        when (pe.status) {
                            "got-it" -> { agg.gotIt++; agg.totalTime += pe.timeSeconds; agg.gotItCount++ }
                            "skipped" -> agg.skipped++
                            "timed-out" -> agg.timedOut++
                        }
                    }
                }

                val phraseStats = byPhrase.map { (phrase, agg) ->
                    val total = agg.gotIt + agg.skipped + agg.timedOut
                    PhraseStats(
                        phrase = phrase,
                        totalSeen = total,
                        gotIt = agg.gotIt,
                        skipped = agg.skipped,
                        timedOut = agg.timedOut,
                        avgTimeSeconds = if (agg.gotItCount > 0) (agg.totalTime.toDouble() / agg.gotItCount * 10).toLong() / 10.0 else 0.0,
                        successRate = if (total > 0) (agg.gotIt.toDouble() / total * 100).toLong() / 100.0 else 0.0
                    )
                }
                    .sortedBy { it.successRate }
                    .take(limit)

                val minTs = rounds.minOf { it.timestamp }
                val maxTs = rounds.maxOf { it.timestamp }

                call.respond(AnalyticsResponse(
                    phrases = phraseStats,
                    totalRounds = rounds.size,
                    dateRange = DateRange(minTs, maxTs)
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
            }
        }

        webSocket("/game/{roomId}") {
            val roomId = call.parameters["roomId"] ?: "lobby"

            // Validate room ID: alphanumeric (plus hyphens/underscores), max 30 chars
            if (roomId.length > 30 || !roomId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid room ID"))
                return@webSocket
            }

            // Room cap: max 100 concurrent rooms
            if (!rooms.containsKey(roomId) && rooms.size >= 100) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many rooms"))
                return@webSocket
            }

            val room = rooms.computeIfAbsent(roomId) { Collections.synchronizedSet(LinkedHashSet()) }
            val state = roomStates.computeIfAbsent(roomId) { RoomState() }

            // Player cap: max 20 players per room
            if (state.players.size >= 20) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Room is full"))
                return@webSocket
            }

            room.add(this)
            roomEmptySince.remove(roomId)

            try {
                // Initialize room word if needed, or get existing
                val currentWord = roomWords.computeIfAbsent(roomId) { getWordForRoom(roomId) }
                val currentScore = roomScores.getOrDefault(roomId, 0)
                sendSerialized(GameMessage(type = "SCORE_UPDATE", score = currentScore))

                // Initialize revealed words for the current phrase
                if (state.revealedWords.isEmpty() || state.currentWord != currentWord) {
                    state.currentWord = currentWord
                    state.revealedWords = currentWord.split(" ").map { false }.toMutableList()
                }

                // Don't send word yet - wait for SET_NAME to determine role

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        // Per-connection rate limit: max 30 messages per second
                        val msgNow = System.currentTimeMillis()
                        val msgTimestamps = wsMessageTimestamps.computeIfAbsent(this) { mutableListOf() }
                        val msgDropped = synchronized(msgTimestamps) {
                            msgTimestamps.removeIf { msgNow - it > 1000L }
                            if (msgTimestamps.size >= 30) {
                                true
                            } else {
                                msgTimestamps.add(msgNow)
                                false
                            }
                        }
                        if (msgDropped) continue  // Drop excess messages

                        val text = frame.readText()
                        val received = Json.decodeFromString<GameMessage>(text)

                        when (received.type) {
                            "SET_NAME" -> {
                                val playerId = received.playerId ?: UUID.randomUUID().toString()
                                // Sanitize player name: trim, cap at 20 chars, strip HTML tags
                                val rawName = (received.player ?: "Anonymous").trim().take(20)
                                val playerName = rawName.replace(Regex("<[^>]*>"), "").ifEmpty { "Anonymous" }

                                // Set room theme if provided
                                val theme = received.theme
                                if (theme != null) {
                                    roomThemes[roomId] = theme
                                    // Re-pick word if game hasn't started yet
                                    if (state.gameStartTime == null) {
                                        val themedWord = getWordForRoom(roomId)
                                        roomWords[roomId] = themedWord
                                        state.currentWord = themedWord
                                        state.revealedWords = themedWord.split(" ").map { false }.toMutableList()
                                    }
                                }

                                // Check if this player already exists (reconnect)
                                val existingPlayer = state.players.find { it.id == playerId }
                                if (existingPlayer != null) {
                                    // Update session and heartbeat
                                    state.players.remove(existingPlayer)
                                    sessionToPlayerId.remove(existingPlayer.session)
                                }

                                // Add/update player
                                val player = Player(playerId, playerName, System.currentTimeMillis(), this)
                                state.players.add(player)
                                sessionToPlayerId[this] = playerId

                                // Broadcast player list to all
                                val playerInfoList = state.players.map { PlayerInfo(it.id, it.name) }
                                val playerListMsg = GameMessage(
                                    type = "PLAYER_LIST",
                                    players = playerInfoList,
                                    hotPlayerIndex = state.currentHotPlayerIndex
                                )
                                room.forEach { session ->
                                    session.sendSerialized(playerListMsg)
                                }

                                // Send current word/progress to this player
                                sendSerialized(GameMessage(
                                    type = "NEW_WORD",
                                    word = state.currentWord,
                                    revealed = state.revealedWords
                                ))

                                // If game is in progress, send timer sync
                                if (state.gameStartTime != null) {
                                    val elapsed = (System.currentTimeMillis() - state.gameStartTime!!) / 1000
                                    val remaining = maxOf(0, 60 - elapsed.toInt())
                                    sendSerialized(GameMessage(
                                        type = "TIMER_SYNC",
                                        timeRemaining = remaining
                                    ))
                                }
                            }

                            "START_GAME" -> {
                                // Start the game timer for this room
                                if (state.gameStartTime == null) {
                                    state.gameStartTime = System.currentTimeMillis()
                                    // Broadcast to all players
                                    room.forEach { session ->
                                        session.sendSerialized(GameMessage(
                                            type = "GAME_STARTED",
                                            timeRemaining = 60
                                        ))
                                    }
                                }
                            }

                            "NEW_ROUND" -> {
                                // Update theme if provided
                                val roundTheme = received.theme
                                if (roundTheme != null) {
                                    roomThemes[roomId] = roundTheme
                                }
                                // Start a new round - reset timer and score, get new word
                                state.gameStartTime = System.currentTimeMillis()
                                roomScores[roomId] = 0

                                // Get a new word
                                val newWord = getWordForRoom(roomId)
                                roomWords[roomId] = newWord
                                state.currentWord = newWord
                                state.revealedWords = newWord.split(" ").map { false }.toMutableList()

                                // Broadcast new round to all players
                                val newRoundMsg = GameMessage(
                                    type = "NEW_ROUND",
                                    timeRemaining = 60
                                )
                                val newWordMsg = GameMessage(
                                    type = "NEW_WORD",
                                    word = newWord,
                                    revealed = state.revealedWords.toList()
                                )

                                room.forEach { session ->
                                    session.sendSerialized(newRoundMsg)
                                    session.sendSerialized(newWordMsg)
                                }
                            }

                            "HEARTBEAT" -> {
                                val playerId = sessionToPlayerId[this]
                                if (playerId != null) {
                                    state.players.find { it.id == playerId }?.lastHeartbeat = System.currentTimeMillis()
                                }
                            }

                            "WORD_MATCH" -> {
                                // A guesser matched a word - only non-hot players allowed
                                val senderPlayerId = sessionToPlayerId[this]
                                if (senderPlayerId == null) continue
                                val senderIndex = state.players.indexOfFirst { it.id == senderPlayerId }
                                if (senderIndex == state.currentHotPlayerIndex) continue  // Hot player can't send word matches
                                val wordIndex = received.wordIndex
                                if (wordIndex != null && wordIndex >= 0 && wordIndex < state.revealedWords.size) {
                                    state.revealedWords[wordIndex] = true

                                    // Broadcast progress to all
                                    val progressMsg = GameMessage(
                                        type = "WORD_PROGRESS",
                                        revealed = state.revealedWords.toList()
                                    )
                                    room.forEach { session ->
                                        session.sendSerialized(progressMsg)
                                    }

                                    // Check if all words revealed - auto claim victory
                                    if (state.revealedWords.all { it }) {
                                        // Rotate to next describer
                                        if (state.players.isNotEmpty()) {
                                            state.currentHotPlayerIndex = (state.currentHotPlayerIndex + 1) % state.players.size
                                        }

                                        val newWord = getWordForRoom(roomId)
                                        roomWords[roomId] = newWord
                                        state.currentWord = newWord
                                        state.revealedWords = newWord.split(" ").map { false }.toMutableList()

                                        val newScore = roomScores.merge(roomId, 1, Int::plus) ?: 1
                                        val winnerMsg = GameMessage(
                                            type = "ROUND_WON",
                                            player = received.player ?: "Someone",
                                            score = newScore
                                        )

                                        // Send updated player list with new hot player
                                        val playerInfoList = state.players.map { PlayerInfo(it.id, it.name) }
                                        val playerListMsg = GameMessage(
                                            type = "PLAYER_LIST",
                                            players = playerInfoList,
                                            hotPlayerIndex = state.currentHotPlayerIndex
                                        )

                                        val newWordMsg = GameMessage(
                                            type = "NEW_WORD",
                                            word = newWord,
                                            revealed = state.revealedWords.toList()
                                        )

                                        room.forEach { session ->
                                            session.sendSerialized(winnerMsg)
                                            session.sendSerialized(playerListMsg)
                                            session.sendSerialized(newWordMsg)
                                        }
                                    }
                                }
                            }

                            "DESCRIBER_SLIP" -> {
                                // Describer accidentally said a forbidden word - only hot player
                                val senderPlayerId = sessionToPlayerId[this] ?: continue
                                val senderIndex = state.players.indexOfFirst { it.id == senderPlayerId }
                                if (senderIndex != state.currentHotPlayerIndex) continue
                                val wordIndex = received.wordIndex
                                if (wordIndex != null && wordIndex >= 0 && wordIndex < state.revealedWords.size) {
                                    if (!state.revealedWords[wordIndex]) {
                                        state.revealedWords[wordIndex] = true

                                        // Broadcast the slip to all players
                                        val slipMsg = GameMessage(
                                            type = "DESCRIBER_SLIPPED",
                                            player = received.player,
                                            word = state.currentWord.split(" ").getOrNull(wordIndex),
                                            wordIndex = wordIndex
                                        )
                                        room.forEach { session ->
                                            session.sendSerialized(slipMsg)
                                        }

                                        // Broadcast updated progress
                                        val progressMsg = GameMessage(
                                            type = "WORD_PROGRESS",
                                            revealed = state.revealedWords.toList()
                                        )
                                        room.forEach { session ->
                                            session.sendSerialized(progressMsg)
                                        }
                                    }
                                }
                            }

                            "DESCRIBER_FAIL" -> {
                                // Describer said the whole phrase - only hot player
                                val senderPlayerId = sessionToPlayerId[this] ?: continue
                                val senderIndex = state.players.indexOfFirst { it.id == senderPlayerId }
                                if (senderIndex != state.currentHotPlayerIndex) continue
                                // Rotate to next describer
                                if (state.players.isNotEmpty()) {
                                    state.currentHotPlayerIndex = (state.currentHotPlayerIndex + 1) % state.players.size
                                }

                                val newWord = getWordForRoom(roomId)
                                roomWords[roomId] = newWord
                                state.currentWord = newWord
                                state.revealedWords = newWord.split(" ").map { false }.toMutableList()

                                // Broadcast the fail
                                val failMsg = GameMessage(
                                    type = "DESCRIBER_FAILED",
                                    player = received.player
                                )

                                // Send updated player list with new hot player
                                val playerInfoList = state.players.map { PlayerInfo(it.id, it.name) }
                                val playerListMsg = GameMessage(
                                    type = "PLAYER_LIST",
                                    players = playerInfoList,
                                    hotPlayerIndex = state.currentHotPlayerIndex
                                )

                                val newWordMsg = GameMessage(
                                    type = "NEW_WORD",
                                    word = newWord,
                                    revealed = state.revealedWords.toList()
                                )

                                room.forEach { session ->
                                    session.sendSerialized(failMsg)
                                    session.sendSerialized(playerListMsg)
                                    session.sendSerialized(newWordMsg)
                                }
                            }

                            "CLAIM_VICTORY" -> {
                                // Any room member can claim victory
                                if (sessionToPlayerId[this] == null) continue
                                // Rotate to next describer
                                if (state.players.isNotEmpty()) {
                                    state.currentHotPlayerIndex = (state.currentHotPlayerIndex + 1) % state.players.size
                                }

                                val newWord = getWordForRoom(roomId)
                                roomWords[roomId] = newWord
                                state.currentWord = newWord
                                state.revealedWords = newWord.split(" ").map { false }.toMutableList()

                                val newScore = roomScores.merge(roomId, 1, Int::plus) ?: 1
                                val winnerMsg = GameMessage(
                                    type = "ROUND_WON",
                                    player = received.player ?: "A Player",
                                    score = newScore
                                )

                                // Send updated player list with new hot player
                                val playerInfoList = state.players.map { PlayerInfo(it.id, it.name) }
                                val playerListMsg = GameMessage(
                                    type = "PLAYER_LIST",
                                    players = playerInfoList,
                                    hotPlayerIndex = state.currentHotPlayerIndex
                                )

                                val newWordMsg = GameMessage(
                                    type = "NEW_WORD",
                                    word = newWord,
                                    revealed = state.revealedWords.toList()
                                )

                                room.forEach { session ->
                                    session.sendSerialized(winnerMsg)
                                    session.sendSerialized(playerListMsg)
                                    session.sendSerialized(newWordMsg)
                                }
                            }

                            "SKIP_WORD" -> {
                                // Only hot player (describer) can skip
                                val senderPlayerId = sessionToPlayerId[this] ?: continue
                                val senderIndex = state.players.indexOfFirst { it.id == senderPlayerId }
                                if (senderIndex != state.currentHotPlayerIndex) continue
                                // Skip = same describer, new phrase (they take the penalty!)
                                val newWord = getWordForRoom(roomId)
                                roomWords[roomId] = newWord
                                state.currentWord = newWord
                                state.revealedWords = newWord.split(" ").map { false }.toMutableList()

                                val skipMsg = GameMessage(type = "WORD_SKIPPED", player = received.player)

                                val newWordMsg = GameMessage(
                                    type = "NEW_WORD",
                                    word = newWord,
                                    revealed = state.revealedWords.toList()
                                )

                                room.forEach { session ->
                                    session.sendSerialized(skipMsg)
                                    session.sendSerialized(newWordMsg)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Socket error: ${e.localizedMessage}")
            } finally {
                room.remove(this)
                wsMessageTimestamps.remove(this)
                // Remove player from state
                val playerId = sessionToPlayerId.remove(this)
                if (playerId != null) {
                    state.players.removeIf { it.id == playerId }
                    // Adjust hot player index if needed
                    if (state.players.isNotEmpty()) {
                        state.currentHotPlayerIndex = state.currentHotPlayerIndex % state.players.size
                    } else {
                        state.currentHotPlayerIndex = 0
                    }

                    // Broadcast updated player list
                    val playerInfoList = state.players.map { PlayerInfo(it.id, it.name) }
                    val playerListMsg = GameMessage(
                        type = "PLAYER_LIST",
                        players = playerInfoList,
                        hotPlayerIndex = state.currentHotPlayerIndex
                    )
                    room.forEach { session ->
                        try {
                            CoroutineScope(Dispatchers.IO).launch {
                                session.sendSerialized(playerListMsg)
                            }
                        } catch (e: Exception) {
                            // Ignore send errors
                        }
                    }
                }
            }
        }
    }
}