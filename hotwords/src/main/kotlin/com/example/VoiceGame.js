import React, { useEffect, useRef, useState } from 'react';

const VoiceGame = ({ roomId }) => {
    const [currentWord, setCurrentWord] = useState("Waiting...");
    const [status, setStatus] = useState("Initializing...");
    const socket = useRef(null);
    const recognition = useRef(null);

    useEffect(() => {
        // 1. Setup WebSocket
        socket.current = new WebSocket(`ws://localhost:8080/game/${roomId}`);

        socket.current.onmessage = (event) => {
            const data = JSON.parse(event.data);
            if (data.type === "NEW_WORD") {
                setCurrentWord(data.word);
                startListening(data.word);
            } else if (data.type === "ROUND_WON") {
                setStatus(`${data.player} won the round!`);
            }
        };

        // 2. Setup Speech Recognition
        const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
        recognition.current = new SpeechRecognition();
        recognition.current.continuous = true;
        recognition.current.interimResults = true;
        recognition.current.lang = 'en-US';

        return () => {
            socket.current.close();
            recognition.current.stop();
        };
    }, [roomId]);

    const startListening = (targetWord) => {
        recognition.current.onresult = (event) => {
            const transcript = Array.from(event.results)
                .map(result => result[0])
                .map(result => result.transcript)
                .join('')
                .toLowerCase();

            if (transcript.includes(targetWord.toLowerCase())) {
                socket.current.send(JSON.stringify({ type: "CLAIM_VICTORY", word: targetWord }));
                recognition.current.stop(); // Stop listening once they win
            }
        };
        recognition.current.start();
        setStatus("Listening...");
    };

    return (
        <div style={{ textAlign: 'center', marginTop: '50px', fontFamily: 'sans-serif' }}>
            <h1>Say the word:</h1>
            <div style={{ fontSize: '4rem', fontWeight: 'bold', color: '#2c3e50' }}>
                {currentWord}
            </div>
            <p>{status}</p>
        </div>
    );
};