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
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.time.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// Weekly reset: Monday 3:00 AM Pacific Time
// Returns the epoch millis of the most recent Monday 3am PT boundary
fun weeklyResetTimestamp(): Long {
    val pacific = ZoneId.of("America/Los_Angeles")
    val now = ZonedDateTime.now(pacific)
    // Find the most recent Monday
    var reset = now.with(DayOfWeek.MONDAY).withHour(3).withMinute(0).withSecond(0).withNano(0)
    // If we haven't passed this Monday's 3am yet, go back to last Monday
    if (reset.isAfter(now)) reset = reset.minusWeeks(1)
    return reset.toInstant().toEpochMilli()
}

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
    val theme: String? = null,
    val playerOrder: List<String>? = null,
    val hostPlayerId: String? = null,
    val phrases: List<String>? = null,
    val category: String? = null,
    val micEnabled: Boolean? = null
)

@Serializable
data class PlayerInfo(
    val id: String,
    val name: String,
    val ready: Boolean = false,
    val micEnabled: Boolean = false
)

data class Player(
    val id: String,
    val name: String,
    var lastHeartbeat: Long = System.currentTimeMillis(),
    val session: DefaultWebSocketServerSession,
    var ready: Boolean = false,
    var micEnabled: Boolean = false
)

data class RoomState(
    val players: MutableList<Player> = mutableListOf(),
    val pendingPlayers: MutableList<Player> = mutableListOf(),  // Joined mid-game, waiting for next round
    var currentHotPlayerIndex: Int = 0,
    var currentWord: String = "",
    var revealedWords: MutableList<Boolean> = mutableListOf(),
    var gameStartTime: Long? = null,  // When the game timer started (null = not started)
    var hostPlayerId: String? = null,  // The player who controls phrase selection
    var roundDuration: Int = 30  // Seconds for this round (30 + 5*(players-2))
)

@Serializable
data class CategoryEntry(
    val name: String,
    val phrases: List<String>
)

@Serializable
data class CategoriesResponse(
    val categories: List<CategoryEntry>
)

