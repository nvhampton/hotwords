package com.example

import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
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
    val score: Int? = null
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
            room.add(this)

            try {
                // Initialize room word if needed, or get existing
                val currentWord = roomWords.computeIfAbsent(roomId) { gameWords.random() }
                val currentScore = roomScores.getOrDefault(roomId, 0)
                sendSerialized(GameMessage(type = "SCORE_UPDATE", score = currentScore))
                sendSerialized(GameMessage(type = "NEW_WORD", word = currentWord))

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val received = Json.decodeFromString<GameMessage>(text)

                        when (received.type) {
                            "CLAIM_VICTORY" -> {
                                val newWord = gameWords.random()
                                roomWords[roomId] = newWord
                                val newScore = roomScores.merge(roomId, 1, Int::plus) ?: 1
                                val winnerMsg = GameMessage(type = "ROUND_WON", player = received.player ?: "A Player", score = newScore)
                                val newWordMsg = GameMessage(type = "NEW_WORD", word = newWord)

                                room.forEach { session ->
                                    session.sendSerialized(winnerMsg)
                                    session.sendSerialized(newWordMsg)
                                }
                            }
                            "SKIP_WORD" -> {
                                val newWord = gameWords.random()
                                roomWords[roomId] = newWord
                                val skipMsg = GameMessage(type = "WORD_SKIPPED", player = received.player)
                                val newWordMsg = GameMessage(type = "NEW_WORD", word = newWord)

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
            }
        }
    }
}