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
        // Actions & Activities
        "Running late", "Couch potato", "Road trip", "Window shopping", "People watching",
        "Channel surfing", "Binge watching", "Party pooper", "Night owl", "Early bird",
        // Food & Drink
        "Piece of cake", "Spill the beans", "Hot potato", "Cool beans", "Bad apple",
        "Bread winner", "Couch potato", "Smart cookie", "Tough cookie", "Big cheese",
        // Animals
        "Cat nap", "Dog days", "Fish out of water", "Wild goose chase", "Dark horse",
        "Eager beaver", "Busy bee", "Social butterfly", "Party animal", "Loan shark",
        // Weather & Nature
        "Under the weather", "Rain check", "Storm brewing", "Cloud nine", "Lightning fast",
        // Sports & Games
        "Curveball", "Home run", "Slam dunk", "Touchdown", "Hole in one",
        // Tech & Modern
        "Viral video", "Going viral", "Mic drop", "Plot twist", "Cliffhanger",
        "Binge worthy", "Game changer", "Power move", "Big brain", "Main character",
        // Classic Phrases
        "Break a leg", "Hit the road", "Call it a day", "Piece of work", "Long shot",
        "Big picture", "Last straw", "Wild card", "Green thumb", "Cold feet"
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