package com.busywhale.busybot.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;

import java.nio.charset.StandardCharsets;

public class WebSocketMessageConverter extends AbstractMessageConverter {
    @Override
    protected boolean supports(Class<?> clazz) {
        return WebSocketMessage.class == clazz;
    }

    @Override
    protected Object convertFromInternal(Message<?> message, Class<?> targetClass, Object conversionHint) {
        Object payload = message.getPayload();
        return new WebSocketMessage(
                message.getHeaders().get("stompCommand", StompCommand.class),
                new String((byte[]) payload, StandardCharsets.UTF_8)
        );
    }
}
