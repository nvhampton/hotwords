package com.example

import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val gameStartTime: Long? = null
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

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }

    // Shared game state
    val rooms = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()
    val roomScores = ConcurrentHashMap<String, Int>()
    val roomWords = ConcurrentHashMap<String, String>()  // Per-room active word
    val roomStates = ConcurrentHashMap<String, RoomState>()  // Per-room player state
    val sessionToPlayerId = ConcurrentHashMap<DefaultWebSocketServerSession, String>()  // Session to player ID mapping

    // TTL cleanup coroutine - removes inactive players every 5 seconds
    val cleanupJob = CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(5000)
            val now = System.currentTimeMillis()
            val ttlMs = 15000L  // 15 seconds TTL

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
        "Sunday driver", "Backseat driver", "Joy ride", "Happy hour", "Beauty sleep",
        "Power nap", "All nighter", "Gym rat", "Beach bum", "Mall rat",
        "Bookworm", "Movie buff", "Foodie heaven", "Coffee addict", "Retail therapy",
        // Food & Drink Idioms
        "Piece of cake", "Spill the beans", "Hot potato", "Cool beans", "Bad apple",
        "Bread winner", "Smart cookie", "Tough cookie", "Big cheese", "Top banana",
        "Sour grapes", "Apple pie", "Easy as pie", "Cream of the crop", "Best thing since sliced bread",
        "Butter fingers", "Egg head", "Small potatoes", "Hot dog",
        "In a pickle", "Full plate", "Food coma", "Comfort food", "Brain freeze",
        // Animal Expressions
        "Cat nap", "Dog days", "Fish out of water", "Wild goose chase", "Dark horse",
        "Eager beaver", "Busy bee", "Social butterfly", "Party animal", "Loan shark",
        "Copy cat", "Fat cat", "Scaredy cat", "Cool cat", "Cat got your tongue",
        "Puppy love", "Top dog", "Dog tired", "Let sleeping dogs lie", "Underdog",
        "Monkey business", "Monkey see monkey do", "Horse around", "Hold your horses", "One trick pony",
        "Bull in a china shop", "Cash cow", "Holy cow", "Pig out", "Guinea pig",
        "Chicken out", "Sitting duck", "Lame duck", "Lucky duck", "Cold turkey",
        "Wise owl", "Free bird", "Bird brain",
        // Weather & Nature
        "Under the weather", "Rain check", "Storm brewing", "Cloud nine", "Lightning fast",
        "Sunny disposition", "Right as rain", "Come rain or shine", "Steal thunder", "Brainstorm",
        "Perfect storm", "Calm before the storm", "Weather the storm", "Head in the clouds", "On cloud nine",
        "Ray of sunshine", "Walking on sunshine", "Chasing rainbows", "Silver lining", "Golden hour",
        // Sports & Games
        "Curveball", "Home run", "Slam dunk", "Touchdown", "Hole in one",
        "Drop the ball", "On the ball", "Play ball", "Ball park", "Foul play",
        "Game on", "Game over", "Game face", "Fair game", "Blame game",
        "Level up", "Boss level", "High score", "Side quest", "Easter egg",
        "Full court press", "Hail Mary", "Moving the goalposts", "Par for the course", "Behind the eight ball",
        // Pop Culture & Modern
        "Viral video", "Going viral", "Mic drop", "Plot twist", "Cliffhanger",
        "Binge worthy", "Game changer", "Power move", "Big brain", "Main character",
        "Hot take", "Humble brag", "Low key", "High key", "No cap",
        "Side hustle", "Glow up", "Vibe check",
        "Cancel culture", "Doom scrolling", "Touch grass", "Living rent free", "Caught in 4K",
        "Send it", "Slay", "Iconic", "Legendary",
        "Reality check", "Plot armor", "Character arc", "Origin story", "Redemption arc",
        // Common Expressions
        "Break a leg", "Hit the road", "Call it a day", "Piece of work", "Long shot",
        "Big picture", "Last straw", "Wild card", "Green thumb", "Cold feet",
        "Gut feeling", "Second guess", "No brainer", "Think tank", "Train of thought",
        "Food for thought", "Penny for your thoughts", "Two cents", "Bottom line", "Fine print",
        "Red flag", "Green light", "Gray area", "Black and white", "Golden rule",
        "Silver bullet", "Magic touch", "Midas touch", "Sixth sense", "Sweet spot",
        "Comfort zone", "Safe bet", "Sure thing", "Done deal", "No dice",
        "Mixed bag", "Grab bag", "In the bag", "Bag of tricks",
        // Work & Business
        "Team player", "Go getter", "Self starter", "Heavy hitter", "Big shot",
        "Corner office", "Glass ceiling", "Rat race", "Fast track", "Inside track",
        "Paper trail", "Paper pusher", "Number cruncher", "Bean counter", "Pencil pusher",
        "Think outside the box", "Back to the drawing board", "Get the ball rolling", "Touch base", "Circle back",
        "Deep dive", "Low hanging fruit", "Move the needle", "Boil the ocean", "Drink the Kool Aid",
        // Time Expressions
        "Around the clock", "Against the clock", "Beat the clock", "Kill time", "Crunch time",
        "Prime time", "Big time", "About time", "High time", "Time flies",
        "In the nick of time", "Ahead of time", "Behind the times", "Once in a blue moon", "At the drop of a hat",
        "Day in day out", "Day and night", "Call it a night", "Burning the midnight oil", "Rise and shine",
        // Emotions & States
        "Over the moon", "On top of the world", "Walking on air", "Head over heels", "Butterflies in stomach",
        "Heart of gold", "Cold hearted", "Broken heart", "Change of heart", "Heavy heart",
        "Thick skinned", "Thin skinned", "Get under skin", "Jump out of skin", "Skin deep",
        "Keep cool", "Play it cool", "Cool headed", "Hot headed", "Level headed",
        "Losing it", "Keep it together", "Fall apart", "Get a grip", "Hang in there",
        // Actions & Movement
        "Hit the ground running", "Jump the gun", "Pull the trigger", "Bite the bullet", "Dodge a bullet",
        "Cross the line", "Draw the line", "Walk the line", "Read between the lines", "Drop a line",
        "Push the envelope", "Push your luck", "Push comes to shove", "Pull strings", "Pull punches",
        "Throw in the towel", "Throw shade", "Throw a curve", "Throw caution to the wind", "Throw for a loop",
        "Kick the bucket", "Kick back", "Kick it up a notch", "Kick start", "Kick the habit",
        // Relationships
        "Tied the knot", "Pop the question", "Match made in heaven", "Two peas in a pod", "Better half",
        "Partner in crime", "Thick as thieves", "Birds of a feather", "Joined at hip",
        "Heart to heart", "Eye to eye", "Face to face", "Hand in hand", "Neck and neck",
        "Old flame", "Spark fly", "Love at first sight", "Blind date", "Double date"
    )

    routing {
        staticResources("/", "static") {
            default("index.html")
        }

        webSocket("/game/{roomId}") {
            val roomId = call.parameters["roomId"] ?: "lobby"
            val room = rooms.computeIfAbsent(roomId) { Collections.synchronizedSet(LinkedHashSet()) }
            val state = roomStates.computeIfAbsent(roomId) { RoomState() }
            room.add(this)

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
                        val text = frame.readText()
                        val received = Json.decodeFromString<GameMessage>(text)

                        when (received.type) {
                            "SET_NAME" -> {
                                val playerId = received.playerId ?: UUID.randomUUID().toString()
                                val playerName = received.player ?: "Anonymous"

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

                            "HEARTBEAT" -> {
                                val playerId = sessionToPlayerId[this]
                                if (playerId != null) {
                                    state.players.find { it.id == playerId }?.lastHeartbeat = System.currentTimeMillis()
                                }
                            }

                            "WORD_MATCH" -> {
                                // A guesser matched a word
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
                                // Describer accidentally said a forbidden word - reveal it as penalty
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
                                // Describer said the whole phrase - fail and skip!
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
                                // Skip = same describer, new phrase (they take the penalty!)
                                // Does NOT rotate - like local mode hot potato
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