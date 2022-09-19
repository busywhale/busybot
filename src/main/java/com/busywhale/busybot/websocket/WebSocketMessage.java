package com.busywhale.busybot.websocket;

import org.springframework.messaging.simp.stomp.StompCommand;

public class WebSocketMessage {
    private final StompCommand command;
    private final String message;

    public WebSocketMessage(StompCommand command, String message) {
        this.command = command;
        this.message = message;
    }

    public StompCommand getCommand() {
        return command;
    }

    public String getMessage() {
        return message;
    }
}
