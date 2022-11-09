package com.busywhale.busybot.component;

import com.busywhale.busybot.model.Asset;
import com.busywhale.busybot.model.RfqEntry;
import com.busywhale.busybot.model.Side;
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

@Component
public class ApiEngine {
    private static final Logger logger = LogManager.getLogger(ApiEngine.class);

    @Value("${busywhale.rest.url.base}")
    private String apiUrlBase;

    @Value("${asset.filter:}")
    private String assetFilter;

    @Autowired
    private CloseableHttpClient httpClient;

    @Autowired
    private ApiKeySigner signer;

    private final ObjectMapper mapper = new ObjectMapper();

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
                                .filter(asset -> assetSet.isEmpty() || assetSet.contains(asset.getSymbol()))
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
                    ArrayNode arrayNode = (ArrayNode) node;
                    Map<String, Double> map = new HashMap<>();
                    StreamSupport.stream(arrayNode.spliterator(), false)
                            .forEach(dataNode -> {
                                String symbol = dataNode.path("ix").asText();
                                double last = dataNode.path("last").doubleValue();
                                map.put(symbol, last);
                            });
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
                            .collect(Collectors.toList());
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
                            .collect(Collectors.toList());
                }
        );
    }

    @Async
    public CompletableFuture<Void> createRfq(String longAsset, String shortAsset, Side side, int ttl, double qty) {
        logger.info("Creating RFQ: longAsset={}, shortAsset={}, side={}, ttl={}, qty={}", longAsset, shortAsset, side, ttl, qty);
        String payload = mapper.createObjectNode()
                .put("longAsset", longAsset)
                .put("shortAsset", shortAsset)
                .put("side", side.toString())
                .put("ttl", ttl)
                .put("qty", qty)
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
    public CompletableFuture<Void> createOffer(String rfqId, int offerTtl, Double offerBidPx, Double offerBidQty, Double offerAskPx, Double offerAskQty) {
        logger.info("Creating offer: rfqId={}, ttl={}, bidPx={}, bidQty={}, askPx={}, askQty={}", rfqId, offerTtl, offerBidPx, offerBidQty, offerAskPx, offerAskQty);
        ObjectNode reqNode = mapper.createObjectNode()
                .put("offerTtl", offerTtl);
        Optional.ofNullable(offerBidPx).ifPresent(d -> reqNode.put("offerBidPx", d));
        Optional.ofNullable(offerBidQty).ifPresent(d -> reqNode.put("offerBidQty", d));
        Optional.ofNullable(offerAskPx).ifPresent(d -> reqNode.put("offerAskPx", d));
        Optional.ofNullable(offerAskQty).ifPresent(d -> reqNode.put("offerAskQty", d));
        return sendRequest(
                "POST",
                "/api/v1/rfqs/" + rfqId + "/offers",
                reqNode.toString(),
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> updateOffer(String rfqId, String offerId, int offerNonce, int offerTtl, Double offerBidPx, Double offerBidQty, Double offerAskPx, Double offerAskQty) {
        logger.info("Updating offer: rfqId={}, offerId={}, offerNonce={}, ttl={}, bidPx={}, bidQty={}, askPx={}, askQty={}", rfqId, offerId, offerNonce, offerTtl, offerBidPx, offerBidQty, offerAskPx, offerAskQty);
        ObjectNode reqNode = mapper.createObjectNode()
                .put("offerTtl", offerTtl);
        Optional.ofNullable(offerBidPx).ifPresent(d -> reqNode.put("offerBidPx", d));
        Optional.ofNullable(offerBidQty).ifPresent(d -> reqNode.put("offerBidQty", d));
        Optional.ofNullable(offerAskPx).ifPresent(d -> reqNode.put("offerAskPx", d));
        Optional.ofNullable(offerAskQty).ifPresent(d -> reqNode.put("offerAskQty", d));
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
    public CompletableFuture<Void> createCounter(String rfqId, String offerId, int counterTtl, Double counterBidPx, Double counterBidQty, Double counterAskPx, Double counterAskQty) {
        logger.info("Creating counter-offer: rfqId={}, offerId={}, ttl={}, bidPx={}, bidQty={}, askPx={}, askQty={}", rfqId, offerId, counterTtl, counterBidPx, counterBidQty, counterAskPx, counterAskQty);
        ObjectNode reqNode = mapper.createObjectNode()
                .put("counterTtl", counterTtl);
        Optional.ofNullable(counterBidPx).ifPresent(d -> reqNode.put("counterBidPx", d));
        Optional.ofNullable(counterBidQty).ifPresent(d -> reqNode.put("counterBidQty", d));
        Optional.ofNullable(counterAskPx).ifPresent(d -> reqNode.put("counterAskPx", d));
        Optional.ofNullable(counterAskQty).ifPresent(d -> reqNode.put("counterAskQty", d));
        return sendRequest(
                "POST",
                "/api/v1/rfqs/" + rfqId + "/offers/" + offerId + "/counter-offer",
                reqNode.toString(),
                true,
                node -> null
        );
    }

    @Async
    public CompletableFuture<Void> updateCounter(String rfqId, String offerId, int counterNonce, int counterTtl, Double counterBidPx, Double counterBidQty, Double counterAskPx, Double counterAskQty) {
        logger.info("Updating counter-offer: rfqId={}, offerId={}, counterNonce={}, ttl={}, bidPx={}, bidQty={}, askPx={}, askQty={}", rfqId, offerId, counterNonce, counterTtl, counterBidPx, counterBidQty, counterAskPx, counterAskQty);
        ObjectNode reqNode = mapper.createObjectNode()
                .put("counterTtl", counterTtl);
        Optional.ofNullable(counterBidPx).ifPresent(d -> reqNode.put("counterBidPx", d));
        Optional.ofNullable(counterBidQty).ifPresent(d -> reqNode.put("counterBidQty", d));
        Optional.ofNullable(counterAskPx).ifPresent(d -> reqNode.put("counterAskPx", d));
        Optional.ofNullable(counterAskQty).ifPresent(d -> reqNode.put("counterAskQty", d));
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
                            .forEachRemaining(name -> list.add(new Asset(name, true)));
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