@Serializable
data class RoundEntry(
    val id: String,
    val score: Int,
    val players: List<String>,
    val mode: String,
    val roomId: String? = null,
    val sessionHash: String,
    val timestamp: Long,
    val category: String = "classic"
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
    val phrases: List<PhraseEvent> = emptyList(),
    val category: String = "classic"
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
    val roomCustomPhrases = ConcurrentHashMap<String, List<String>>()  // Per-room custom phrases from host
    val roomCategories = ConcurrentHashMap<String, String>()  // Per-room active category name
    val sessionToPlayerId = ConcurrentHashMap<DefaultWebSocketServerSession, String>()  // Session to player ID mapping

    // Categories of the week: category name -> (timestamp, cached phrases) — resets Monday 3am PT
    data class CachedCategory(val timestamp: Long, val phrases: List<String>)
    val categoriesOfTheDay = ConcurrentHashMap<String, CachedCategory>()

    // Rate limiting for POST /api/scores: IP -> list of timestamps
    val scoreRateLimit = ConcurrentHashMap<String, MutableList<Long>>()

    // Track when rooms became empty for cleanup
    val roomEmptySince = ConcurrentHashMap<String, Long>()

    // Debounce CLAIM_VICTORY: room -> last claim timestamp
    val roomLastClaimTime = ConcurrentHashMap<String, Long>()

    // Per-connection message rate limiting: session -> list of timestamps
    val wsMessageTimestamps = ConcurrentHashMap<DefaultWebSocketServerSession, MutableList<Long>>()

    // Promote a new host when the current host leaves. Returns the new host's ID or null.
    fun promoteNewHost(roomId: String, state: RoomState): String? {
        if (state.players.isEmpty()) {
            state.hostPlayerId = null
            return null
        }
        // Promote the first player (longest-tenured by list order)
        val newHost = state.players.first()
        state.hostPlayerId = newHost.id
        // Clear custom phrases — new host can set their own
        roomCustomPhrases.remove(roomId)
        return newHost.id
    }

    // TTL cleanup coroutine - removes inactive players every 5 seconds
    val cleanupJob = CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(5000)
            val now = System.currentTimeMillis()
            val ttlMs = 15000L  // 15 seconds TTL

            // Clean up round entries older than weekly reset (Monday 3am PT)
            val weeklyReset = weeklyResetTimestamp()
            roundEntries.entries.removeIf { it.value.timestamp < weeklyReset }

            // Cap round entries at 50000 — evict oldest if exceeded
            if (roundEntries.size > 50_000) {
                val oldest = roundEntries.values.sortedBy { it.timestamp }.take(roundEntries.size - 50_000)
                oldest.forEach { roundEntries.remove(it.id) }
            }

            roomStates.forEach { (roomId, state) ->
                // Clean up stale pending players
                val pendingToRemove = state.pendingPlayers.filter { now - it.lastHeartbeat > ttlMs }
                pendingToRemove.forEach { player ->
                    state.pendingPlayers.remove(player)
                    sessionToPlayerId.remove(player.session)
                }

                val playersToRemove = state.players.filter { now - it.lastHeartbeat > ttlMs }
                if (playersToRemove.isNotEmpty()) {
                    val removedIds = playersToRemove.map { it.id }.toSet()
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

                    // Promote new host if current host was removed
                    if (state.hostPlayerId in removedIds) {
                        promoteNewHost(roomId, state)
                    }

                    // Broadcast updated player list
                    val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                    val playerListMsg = GameMessage(
                        type = "PLAYER_LIST",
                        players = playerInfoList,
                        hotPlayerIndex = state.currentHotPlayerIndex,
                        hostPlayerId = state.hostPlayerId
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

                // Server-side round expiry: end stale games when timer has elapsed
                // or all active players have left, so pending players aren't stuck forever
                if (state.gameStartTime != null) {
                    val elapsed = (now - state.gameStartTime!!) / 1000
                    val expired = elapsed > state.roundDuration + 10 // 10s grace period
                    val noActivePlayers = state.players.isEmpty()

                    if (expired || noActivePlayers) {
                        state.gameStartTime = null
                        roomScores[roomId] = 0

                        // Fold pending players into active roster
                        state.pendingPlayers.forEach { pending ->
                            if (state.players.none { it.id == pending.id }) {
                                state.players.add(pending)
                            }
                        }
                        state.pendingPlayers.clear()

                        // Assign host if needed
                        if (state.players.isNotEmpty() && (state.hostPlayerId == null || state.players.none { it.id == state.hostPlayerId })) {
                            state.hostPlayerId = state.players.first().id
                        }

                        state.players.forEach { it.ready = false }
                        state.currentHotPlayerIndex = 0

                        // Reset revealed words for existing phrase
                        state.revealedWords = (state.currentWord ?: "").split(" ").map { false }.toMutableList()

                        // Broadcast round reset to all connected sessions
                        // A fresh word will be picked when all players ready up and game starts
                        val newRoundMsg = GameMessage(type = "NEW_ROUND")
                        val playerInfoList2 = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                        val playerListMsg2 = GameMessage(
                            type = "PLAYER_LIST",
                            players = playerInfoList2,
                            hotPlayerIndex = state.currentHotPlayerIndex,
                            hostPlayerId = state.hostPlayerId
                        )
                        rooms[roomId]?.forEach { session ->
                            try {
                                CoroutineScope(Dispatchers.IO).launch {
                                    session.sendSerialized(newRoundMsg)
                                    session.sendSerialized(playerListMsg2)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Track empty rooms for cleanup
                val roomSessions = rooms[roomId]
                if (state.players.isEmpty() && state.pendingPlayers.isEmpty() && (roomSessions == null || roomSessions.isEmpty())) {
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
                    roomCustomPhrases.remove(roomId)
                    roomCategories.remove(roomId)
                    roomLastClaimTime.remove(roomId)
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
    // Kid-friendly phrases saved in phrases/kid-phrases.txt
    val gameWords = listOf(
        // Activities & Lifestyle
        "Running late", "Couch potato", "Road trip", "Window shopping",
        "People watching", "Channel surfing", "Binge watching", "Party pooper",
        "Night owl", "Early bird", "Backseat driver", "Joy ride",
        "Happy hour", "Beauty sleep", "Power nap", "All nighter",
        "Gym rat", "Beach bum", "Movie buff", "Sunday scaries",
        // Food & Drink
        "Piece of cake", "Spill the beans", "Hot potato", "Cool beans",
        "Bad apple", "Bread winner", "Smart cookie", "Tough cookie",
        "Big cheese", "Top banana", "Sour grapes", "Easy as pie",
        "Cream of the crop", "Best thing since sliced bread", "Butter fingers",
        "Egg on your face", "Small potatoes", "In a pickle", "Full plate",
        "Food coma", "Brain freeze", "Bite off more than you can chew",
        // Animals
        "Cat nap", "Dog days", "Fish out of water", "Wild goose chase",
        "Dark horse", "Eager beaver", "Busy bee", "Social butterfly",
        "Party animal", "Loan shark", "Scaredy cat", "Cat got your tongue",
        "Puppy love", "Top dog", "Let sleeping dogs lie", "Monkey business",
        "Monkey see monkey do", "Hold your horses", "One trick pony",
        "Bull in a china shop", "Cash cow", "Holy cow", "Pig out",
        "Guinea pig", "Chicken out", "Sitting duck", "Cold turkey",
        "Free as a bird", "Bird brain", "When pigs fly",
        "Straight from the horse's mouth", "Elephant in the room",
        // Weather & Nature
        "Under the weather", "Rain check", "Storm is brewing", "Cloud nine",
        "Lightning fast", "Right as rain", "Come rain or shine",
        "Steal your thunder", "Perfect storm", "Calm before the storm",
        "Head in the clouds", "Ray of sunshine", "Walking on sunshine",
        "Chasing rainbows", "Silver lining", "Tip of the iceberg",
        "Break the ice", "Snowball effect",
        // Sports & Competition
        "Home run", "Slam dunk", "Hole in one", "Drop the ball",
        "On the ball", "Game face", "Fair game", "Hail Mary",
        "Moving the goalposts", "Par for the course", "Behind the eight ball",
        "Knock it out of the park", "Down to the wire", "Jump through hoops",
        "Raise the bar", "Threw a curveball", "Photo finish",
        "Front runner", "Goal line stand",
        // Pop Culture & Modern
        "Mic drop", "Plot twist", "Game changer", "Power move",
        "Main character energy", "Hot take", "Living rent free",
        "Caught in 4K", "Reality check", "Plot armor", "Origin story",
        "Redemption arc", "Side quest", "Doom scrolling", "Touch grass",
        "Glow up", "Vibe check", "Read the room", "Left on read",
        "Hits different", "Built different", "It's giving", "Say less",
        "Rent free", "Understood the assignment",
        // Famous Phrases & Sayings
        "Actions speak louder than words", "Barking up the wrong tree",
        "Burning bridges", "Caught red handed", "Cost an arm and a leg",
        "Cry over spilled milk", "Curiosity killed the cat", "Devil's advocate",
        "Every cloud has a silver lining", "Hit the nail on the head",
        "Jump on the bandwagon", "Keep your eyes peeled",
        "Kill two birds with one stone", "Let the cat out of the bag",
        "Method to the madness", "Miss the boat", "Speak of the devil",
        "Steal the spotlight", "Take it with a grain of salt",
        "The ball is in your court", "The best of both worlds",
        "Under the radar", "Up in the air", "Wouldn't hurt a fly",
        "You can say that again",
        // Common Expressions
        "Break a leg", "Hit the road", "Call it a day", "Piece of work",
        "Long shot", "Big picture", "Last straw", "Wild card",
        "Green thumb", "Cold feet", "Gut feeling", "No brainer",
        "Train of thought", "Food for thought", "Penny for your thoughts",
        "Bottom line", "Fine print", "Red flag", "Green light",
        "Gray area", "Golden rule", "Magic touch", "Sixth sense",
        "Sweet spot", "Comfort zone", "Done deal", "No dice",
        "In the bag", "Bag of tricks",
        // Work & Hustle
        "Heavy hitter", "Big shot", "Glass ceiling", "Rat race",
        "Fast track", "Paper trail", "Think outside the box",
        "Back to the drawing board", "Get the ball rolling",
        "Low hanging fruit", "Burning the candle at both ends",
        "Cut corners", "Go the extra mile", "Learn the ropes", "Up to speed",
        // Time
        "Around the clock", "Against the clock", "Beat the clock",
        "Crunch time", "Prime time", "Time flies", "In the nick of time",
        "Once in a blue moon", "At the drop of a hat", "Better late than never",
        "Burning the midnight oil", "Rise and shine", "Ship has sailed",
        "Time is money", "Back to square one",
        // Emotions & States
        "Over the moon", "On top of the world", "Walking on air",
        "Head over heels", "Butterflies in your stomach", "Heart of gold",
        "Broken heart", "Change of heart", "Thick skinned",
        "Gets under your skin", "Skin deep", "Keep it together",
        "Get a grip", "Hang in there", "Losing your marbles",
        "Scared to death", "Bored to tears", "Tickled pink", "Green with envy",
        // Actions & Movement
        "Hit the ground running", "Jump the gun", "Bite the bullet",
        "Dodge a bullet", "Cross the line", "Draw the line",
        "Read between the lines", "Push the envelope", "Push your luck",
        "Pull some strings", "Throw in the towel", "Throw shade",
        "Throw caution to the wind", "Kick the bucket", "Kick it up a notch",
        "Go with the flow", "Roll with the punches", "Shake things up",
        "Stir the pot", "Rock the boat", "Blow off steam", "Cut to the chase",
        // Relationships & People
        "Tied the knot", "Pop the question", "Match made in heaven",
        "Two peas in a pod", "Better half", "Partner in crime",
        "Thick as thieves", "Birds of a feather", "Joined at the hip",
        "Heart to heart", "Eye to eye", "Neck and neck", "Sparks fly",
        "Love at first sight", "Life of the party", "Old soul",
        "Trouble maker", "Apple of my eye", "Takes one to know one"
    )

    // RKO Company Culture phrases
    val rkoWords = listOf(
        // Company Jargon
        "Work Wiser Wednesday", "Book of Record", "Nexus", "Aspen ERG",
        "Basecamp", "Hands on Keyboard", "Architecture Advisory Forum",
        "Operations Dashboard", "Directory", "Role Expectations",
        "Assistant", "Help Center", "Design Partner Program",
        "Engineering Show and Tell", "Feature Flag Lunch", "Fun Squad",
        "Service as Software", "Year of CX", "Feature Flag Quiz",
        "Data Share", "Intelligent Operations", "Agents",
        "Sandbox Environments", "Market Data", "Ask IO",
        "Deployments channel", "In Action", "Links hub",
        "Jitterbug", "Stash", "Feature Megaphone", "P&E Squads", "Props points",
        // Products & Features
        "Platform", "Universal Data Model", "Report Builder",
        "Client Management", "Revenue Management", "Client Portal",
        "Trade Blotter", "Portfolio Modeling", "Portfolio Rebalancer",
        "Tax Loss Harvesting", "Post-Trade Dashboard", "Executive Dashboard",
        "Portfolio Manager Dashboard", "Global Search", "Saved Views",
        "Hidden but Available", "Group By", "Operations Manager",
        "Deployment Manager", "Feature Flags", "Users and Entitlements",
        "Tasks and Notifications", "Data Persistence", "Metadata Service",
        "Data Replication Service", "Electronic Trading", "Portfolio Construction",
        "Order Generation", "Trading and Compliance", "Integration Platform",
        "Integrations Hub",
        // Values & Culture
        "Way", "Work Life Harmony", "One", "Stop the Line",
        "Technology Readiness Level", "TRL 1-9", "Speed at Scale",
        "Security-first culture", "Core Value Awards", "Props Awards",
        "Earth Day", "Reading Ambitiously", "Family Hike",
        "Brilliant jerks are not welcome", "Build, celebrate and grow together",
        "Outcomes over outputs", "Protect Market Hours", "Security Awareness Week",
        "Weekly Wednesday", "Responsible AI program",
        "Our customers are our coworkers", "Empathy and listening",
        "Keep pushin keep hustlin", "Security-first mindset", "Data Stats", "AI-ready repos",
        // Executive Catchphrases
        "RIO", "The Flywheel", "API-first", "Core system of record",
        "Land and expand", "Automated high availability",
        "Knowledge Graph", "2030 vision", "Project Octane", "Project One",
        "Roadshow", "Kickoff", "Everything Base Camp", "AI Platform",
        "Speed, quality, resilience", "We're all in",
        // Engineering Lingo
        "Accounting Processing Engine", "Metadata Delivery Service",
        "UDM Schema", "UDM Publishing Service", "One API Gateway",
        "Feature Flag Service", "Release Candidate", "Prod Hotfix",
        "Zero Downtime", "Ring 0", "Ring 1", "Data Orchestration Engine",
        "Reporting Engine v2", "Portfolio Access Control", "Reporting Experience",
        "Service Libs Kotlin", "Data Core", "Event-Driven Backbone",
        "Elastic by Design", "Observability Everywhere", "Data Source Abstraction",
        "Kafka Topic Partitions", "Outbox Table", "Pinecone Persistence",
        "Data Warehouse", "Architecture Decision Record", "Relational Pinecone",
        "Browser Extension", "DevTools Script",
        // Customer & Industry
        "AWS Cloud Practitioner", "Amazon Web Services", "Snowflake Data Warehouse",
        "Salesforce CRM", "Microsoft Outlook", "Microsoft Teams",
        "Workato integration", "FactSet analytics", "Bloomberg Terminal",
        "Bloomberg EMSX", "DTCC Omgeo CTM", "Gresham Technologies",
        "ICE data feeds", "LSEG market data", "MSCI index data",
        "GIPS verification", "ISS proxy voting", "ClearPar loans",
        "Allvue", "Advent APX", "Advent ACD", "Moxy OMS",
        "Charles River OMS", "SimCorp Dimension", "FundGuard", "Addepar",
        "Unified data model", "Cloud-native SaaS", "Ex-ante risk", "Ex-post risk",
        "Composite performance", "UMA model delivery", "Client reporting",
        "Performance attribution", "Zero-copy data sharing",
        "Data warehouse connectivity", "Services-as-software",
        "Agentic automation", "Proactive intelligence",
        "Real-time books and records", "Custodian authorization", "Custodian connections",
        // Meetings & Rituals
        "Demo Day", "Show & Tell", "Engineering Show & Tell",
        "Tech All Hands", "Company All-Hands", "Product Council",
        "Operating Reviews", "Manager Community Connect",
        "Platform PM Team Meeting", "IO Leads Sync", "IO Staff Meeting",
        "ProDev", "Base Camp", "Nexus Hackathon",
        "Simulation Exercise", "Day of Giving", "Emoji-nal Support",
        "Hot Ones: Security Edition", "UI Office Hours",
        "Technology Show & Tell", "OKTOBERFEST-O-WEEN",
        "Kick-off costume party", "#product-and-technology-org",
        "#security-awareness", "#rl-deployments", "#ask-io", "#ask-ui", "#ask-data-persistence",
        // Inside Jokes
        "party-bounce", "blob_excited", "heart emoji", "feature-flag",
        "io emoji", "United States of", "Bricked Environments",
        "Swag Czar", "DC Swag Czar", "Taxman jam", "Demo Day season",
        "Mobile app jokes", "Gone Phishing", "Gandalf challenge",
        "NPC Capital", "Pop Poppin Off", "Concierge", "Error Terrors",
        "Swipe Right Richies", "Workato is Working Wonders", "Swag drops",
        "Demo Day Perfection", "Keyboard Navigation Kickoff",
        "Props & Prize Winners"
    )

    // Cache for topic phrase packs loaded from classpath
    val topicPhrasesCache = ConcurrentHashMap<String, List<String>>()

    fun loadTopicPhrases(topicId: String): List<String>? {
        return topicPhrasesCache.getOrPut(topicId) {
            try {
                val json = Application::class.java.getResource("/static/phrases/$topicId.json")?.readText()
                    ?: return null
                Json.decodeFromString<List<String>>(json)
            } catch (e: Exception) {
                return null
            }
        }
    }

    fun loadRkoTopicPhrases(topicId: String): List<String>? {
        val cacheKey = "rko:$topicId"
        return topicPhrasesCache.getOrPut(cacheKey) {
            try {
                val json = Application::class.java.getResource("/static/phrases/rko/$topicId.json")?.readText()
                    ?: return null
                Json.decodeFromString<List<String>>(json)
            } catch (e: Exception) {
                return null
            }
        }
    }

    fun getWordForRoom(roomId: String): String {
        // Custom phrases from host take priority
        val custom = roomCustomPhrases[roomId]
        println("getWordForRoom($roomId): custom=${custom?.size ?: 0}, theme=${roomThemes[roomId]}")
        if (custom != null && custom.isNotEmpty()) return custom.random()

        val theme = roomThemes[roomId]
        if (theme == "rko") return rkoWords.random()
        if (theme != null && theme.startsWith("rko:")) {
            val topicId = theme.removePrefix("rko:")
            val phrases = loadRkoTopicPhrases(topicId)
            if (phrases != null && phrases.isNotEmpty()) return phrases.random()
            return rkoWords.random() // fallback to all RKO
        }
        if (theme != null && theme.startsWith("topic:")) {
            val topicId = theme.removePrefix("topic:")
            val phrases = loadTopicPhrases(topicId)
            if (phrases != null && phrases.isNotEmpty()) return phrases.random()
        }
        return gameWords.random()
    }


    val appVersion = Application::class.java.`package`?.implementationVersion ?: "dev"

    routing {
        staticResources("/", "static") {
            default("index.html")
        }

        get("/health") {
            call.respond(mapOf("status" to "ok", "version" to appVersion))
        }

        // Categories of the day — returns recently played custom categories
        get("/api/categories") {
            val weeklyReset = weeklyResetTimestamp()
            // Clean up entries from before this week's reset
            categoriesOfTheDay.entries.removeIf { it.value.timestamp < weeklyReset }
            // Return sorted by most recent, include cached phrases
            val categories = categoriesOfTheDay.entries
                .sortedByDescending { it.value.timestamp }
                .take(20)
                .map { CategoryEntry(name = it.key, phrases = it.value.phrases) }
            call.respond(CategoriesResponse(categories = categories))
        }

        // Top categories by highest score this week
        get("/api/top-categories") {
            val weeklyReset = weeklyResetTimestamp()
            // Group rounds by category, find max score per category
            val categoryScores = roundEntries.values
                .filter { it.timestamp >= weeklyReset }
                .groupBy { it.category.lowercase() }
                .mapValues { (_, rounds) -> rounds.maxOf { it.score } }
                .entries
                .sortedByDescending { it.value }
                .take(2)
                .map { mapOf("category" to it.key, "score" to it.value) }
            call.respond(mapOf("top" to categoryScores))
        }

        // Add a category of the day (with cached phrases)
        post("/api/categories") {
            val body = call.receiveText()
            val request = jsonLenient.decodeFromString(JsonObject.serializer(), body)
            val rawCategory = request["category"]?.jsonPrimitive?.contentOrNull?.trim()?.take(100)
            // Enforce max 3 words, lowercase
            val category = rawCategory?.split("\\s+".toRegex())?.take(3)?.joinToString(" ")?.lowercase()
            val phrases = request["phrases"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            if (!category.isNullOrBlank() && phrases.isNotEmpty()) {
                categoriesOfTheDay[category] = CachedCategory(System.currentTimeMillis(), phrases.take(200))
            } else if (!category.isNullOrBlank()) {
                // Update timestamp but keep existing phrases if any
                val existing = categoriesOfTheDay[category]
                if (existing != null) {
                    categoriesOfTheDay[category] = existing.copy(timestamp = System.currentTimeMillis())
                }
            }
            call.respond(mapOf("ok" to true))
        }

        // Delete a category of the day
        delete("/api/categories/{name}") {
            val name = call.parameters["name"]?.lowercase()?.trim()
            if (name.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing category name"))
                return@delete
            }
            val removed = categoriesOfTheDay.remove(name) != null
            call.respond(mapOf("ok" to true, "removed" to removed))
        }

        post("/api/scores") {
            try {
                val remoteIp = call.request.header("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
                    ?: call.request.local.remoteHost
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
                    timestamp = now,
                    category = submission.category.trim().split("\\s+".toRegex()).take(3).joinToString(" ").lowercase().ifEmpty { "classic" }
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

                // Compute percentile among rounds since weekly reset (same mode/room)
                val weeklyReset = weeklyResetTimestamp()
                val comparable = roundEntries.values.filter { r ->
                    r.mode == submission.mode &&
                    r.timestamp >= weeklyReset &&
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
            val weeklyReset = weeklyResetTimestamp()

            val filtered = roundEntries.values
                .filter { it.mode == mode }
                .filter { it.timestamp >= weeklyReset }
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

        // Generate similar phrases using Claude API
        val claudeApiKey = System.getenv("ANTHROPIC_API_KEY") ?: ""
        application.log.info("Claude API key configured: ${claudeApiKey.isNotBlank()} (length: ${claudeApiKey.length})")
        val claudeClient = HttpClient(CIO) {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        post("/api/generate-phrases") {
            if (claudeApiKey.isBlank()) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "API key not configured"))
                return@post
            }

            val body = call.receiveText()
            val request = jsonLenient.decodeFromString(JsonObject.serializer(), body)
            val phrases = request["phrases"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val category = request["category"]?.jsonPrimitive?.contentOrNull
            val count = request["count"]?.jsonPrimitive?.intOrNull ?: 50

            if (phrases.isEmpty() && category.isNullOrBlank()) {
                call.respond(mapOf("phrases" to emptyList<String>()))
                return@post
            }

            val safeCount = count.coerceIn(1, 100)

            val prompt = if (!category.isNullOrBlank()) {
                // Category mode: generate phrases for a category
                val safeCategory = category.take(100).replace(Regex("[\"\\\\]"), "")
                """Generate exactly $safeCount short phrases or expressions related to the category: "$safeCategory"

Rules:
- Each phrase should be 1-5 words
- Phrases should be fun, recognizable things within this category that would work well in a word-guessing party game
- Vary the difficulty — mix easy and tricky ones
- NO acronyms or abbreviations — write them out as full words
- NO hyphens, apostrophes, or special punctuation — only plain letters and spaces (e.g. "product market fit" not "product-market fit")
- All words must be correctly spelled — no slang spellings or dropped letters (e.g. "believing" not "believin")
- Return ONLY a JSON array of strings, no other text
- Example format: ["phrase one", "phrase two", "phrase three"]"""
            } else {
                // Example mode: generate similar phrases
                val exampleList = phrases.take(20).joinToString(", ") { "\"$it\"" }
                """Generate exactly $safeCount short phrases or expressions that are thematically similar to these examples: $exampleList

Rules:
- Each phrase should be 1-5 words
- Match the style, theme, and difficulty of the examples
- Don't repeat any of the example phrases
- NO acronyms or abbreviations — write them out as full words
- NO hyphens, apostrophes, or special punctuation — only plain letters and spaces (e.g. "product market fit" not "product-market fit")
- All words must be correctly spelled — no slang spellings or dropped letters (e.g. "believing" not "believin")
- Return ONLY a JSON array of strings, no other text
- Example format: ["phrase one", "phrase two", "phrase three"]"""
            }

            try {
                val response = claudeClient.post("https://api.anthropic.com/v1/messages") {
                    header("x-api-key", claudeApiKey)
                    header("anthropic-version", "2023-06-01")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("model", "claude-haiku-4-5-20251001")
                        put("max_tokens", 1024)
                        putJsonArray("messages") {
                            addJsonObject {
                                put("role", "user")
                                put("content", prompt)
                            }
                        }
                    }.toString())
                }

                val responseBody = response.bodyAsText()
                application.log.info("Claude API response status: ${response.status}, body length: ${responseBody.length}")
                if (response.status.value != 200) {
                    application.log.error("Claude API error response: $responseBody")
                    call.respond(mapOf("phrases" to emptyList<String>()))
                    return@post
                }
                val responseJson = jsonLenient.decodeFromString(JsonObject.serializer(), responseBody)

                // Extract text from Claude's response
                val content = responseJson["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "[]"

                // Parse the JSON array from Claude's response (may have markdown wrapping)
                val cleanJson = content.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
                val generatedPhrases = jsonLenient.decodeFromString(JsonArray.serializer(), cleanJson)
                    .map { it.jsonPrimitive.content.replace(Regex("[^a-zA-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .take(safeCount)

                call.respond(mapOf("phrases" to generatedPhrases))
            } catch (e: Exception) {
                application.log.error("Claude API error: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Generation failed")))
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

                                // Set room theme if provided (only host or first player can set)
                                val theme = received.theme
                                val isHost = state.hostPlayerId == null || state.hostPlayerId == playerId
                                if (theme != null && isHost) {
                                    roomThemes[roomId] = theme
                                    // Re-pick word if game hasn't started yet
                                    if (state.gameStartTime == null) {
                                        val themedWord = getWordForRoom(roomId)
                                        roomWords[roomId] = themedWord
                                        state.currentWord = themedWord
                                        state.revealedWords = themedWord.split(" ").map { false }.toMutableList()
                                    }
                                }

                                // Check if this player already exists (reconnect/rename)
                                val existingPlayer = state.players.find { it.id == playerId }
                                val existingPending = state.pendingPlayers.find { it.id == playerId }
                                val isNewPlayer = existingPlayer == null && existingPending == null
                                if (existingPlayer != null) {
                                    state.players.remove(existingPlayer)
                                    sessionToPlayerId.remove(existingPlayer.session)
                                }
                                if (existingPending != null) {
                                    state.pendingPlayers.remove(existingPending)
                                    sessionToPlayerId.remove(existingPending.session)
                                }

                                val player = Player(playerId, playerName, System.currentTimeMillis(), this)
                                sessionToPlayerId[this] = playerId

                                // Mid-game new joiners go to pending list — not added to active roster
                                // But if the timer has already expired, treat as normal join
                                val gameStillActive = state.gameStartTime != null &&
                                    (System.currentTimeMillis() - state.gameStartTime!!) / 1000 <= state.roundDuration + 10
                                if (isNewPlayer && gameStillActive) {
                                    state.pendingPlayers.add(player)

                                    // Send room category
                                    val roomCat = roomCategories[roomId]
                                    if (roomCat != null) {
                                        sendSerialized(GameMessage(type = "CATEGORY", category = roomCat))
                                    }

                                    // Tell them a game is in progress
                                    val elapsed = (System.currentTimeMillis() - state.gameStartTime!!) / 1000
                                    val remaining = maxOf(0, state.roundDuration - elapsed.toInt())
                                    sendSerialized(GameMessage(
                                        type = "GAME_IN_PROGRESS",
                                        timeRemaining = remaining,
                                        hostPlayerId = state.hostPlayerId
                                    ))
                                } else {
                                    // Normal join: add to active roster
                                    state.players.add(player)

                                    // Assign host if none exists
                                    if (state.hostPlayerId == null || state.players.none { it.id == state.hostPlayerId }) {
                                        state.hostPlayerId = playerId
                                    }

                                    // Reset all ready states only when a genuinely new player joins (not renames)
                                    if (isNewPlayer && state.gameStartTime == null) {
                                        state.players.forEach { it.ready = false }
                                    }

                                    // Broadcast player list to all active players
                                    val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                                    val playerListMsg = GameMessage(
                                        type = "PLAYER_LIST",
                                        players = playerInfoList,
                                        hotPlayerIndex = state.currentHotPlayerIndex,
                                        hostPlayerId = state.hostPlayerId
                                    )
                                    room.forEach { session ->
                                        session.sendSerialized(playerListMsg)
                                    }

                                    // Send room category
                                    val roomCat = roomCategories[roomId]
                                    if (roomCat != null) {
                                        sendSerialized(GameMessage(type = "CATEGORY", category = roomCat))
                                    }

                                    // Reconnecting player mid-game: send current word + timer sync
                                    if (state.gameStartTime != null && state.players.size >= 2) {
                                        val elapsed = (System.currentTimeMillis() - state.gameStartTime!!) / 1000
                                        val remaining = maxOf(0, state.roundDuration - elapsed.toInt())
                                        sendSerialized(GameMessage(
                                            type = "NEW_WORD",
                                            word = state.currentWord,
                                            revealed = state.revealedWords
                                        ))
                                        sendSerialized(GameMessage(
                                            type = "TIMER_SYNC",
                                            timeRemaining = remaining
                                        ))
                                    } else {
                                        // Game not in progress — send current word/progress
                                        sendSerialized(GameMessage(
                                            type = "NEW_WORD",
                                            word = state.currentWord,
                                            revealed = state.revealedWords
                                        ))
                                    }
                                }
                            }

                            "READY" -> {
                                val playerId = sessionToPlayerId[this] ?: continue
                                val player = state.players.find { it.id == playerId } ?: continue
                                // Toggle ready state
                                player.ready = !player.ready

                                // Broadcast updated player list
                                val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                                val playerListMsg = GameMessage(
                                    type = "PLAYER_LIST",
                                    players = playerInfoList,
                                    hotPlayerIndex = state.currentHotPlayerIndex,
                                    hostPlayerId = state.hostPlayerId
                                )
                                room.forEach { session ->
                                    session.sendSerialized(playerListMsg)
                                }

                                // Check if all players ready and >= 2: auto-start after 1s delay
                                if (state.players.size >= 2 && state.players.all { it.ready }) {
                                    launch {
                                        delay(1000)
                                        // Re-check after delay (player may have unreadied or left)
                                        if (state.players.size >= 2 && state.players.all { it.ready }) {
                                            state.roundDuration = 30 + 5 * maxOf(0, state.players.size - 2)
                                            state.gameStartTime = System.currentTimeMillis()
                                            state.players.forEach { it.ready = false }

                                            // Pick a fresh word so all players start with the same phrase
                                            val startWord = getWordForRoom(roomId)
                                            roomWords[roomId] = startWord
                                            state.currentWord = startWord
                                            state.revealedWords = startWord.split(" ").map { false }.toMutableList()

                                            val startWordMsg = GameMessage(
                                                type = "NEW_WORD",
                                                word = startWord,
                                                revealed = state.revealedWords.toList()
                                            )
                                            room.forEach { session ->
                                                session.sendSerialized(GameMessage(
                                                    type = "GAME_STARTED",
                                                    timeRemaining = state.roundDuration
                                                ))
                                                session.sendSerialized(startWordMsg)
                                            }
                                        }
                                    }
                                }
                            }

                            "SET_PHRASES" -> {
                                // Host uploads custom phrase list for the room
                                val senderPlayerId = sessionToPlayerId[this] ?: continue
                                println("SET_PHRASES from $senderPlayerId (host=${state.hostPlayerId}), ${received.phrases?.size ?: 0} phrases, category=${received.category}")
                                if (senderPlayerId != state.hostPlayerId) continue  // Only host can set phrases
                                val phrases = received.phrases
                                // Store category if provided
                                val cat = received.category?.trim()?.take(100)
                                if (!cat.isNullOrBlank()) {
                                    roomCategories[roomId] = cat
                                } else if (phrases == null || phrases.isEmpty()) {
                                    roomCategories.remove(roomId)
                                }
                                // Broadcast category to all players
                                val categoryMsg = GameMessage(
                                    type = "CATEGORY",
                                    category = roomCategories[roomId]
                                )
                                room.forEach { session -> session.sendSerialized(categoryMsg) }

                                if (phrases != null && phrases.isNotEmpty()) {
                                    // Cap at 200 phrases, sanitize
                                    val sanitized = phrases.take(200).map { it.trim() }.filter { it.isNotEmpty() }
                                    if (sanitized.isNotEmpty()) {
                                        roomCustomPhrases[roomId] = sanitized
                                        // Re-pick word if game hasn't started yet and broadcast to all
                                        if (state.gameStartTime == null) {
                                            val newWord = getWordForRoom(roomId)
                                            roomWords[roomId] = newWord
                                            state.currentWord = newWord
                                            state.revealedWords = newWord.split(" ").map { false }.toMutableList()
                                            val newWordMsg = GameMessage(
                                                type = "NEW_WORD",
                                                word = newWord,
                                                revealed = state.revealedWords.toList()
                                            )
                                            room.forEach { session -> session.sendSerialized(newWordMsg) }
                                        }
                                    }
                                } else {
                                    // Empty list clears custom phrases
                                    roomCustomPhrases.remove(roomId)
                                }
                            }

                            "NEW_ROUND" -> {
                                // Update theme if provided (only host)
                                val senderPlayerId = sessionToPlayerId[this]
                                val roundTheme = received.theme
                                if (roundTheme != null && senderPlayerId == state.hostPlayerId) {
                                    roomThemes[roomId] = roundTheme
                                }
                                // Reset state for new round
                                state.gameStartTime = null
                                roomScores[roomId] = 0

                                // Fold pending players into active roster
                                state.pendingPlayers.forEach { pending ->
                                    if (state.players.none { it.id == pending.id }) {
                                        state.players.add(pending)
                                    }
                                }
                                state.pendingPlayers.clear()

                                state.players.forEach { it.ready = false }

                                // Get a new word
                                val newWord = getWordForRoom(roomId)
                                roomWords[roomId] = newWord
                                state.currentWord = newWord
                                state.revealedWords = newWord.split(" ").map { false }.toMutableList()

                                // Broadcast NEW_ROUND (tells clients to show ready overlay)
                                val newRoundMsg = GameMessage(
                                    type = "NEW_ROUND"
                                )
                                // Send updated player list (all unready) so ready overlay shows correctly
                                val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                                val playerListMsg = GameMessage(
                                    type = "PLAYER_LIST",
                                    players = playerInfoList,
                                    hotPlayerIndex = state.currentHotPlayerIndex,
                                    hostPlayerId = state.hostPlayerId
                                )
                                val newWordMsg = GameMessage(
                                    type = "NEW_WORD",
                                    word = newWord,
                                    revealed = state.revealedWords.toList()
                                )

                                room.forEach { session ->
                                    session.sendSerialized(newRoundMsg)
                                    session.sendSerialized(playerListMsg)
                                    session.sendSerialized(newWordMsg)
                                }
                            }

                            "HEARTBEAT" -> {
                                val playerId = sessionToPlayerId[this]
                                if (playerId != null) {
                                    val ts = System.currentTimeMillis()
                                    state.players.find { it.id == playerId }?.lastHeartbeat = ts
                                    state.pendingPlayers.find { it.id == playerId }?.lastHeartbeat = ts
                                }
                            }

                            "SET_MIC" -> {
                                val playerId = sessionToPlayerId[this] ?: continue
                                val micEnabled = received.micEnabled ?: continue
                                val player = state.players.find { it.id == playerId }
                                    ?: state.pendingPlayers.find { it.id == playerId }
                                    ?: continue
                                player.micEnabled = micEnabled
                                // Broadcast updated player list
                                val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                                val playerListMsg = GameMessage(
                                    type = "PLAYER_LIST",
                                    players = playerInfoList,
                                    hotPlayerIndex = state.currentHotPlayerIndex,
                                    hostPlayerId = state.hostPlayerId
                                )
                                room.forEach { session -> session.sendSerialized(playerListMsg) }
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
                                        val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                                        val playerListMsg = GameMessage(
                                            type = "PLAYER_LIST",
                                            players = playerInfoList,
                                            hotPlayerIndex = state.currentHotPlayerIndex,
                                            hostPlayerId = state.hostPlayerId
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
                                val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                                val playerListMsg = GameMessage(
                                    type = "PLAYER_LIST",
                                    players = playerInfoList,
                                    hotPlayerIndex = state.currentHotPlayerIndex,
                                    hostPlayerId = state.hostPlayerId
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
                                // Debounce: ignore if another claim was processed within 500ms
                                val now = System.currentTimeMillis()
                                val lastClaim = roomLastClaimTime[roomId] ?: 0L
                                if (now - lastClaim < 500) continue
                                roomLastClaimTime[roomId] = now
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
                                val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                                val playerListMsg = GameMessage(
                                    type = "PLAYER_LIST",
                                    players = playerInfoList,
                                    hotPlayerIndex = state.currentHotPlayerIndex,
                                    hostPlayerId = state.hostPlayerId
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

                            "REORDER_PLAYERS" -> {
                                val newOrder = received.playerOrder ?: continue
                                // Validate: same set of player IDs
                                val currentIds = state.players.map { it.id }.toSet()
                                if (newOrder.size != state.players.size || newOrder.toSet() != currentIds) continue
                                // Remember who is hot
                                val hotPlayerId = state.players.getOrNull(state.currentHotPlayerIndex)?.id
                                // Reorder
                                val playerMap = state.players.associateBy { it.id }
                                state.players.clear()
                                newOrder.forEach { id -> playerMap[id]?.let { state.players.add(it) } }
                                // Restore hot player index
                                state.currentHotPlayerIndex = state.players.indexOfFirst { it.id == hotPlayerId }.coerceAtLeast(0)
                                // Broadcast
                                val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                                val playerListMsg = GameMessage(type = "PLAYER_LIST", players = playerInfoList, hotPlayerIndex = state.currentHotPlayerIndex, hostPlayerId = state.hostPlayerId)
                                room.forEach { session -> session.sendSerialized(playerListMsg) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Socket error: ${e.localizedMessage}")
            } finally {
                room.remove(this)
                wsMessageTimestamps.remove(this)
                // Remove player from state (active or pending)
                val playerId = sessionToPlayerId.remove(this)
                if (playerId != null) {
                    state.players.removeIf { it.id == playerId }
                    state.pendingPlayers.removeIf { it.id == playerId }
                    // Adjust hot player index if needed
                    if (state.players.isNotEmpty()) {
                        state.currentHotPlayerIndex = state.currentHotPlayerIndex % state.players.size
                    } else {
                        state.currentHotPlayerIndex = 0
                    }

                    // Promote new host if the departing player was host
                    if (state.hostPlayerId == playerId) {
                        promoteNewHost(roomId, state)
                    }

                    // Broadcast updated player list
                    val playerInfoList = state.players.map { PlayerInfo(it.id, it.name, it.ready, it.micEnabled) }
                    val playerListMsg = GameMessage(
                        type = "PLAYER_LIST",
                        players = playerInfoList,
                        hotPlayerIndex = state.currentHotPlayerIndex,
                        hostPlayerId = state.hostPlayerId
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