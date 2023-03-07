package com.busywhale.busybot.component;

import com.busywhale.busybot.model.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.busywhale.busybot.util.BotUtils.SETTLEMENT_METHOD_OFF_CHAIN_IMMEDIATE;

@Component
public class ApiEngine {
    private static final Logger logger = LogManager.getLogger(ApiEngine.class);

    @Value("${REST_URL_BASE}")
    private String apiUrlBase;

    @Value("${ASSET_FILTER:}")
    private String assetFilter;

    @Autowired
    private CloseableHttpClient httpClient;

    @Autowired
    private ApiKeySigner signer;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Async
    public CompletableFuture<List<Asset>> getAssets() {
        CompletableFuture<List<Asset>> cryptos = getCryptos();
        CompletableFuture<List<Asset>> fiats = getFiats();
        Set<String> assetSet = StreamSupport.stream(
                        Splitter.on(",")
                                .trimResults()
                                .omitEmptyStrings()
                                .split(assetFilter)
                                .spliterator(),
                        false
                )
                .collect(Collectors.toSet());
        return CompletableFuture.allOf(cryptos, fiats)
                .thenApply(dummy -> ListUtils.union(
                                        ListUtils.emptyIfNull(cryptos.join()),
                                        ListUtils.emptyIfNull(fiats.join())
                                )
                                .stream()
                                .filter(asset -> assetSet.isEmpty() || assetSet.contains(asset.symbol()))
                                .collect(Collectors.toList())
                );
    }

    @Async
    public CompletableFuture<Map<String, Double>> getIndexSnapshots() {
        return sendRequest(
                "GET",
                "/api/v1/public/index/snapshot",
                null,
                false,
                node -> {
                    Map<String, Double> map = new HashMap<>();
                    JsonNode indexesNode = node.path("indexes");
                    if (!indexesNode.isMissingNode()) {
                        indexesNode.fields()
                                .forEachRemaining(e -> map.put(e.getKey(), e.getValue().path("L").doubleValue()));
                    }
                    return map;
                }
        );
    }

