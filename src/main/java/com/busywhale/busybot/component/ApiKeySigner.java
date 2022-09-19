package com.busywhale.busybot.component;

import com.google.common.hash.Hashing;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Component
public class ApiKeySigner {
    private static final Logger logger = LogManager.getLogger(ApiKeySigner.class);
    private static final String HEADER_API_KEY = "bw-apikey";
    private static final String HEADER_TIMESTAMP = "bw-timestamp";
    private static final String HEADER_SIGNATURE = "bw-signature";

    @Value("${bot.api.key:}")
    private String apiKey;

    @Value("${bot.api.secret:}")
    private String apiSecret;

    @PostConstruct
    public void postConstruct() {
        if (StringUtils.isEmpty(apiKey) || StringUtils.isEmpty(apiSecret)) {
            logger.error("No API key/secret pair not defined.");
            throw new IllegalStateException("No API key/secret pair not defined");
        }
    }

    public Map<String, String> getAuthHeaders(String method, String path, String payload) {
        long now = Instant.now().getEpochSecond();
        return Map.of(
                HEADER_API_KEY, apiKey,
                HEADER_TIMESTAMP, String.valueOf(now),
                HEADER_SIGNATURE, getSignature(method, path, now, payload)
        );
    }

    private String getSignature(String method, String path, long timestamp, String payload) {
        String signingString = method + path + timestamp + StringUtils.defaultString(payload);
        byte[] bytes = Hashing.hmacSha256(apiSecret.getBytes())
                .hashString(signingString, StandardCharsets.UTF_8)
                .asBytes();
        return Base64.encodeBase64String(bytes);
    }
}
