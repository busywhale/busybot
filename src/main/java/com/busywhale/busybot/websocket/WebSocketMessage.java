package com.busywhale.busybot.websocket;

import org.springframework.messaging.simp.stomp.StompCommand;

public record WebSocketMessage(StompCommand command, String message) {
}