    @Async
    public CompletableFuture<List<RfqEntry>> getRfqAds() {
        logger.info("Fetching RFQ ads...");
        return sendRequest(
                "GET",
                "/api/v1/rfq-ads",
                null,
                true,
                node -> {
                    JsonNode rfqsNode = node.path("rfqs");
                    if (rfqsNode.isMissingNode()) {
                        return Collections.emptyList();
                    }
                    ArrayNode arrayNode = (ArrayNode) rfqsNode;
                    return StreamSupport.stream(arrayNode.spliterator(), false)
                            .map(dataNode -> {
                                try {
                                    return mapper.treeToValue(dataNode, RfqEntry.class);
                                } catch (Exception ignored) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .toList();
                }
        );
    }

    @Async
    public CompletableFuture<List<RfqEntry>> getMyRfqs() {
        logger.info("Fetching my own RFQs...");
        return sendRequest(
                "GET",
                "/api/v1/rfqs",
                null,
                true,
                node -> {
                    JsonNode rfqsNode = node.path("rfqs");
                    if (rfqsNode.isMissingNode()) {
                        return Collections.emptyList();
                    }
                    ArrayNode arrayNode = (ArrayNode) rfqsNode;
                    return StreamSupport.stream(arrayNode.spliterator(), false)
                            .map(dataNode -> {
                                try {
                                    return mapper.treeToValue(dataNode, RfqEntry.class);
                                } catch (Exception ignored) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .toList();
                }
        );
    }

    @Async
    public CompletableFuture<List<PositionEntry>> getPositions() {
        logger.info("Fetching positions...");
        return sendRequest(
                "GET",
                "/api/v1/position",
                null,
                true,
                node -> {
                    JsonNode positionsNode = node.path("positions");
                    if (positionsNode.isMissingNode()) {
                        return Collections.emptyList();
                    }
                    ArrayNode arrayNode = (ArrayNode) positionsNode;
                    return StreamSupport.stream(arrayNode.spliterator(), false)
                            .map(dataNode -> {
                                try {
                                    return mapper.treeToValue(dataNode, PositionEntry.class);
                                } catch (Exception ignored) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .toList();
                }
        );
    }

    @Async
    public CompletableFuture<List<SettlementEntry>> getSettlements() {
        logger.info("Fetching settlements...");
        return sendRequest(
                "GET",
                "/api/v1/settlement",
                null,
                true,
                node -> {
                    JsonNode settlementsNode = node.path("settlements");
                    if (settlementsNode.isMissingNode()) {
                        return Collections.emptyList();
                    }
                    ArrayNode arrayNode = (ArrayNode) settlementsNode;
                    return StreamSupport.stream(arrayNode.spliterator(), false)
                            .map(dataNode -> {
                                try {
                                    return mapper.treeToValue(dataNode, SettlementEntry.class);
                                } catch (Exception ignored) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .toList();
                }
        );
    }

    @Async
    public void acceptSettlement(String settlementId) {
        logger.info("Accepting settlement {}...", settlementId);
        sendRequest(
                "POST",
                "/api/v1/settlement/" + settlementId + "/accept",
                null,
                true,
                node -> {
                    JsonNode statusNode = node.path("status");
                    if (!statusNode.isMissingNode()) {
                        logger.info("Completed accepting settlement {}, new status={}", settlementId, statusNode.textValue());
                    }
                    return null;
                }
        );
    }

    @Async
    public void rejectSettlement(String settlementId) {
        logger.info("Rejecting settlement {}...", settlementId);
        sendRequest(
                "POST",
                "/api/v1/settlement/" + settlementId + "/reject",
                null,
                true,
                node -> {
                    JsonNode statusNode = node.path("status");
                    if (!statusNode.isMissingNode()) {
                        logger.info("Completed rejecting settlement {}, new status={}", settlementId, statusNode.textValue());
                    }
                    return null;
                }
        );
    }

    @Async
    public void performSettlement(String settlementId) {
        logger.info("Performing settlement {}...", settlementId);
        sendRequest(
                "POST",
                "/api/v1/settlement/" + settlementId + "/settle",
                null,
                true,
                node -> {
                    JsonNode statusNode = node.path("status");
                    if (!statusNode.isMissingNode()) {
                        logger.info("Completed performing settlement {}, new status={}", settlementId, statusNode.textValue());
                    }
                    return null;
                }
        );
    }

    @Async
    public CompletableFuture<Void> createRfq(String baseAsset, String quoteAsset, Side side, int ttl, double qty) {
        logger.info("Creating RFQ: baseAsset={}, quoteAsset={}, side={}, ttl={}, qty={}", baseAsset, quoteAsset, side, ttl, qty);
        String payload = mapper.createObjectNode()
                .put("baseAsset", baseAsset)
                .put("quoteAsset", quoteAsset)
                .put("side", side.toString())
                .put("ttl", ttl)
                .put("qty", qty)
                .put("settlementMethod", SETTLEMENT_METHOD_OFF_CHAIN_IMMEDIATE)
                .toString();
        return sendRequest(
                "POST",
                "/api/v1/rfqs",
                payload,
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> updateRfq(String rfqId, int ttl) {
        logger.info("Updating RFQ: rfqId={}, ttl={}", rfqId, ttl);
        String payload = mapper.createObjectNode()
                .put("ttl", ttl)
                .toString();
        return sendRequest(
                "PATCH",
                "/api/v1/rfqs/" + rfqId,
                payload,
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> cancelRfq(String rfqId) {
        logger.info("Cancelling RFQ: rfqId={}", rfqId);
        return sendRequest(
                "DELETE",
                "/api/v1/rfqs/" + rfqId,
                null,
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> createOffer(String rfqId, int ttl, Double bidPx, Double bidQty, Double askPx, Double askQty) {
        logger.info("Creating offer: rfqId={}, ttl={}, bidPx={}, bidQty={}, askPx={}, askQty={}", rfqId, ttl, bidPx, bidQty, askPx, askQty);
        ObjectNode reqNode = mapper.createObjectNode()
                .put("ttl", ttl);
        Optional.ofNullable(bidPx).ifPresent(d -> reqNode.put("bidPx", d));
        Optional.ofNullable(bidQty).ifPresent(d -> reqNode.put("bidQty", d));
        Optional.ofNullable(askPx).ifPresent(d -> reqNode.put("askPx", d));
        Optional.ofNullable(askQty).ifPresent(d -> reqNode.put("askQty", d));
        return sendRequest(
                "POST",
                "/api/v1/rfqs/" + rfqId + "/offers",
                reqNode.toString(),
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> updateOffer(String rfqId, String offerId, int offerNonce, int ttl, Double bidPx, Double bidQty, Double askPx, Double askQty) {
        logger.info("Updating offer: rfqId={}, offerId={}, offerNonce={}, ttl={}, bidPx={}, bidQty={}, askPx={}, askQty={}", rfqId, offerId, offerNonce, ttl, bidPx, bidQty, askPx, askQty);
        ObjectNode reqNode = mapper.createObjectNode()
                .put("ttl", ttl);
        Optional.ofNullable(bidPx).ifPresent(d -> reqNode.put("bidPx", d));
        Optional.ofNullable(bidQty).ifPresent(d -> reqNode.put("bidQty", d));
        Optional.ofNullable(askPx).ifPresent(d -> reqNode.put("askPx", d));
        Optional.ofNullable(askQty).ifPresent(d -> reqNode.put("askQty", d));
        return sendRequest(
                "PATCH",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/offer/" + offerNonce,
                reqNode.toString(),
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> cancelOffer(String rfqId, String offerId, int offerNonce) {
        logger.info("Cancelling offer: rfqId={}, offerId={}, offerNonce={}", rfqId, offerId, offerNonce);
        return sendRequest(
                "DELETE",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/offer/" + offerNonce,
                null,
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> acceptOffer(String rfqId, String offerId, int offerNonce, Side side, double qty) {
        logger.info("Accepting offer: rfqId={}, offerId={}, offerNonce={}, side={}, qty={}", rfqId, offerId, offerNonce, side, qty);
        String payload = mapper.createObjectNode()
                .put("side", side.toString())
                .put("qty", qty)
                .toString();
        return sendRequest(
                "POST",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/offer/" + offerNonce + "/accept",
                payload,
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> rejectOffer(String rfqId, String offerId, int offerNonce) {
        logger.info("Rejecting offer: rfqId={}, offerId={}, offerNonce={}", rfqId, offerId, offerNonce);
        return sendRequest(
                "POST",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/offer/" + offerNonce + "/reject",
                null,
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> createCounter(String rfqId, String offerId, int ttl, Double bidPx, Double bidQty, Double askPx, Double askQty) {
        logger.info("Creating counter-offer: rfqId={}, offerId={}, ttl={}, bidPx={}, bidQty={}, askPx={}, askQty={}", rfqId, offerId, ttl, bidPx, bidQty, askPx, askQty);
        ObjectNode reqNode = mapper.createObjectNode()
                .put("ttl", ttl);
        Optional.ofNullable(bidPx).ifPresent(d -> reqNode.put("bidPx", d));
        Optional.ofNullable(bidQty).ifPresent(d -> reqNode.put("bidQty", d));
        Optional.ofNullable(askPx).ifPresent(d -> reqNode.put("askPx", d));
        Optional.ofNullable(askQty).ifPresent(d -> reqNode.put("askQty", d));
        return sendRequest(
                "POST",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/counter-offer",
                reqNode.toString(),
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> updateCounter(String rfqId, String offerId, int counterNonce, int ttl, Double bidPx, Double bidQty, Double askPx, Double askQty) {
        logger.info("Updating counter-offer: rfqId={}, offerId={}, counterNonce={}, ttl={}, bidPx={}, bidQty={}, askPx={}, askQty={}", rfqId, offerId, counterNonce, ttl, bidPx, bidQty, askPx, askQty);
        ObjectNode reqNode = mapper.createObjectNode()
                .put("ttl", ttl);
        Optional.ofNullable(bidPx).ifPresent(d -> reqNode.put("bidPx", d));
        Optional.ofNullable(bidQty).ifPresent(d -> reqNode.put("bidQty", d));
        Optional.ofNullable(askPx).ifPresent(d -> reqNode.put("askPx", d));
        Optional.ofNullable(askQty).ifPresent(d -> reqNode.put("askQty", d));
        return sendRequest(
                "PATCH",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/counter-offer/" + counterNonce,
                reqNode.toString(),
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> cancelCounter(String rfqId, String offerId, int counterNonce) {
        logger.info("Cancelling counter-offer: rfqId={}, offerId={}, counterNonce={}", rfqId, offerId, counterNonce);
        return sendRequest(
                "DELETE",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/counter-offer/" + counterNonce,
                null,
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> acceptCounter(String rfqId, String offerId, int counterNonce, Side side, double qty) {
        logger.info("Accepting counter-offer: rfqId={}, offerId={}, counterNonce={}, side={}, qty={}", rfqId, offerId, counterNonce, side, qty);
        String payload = mapper.createObjectNode()
                .put("side", side.toString())
                .put("qty", qty)
                .toString();
        return sendRequest(
                "POST",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/counter-offer/" + counterNonce + "/accept",
                payload,
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> rejectCounter(String rfqId, String offerId, int counterNonce) {
        logger.info("Rejecting counter-offer: rfqId={}, offerId={}, counterNonce={}", rfqId, offerId, counterNonce);
        return sendRequest(
                "POST",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/counter-offer/" + counterNonce + "/reject",
                null,
                true,
                node -> null
        );
    }

    private CompletableFuture<List<Asset>> getCryptos() {
        return sendRequest(
                "GET",
                "/api/v1/public/trading/crypto",
                null,
                false,
                node -> {
                    JsonNode cryptoListNode = node.path("cryptoList");
                    if (cryptoListNode.isMissingNode()) {
                        return Collections.emptyList();
                    }
                    List<Asset> list = new ArrayList<>();
                    cryptoListNode.fieldNames()
                            .forEachRemaining(name -> {
                                JsonNode tradableNode = cryptoListNode.path(name).path("meta").path("tradable");
                                if (tradableNode.asBoolean()) {
                                    list.add(new Asset(name, true));
                                    return;
                                }
                                JsonNode oldTradableNode = cryptoListNode.path(name).path("isTradable");
                                if (oldTradableNode.asBoolean()) {
                                    list.add(new Asset(name, true));
                                }
                            });
                    return list;
                }
        );
    }

    private CompletableFuture<List<Asset>> getFiats() {
        return sendRequest(
                "GET",
                "/api/v1/public/trading/fiat",
                null,
                false,
                node -> {
                    JsonNode fiatListNode = node.path("fiatList");
                    if (fiatListNode.isMissingNode()) {
                        return Collections.emptyList();
                    }
                    List<Asset> list = new ArrayList<>();
                    fiatListNode.fieldNames()
                            .forEachRemaining(name -> list.add(new Asset(name, false)));
                    return list;
                }
        );
    }

    private <T> CompletableFuture<T> sendRequest(
            String method,
            String path,
            String payload,
            boolean authenticated,
            Function<JsonNode, T> rawDataConverter
    ) {
        RequestBuilder builder = RequestBuilder.create(method);
        builder.setUri(apiUrlBase + path);
        if (StringUtils.isNotEmpty(payload)) {
            try {
                builder.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                builder.setEntity(new StringEntity(payload));
            } catch (Exception e) {
                logger.error("Failed to set payload for API request", e);
                return CompletableFuture.completedFuture(null);
            }
        }
        if (authenticated) {
            signer.getAuthHeaders(method, path, payload).forEach(builder::addHeader);
        }
        HttpUriRequest request = builder.build();
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            if (status == HttpStatus.SC_OK) {
                try {
                    String content = EntityUtils.toString(response.getEntity());
                    JsonNode node = mapper.readTree(content);
                    return CompletableFuture.completedFuture(
                            rawDataConverter != null ?
                                    rawDataConverter.apply(node) :
                                    null
                    );
                } catch (IOException e) {
                    logger.warn("Failed to parse response content", e);
                }
            }
            logger.error("Failed to send API request, status={}, path={}, content={}", status, path, EntityUtils.toString(response.getEntity()));
        } catch (IOException e) {
            logger.error("Failed to send API request", e);
        }
        return CompletableFuture.completedFuture(null);
    }

}
