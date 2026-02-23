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
data class RoundSubmission(
    val score: Int,
    val players: List<String>,
    val mode: String,
    val roomId: String? = null
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

fun generateSessionHash(remoteHost: String): String {
    val salt = "hotwords-session-salt"
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest("$salt:$remoteHost".toByteArray())
    return hash.take(3).joinToString("") { "%02x".format(it) }
}

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
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
    val gameWords = listOf(
        // Activities & Lifestyle
        "Running late", "Couch potato", "Road trip", "Window shopping", "People watching",
        "Channel surfing", "Binge watching", "Party pooper", "Night owl", "Early bird",
        "Backseat driver", "Joy ride", "Happy hour", "Beauty sleep", "Power nap",
        "All nighter", "Gym rat", "Beach bum", "Movie buff", "Sunday scaries",
        // Food & Drink
        "Piece of cake", "Spill the beans", "Hot potato", "Cool beans", "Bad apple",
        "Bread winner", "Smart cookie", "Tough cookie", "Big cheese", "Top banana",
        "Sour grapes", "Easy as pie", "Cream of the crop", "Best thing since sliced bread",
        "Butter fingers", "Egg on your face", "Small potatoes", "In a pickle",
        "Full plate", "Food coma", "Brain freeze", "Bite off more than you can chew",
        // Animals
        "Cat nap", "Dog days", "Fish out of water", "Wild goose chase", "Dark horse",
        "Eager beaver", "Busy bee", "Social butterfly", "Party animal", "Loan shark",
        "Scaredy cat", "Cat got your tongue", "Puppy love", "Top dog",
        "Let sleeping dogs lie", "Monkey business", "Monkey see monkey do",
        "Hold your horses", "One trick pony", "Bull in a china shop",
        "Cash cow", "Holy cow", "Pig out", "Guinea pig", "Chicken out",
        "Sitting duck", "Cold turkey", "Free as a bird", "Bird brain",
        "When pigs fly", "Straight from the horse's mouth", "Elephant in the room",
        // Weather & Nature
        "Under the weather", "Rain check", "Storm is brewing", "Cloud nine", "Lightning fast",
        "Right as rain", "Come rain or shine", "Steal your thunder",
        "Perfect storm", "Calm before the storm", "Head in the clouds",
        "Ray of sunshine", "Walking on sunshine", "Chasing rainbows", "Silver lining",
        "Tip of the iceberg", "Break the ice", "Snowball effect",
        // Sports & Competition
        "Home run", "Slam dunk", "Hole in one", "Drop the ball", "On the ball",
        "Game face", "Fair game", "Hail Mary", "Moving the goalposts",
        "Par for the course", "Behind the eight ball", "Knock it out of the park",
        "Down to the wire", "Jump through hoops", "Raise the bar",
        "Threw a curveball", "Photo finish", "Front runner", "Goal line stand",
        // Pop Culture & Modern
        "Mic drop", "Plot twist", "Game changer", "Power move", "Main character energy",
        "Hot take", "Living rent free", "Caught in 4K", "Reality check",
        "Plot armor", "Origin story", "Redemption arc", "Side quest",
        "Doom scrolling", "Touch grass", "Glow up", "Vibe check",
        "Read the room", "Left on read", "Hits different", "Built different",
        "It's giving", "Say less", "Rent free", "Understood the assignment",
        // Famous Phrases & Sayings
        "Actions speak louder than words", "Barking up the wrong tree",
        "Burning bridges", "Caught red handed", "Cost an arm and a leg",
        "Cry over spilled milk", "Curiosity killed the cat", "Devil's advocate",
        "Every cloud has a silver lining", "Hit the nail on the head",
        "Jump on the bandwagon", "Keep your eyes peeled", "Kill two birds with one stone",
        "Let the cat out of the bag", "Method to the madness",
        "Miss the boat", "Speak of the devil", "Steal the spotlight",
        "Take it with a grain of salt", "The ball is in your court",
        "The best of both worlds", "Under the radar", "Up in the air",
        "Wouldn't hurt a fly", "You can say that again",
        // Common Expressions
        "Break a leg", "Hit the road", "Call it a day", "Piece of work", "Long shot",
        "Big picture", "Last straw", "Wild card", "Green thumb", "Cold feet",
        "Gut feeling", "No brainer", "Train of thought", "Food for thought",
        "Penny for your thoughts", "Bottom line", "Fine print",
        "Red flag", "Green light", "Gray area", "Golden rule",
        "Magic touch", "Sixth sense", "Sweet spot", "Comfort zone",
        "Done deal", "No dice", "In the bag", "Bag of tricks",
        // Work & Hustle
        "Heavy hitter", "Big shot", "Glass ceiling", "Rat race", "Fast track",
        "Paper trail", "Think outside the box", "Back to the drawing board",
        "Get the ball rolling", "Low hanging fruit", "Burning the candle at both ends",
        "Cut corners", "Go the extra mile", "Learn the ropes", "Up to speed",
        // Time
        "Around the clock", "Against the clock", "Beat the clock", "Crunch time",
        "Prime time", "Time flies", "In the nick of time",
        "Once in a blue moon", "At the drop of a hat", "Better late than never",
        "Burning the midnight oil", "Rise and shine", "Ship has sailed",
        "Time is money", "Back to square one",
        // Emotions & States
        "Over the moon", "On top of the world", "Walking on air", "Head over heels",
        "Butterflies in your stomach", "Heart of gold", "Broken heart", "Change of heart",
        "Thick skinned", "Gets under your skin", "Skin deep",
        "Keep it together", "Get a grip", "Hang in there", "Losing your marbles",
        "Scared to death", "Bored to tears", "Tickled pink", "Green with envy",
        // Actions & Movement
        "Hit the ground running", "Jump the gun", "Bite the bullet", "Dodge a bullet",
        "Cross the line", "Draw the line", "Read between the lines",
        "Push the envelope", "Push your luck", "Pull some strings",
        "Throw in the towel", "Throw shade", "Throw caution to the wind",
        "Kick the bucket", "Kick it up a notch", "Go with the flow",
        "Roll with the punches", "Shake things up", "Stir the pot",
        "Rock the boat", "Blow off steam", "Cut to the chase",
        // Relationships & People
        "Tied the knot", "Pop the question", "Match made in heaven", "Two peas in a pod",
        "Better half", "Partner in crime", "Thick as thieves", "Birds of a feather",
        "Joined at the hip", "Heart to heart", "Eye to eye", "Neck and neck",
        "Sparks fly", "Love at first sight", "Life of the party",
        "Old soul", "Trouble maker", "Apple of my eye", "Takes one to know one"
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

    routing {
        staticResources("/", "static") {
            default("index.html")
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
                val currentWord = roomWords.computeIfAbsent(roomId) { gameWords.random() }
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
                                // Start a new round - reset timer and score, get new word
                                state.gameStartTime = System.currentTimeMillis()
                                roomScores[roomId] = 0

                                // Get a new word
                                val newWord = gameWords.random()
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

                                        val newWord = gameWords.random()
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

                                val newWord = gameWords.random()
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

                                val newWord = gameWords.random()
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
                                val newWord = gameWords.random()
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