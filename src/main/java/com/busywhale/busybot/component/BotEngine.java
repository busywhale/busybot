package com.busywhale.busybot.component;

import com.busywhale.busybot.model.*;
import com.busywhale.busybot.websocket.WebSocketMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.base.Splitter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.busywhale.busybot.util.BotUtils.*;

@Component
public class BotEngine extends StompSessionHandlerAdapter {
    private static final Logger logger = LogManager.getLogger(BotEngine.class);
    private static final int TIMEOUT = 5;
    private static final String INDEX_SNAPSHOTS_DESTINATION = "/topic/public/indexes";
    private static final String RFQ_POST_DESTINATION = "/user/queue/rfq/post";
    private static final String MY_RFQ_DESTINATION = "/user/queue/rfq/my";
    private static final String POSITION_DESTINATION = "/user/queue/position";
    private static final String SETTLEMENT_DESTINATION = "/user/queue/settlement";
    private static final String PIVOT_CURRENCY = "USD";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Asset> assets = new ArrayList<>();
    private final Map<String, Asset> assetMap = new HashMap<>();
    private final Map<String, Double> indexes = new ConcurrentHashMap<>();
    private final Map<String, RfqEntry> rfqMap = new ConcurrentHashMap<>();
    private final Map<String, PositionEntry> positionMap = new ConcurrentHashMap<>();
    private final List<Triple<Supplier<CompletableFuture<Void>>, Double, String>> actions = new ArrayList<>();

    private final List<String> pendingRfqPostPayloads = new ArrayList<>();
    private final List<String> pendingMyRfqPayloads = new ArrayList<>();

    private final Map<String, Set<String>> supportedSettlementAssetsMap;
    private final Set<String> supportedSettlementMethods;
    private final Map<String, Double> defaultPrices;

    @Autowired
    private ApiEngine apiEngine;

    @Autowired
    private ApiKeySigner signer;

    @Autowired
    private WebSocketStompClient stompClient;

    @Value("${WEBSOCKET_URL}")
    private String websocketUrl;

    @Value("${CHANCE_CREATE_RFQ:0.0}")
    private double tossCreateRfq;

    @Value("${CHANCE_MODIFY_RFQ:0.0}")
    private double tossModifyRfq;

    @Value("${CHANCE_MODIFY_RFQ_UPDATE:0.5}")
    private double tossModifyRfqUpdate;

    @Value("${CHANCE_CREATE_OFFER:0.0}")
    private double tossCreateOffer;

    @Value("${CHANCE_MODIFY_OFFER:0.0}")
    private double tossModifyOffer;

    @Value("${CHANCE_MODIFY_OFFER_UPDATE:0.5}")
    private double tossModifyOfferUpdate;

    @Value("${CHANCE_ANSWER_OFFER:0.0}")
    private double tossAnswerOffer;

    @Value("${CHANCE_ANSWER_OFFER_ACCEPT:0.5}")
    private double tossAnswerOfferAccept;

    @Value("${CHANCE_CREATE_COUNTER:0.0}")
    private double tossCreateCounter;

    @Value("${CHANCE_MODIFY_COUNTER:0.0}")
    private double tossModifyCounter;

    @Value("${CHANCE_MODIFY_COUNTER_UPDATE:0.5}")
    private double tossModifyCounterUpdate;

    @Value("${CHANCE_ANSWER_COUNTER:0.0}")
    private double tossAnswerCounter;

    @Value("${CHANCE_ANSWER_COUNTER_ACCEPT:0.5}")
    private double tossAnswerCounterAccept;

    @Value("${UPDATE_EXPIRING_ITEMS_ONLY:true}")
    private boolean updateExpiringItemsOnly;

    // remaining ttl in seconds for expiring rfqs/offers
    @Value("${EXPIRY_BUFFER:5}")
    private long expiryBuffer;

    @Value("${EXTERNAL_RATES_FIAT_URL:-}")
    private String externalFiatRatesUrl;

    @Value("${USE_MINIMUM_TTL:true}")
    private boolean useMinTtl;

    // managing multiple offers at a time if true
    @Value("${QUOTING_MODE:false}")
    private boolean quotingMode;

    @Value("${MARKET_WIDTH:0.002}")
    private double marketWidth;


    @Value("${MAX_RANDOM_QTY_NOTIONAL:5000}")
    private double maxNotionalForRandomQty;

    @Value("${MAX_LIVE_RFQS_PER_USER:20}")
    private int maxLiveRfqsPerUser;

    @Value("${DEBUG_PRINT_INDEX_SNAPSHOT:false}")
    private boolean printIndexSnapshot;

    private boolean isShuttingDown = false;
    private boolean isConnected = false;
    private boolean isRfqPostSnapshotReady = false;
    private boolean isMyRfqSnapshotReady = false;

    public BotEngine(
            @Value("${SUPPORTED_SETTLEMENT_ASSETS_MAP:{}}")
            String supportedSettlementAssetsMapString,
            @Value("${DEFAULT_PRICES:{}}")
            String defaultPricesString
    ) throws Exception {
        supportedSettlementAssetsMap = mapper.readValue(supportedSettlementAssetsMapString, new TypeReference<>() {
        });
        supportedSettlementMethods = supportedSettlementAssetsMap.keySet();
        defaultPrices = mapper.readValue(defaultPricesString, new TypeReference<>() {
        });
    }

    @PostConstruct
    public void postConstruct() {
        logger.info("Supported assets: {}", supportedSettlementAssetsMap);

        initData();
        initActionList();
        initWebSocketConnection();
    }

    @PreDestroy
    public void preDestroy() {
        logger.info("Pulling all active RFQs...");
        List<CompletableFuture<Void>> pullRfqFutures = getEditableRfqs().stream()
                .map(rfq -> apiEngine.cancelRfq(rfq.getId()))
                .toList();

        logger.info("Pulling all active offers...");
        List<CompletableFuture<Void>> pullOfferFutures = getEditableOffers().stream().map(pair -> {
            RfqEntry rfqEntry = pair.getLeft();
            OfferEntry offerEntry = pair.getRight();
            return apiEngine.cancelOffer(
                    rfqEntry.getId(),
                    offerEntry.getId(),
                    offerEntry.getOffer().getNonce()
            );
        }).toList();

        CompletableFuture.allOf(
                Stream.of(pullRfqFutures, pullOfferFutures)
                        .flatMap(Collection::stream)
                        .toArray(CompletableFuture[]::new)
        ).join();

        isShuttingDown = true;
        logger.info("All active RFQs and offers have been pulled.");
    }

    @Scheduled(fixedDelayString = "${ACTION_INTERVAL:10000}")
    public void doAction() {
        if (!isConnected) {
            // no performing any action if it's not connected
            return;
        }
        actions.forEach(action -> {
            if (toss(action.getMiddle())) {
                logger.trace("Performing action: {}", action.getRight());
                try {
                    action.getLeft().get().get(TIMEOUT, TimeUnit.SECONDS);
                } catch (ExecutionException | InterruptedException | TimeoutException e) {
                    logger.error("Timed out when {}", action.getRight());
                }
            } else {
                logger.trace("Skipping: {}", action.getRight());
            }
        });
    }

    @Override
    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
        logger.info("New WebSocket session established: {}, headers={}", session.getSessionId(), connectedHeaders);
        isConnected = true;
        session.subscribe(INDEX_SNAPSHOTS_DESTINATION, this);
        session.subscribe(RFQ_POST_DESTINATION, this);
        session.subscribe(MY_RFQ_DESTINATION, this);
        session.subscribe(POSITION_DESTINATION, this);
        session.subscribe(SETTLEMENT_DESTINATION, this);

        loadMyRfqs();
        loadRfqPosts();
        loadPositions();
        loadSettlements();
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
        logger.error("WebSocket transport error ({}): {}", session.getSessionId(), exception.getMessage(), exception);
        handleError();
    }

    @Override
    public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
        logger.error("Error in WebSocket connection", exception);
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
        WebSocketMessage message = (WebSocketMessage) payload;

        if (message == null) {
            String s = headers.getFirst("message");
            if (StringUtils.isNotEmpty(s)) {
                logger.warn("Stomp message from server: " + s);
            }
            return;
        }
        if (message.command() == StompCommand.MESSAGE) {
            String destination = headers.getDestination();
            if (destination == null) {
                return;
            }
            switch (destination) {
                case INDEX_SNAPSHOTS_DESTINATION:
                    handleIndexSnapshotFeed(message.message());
                    break;
                case RFQ_POST_DESTINATION:
                    if (!isRfqPostSnapshotReady) {
                        pendingRfqPostPayloads.add(message.message());
                    } else {
                        handleRfqPostFeed(message.message());
                    }
                    break;
                case MY_RFQ_DESTINATION:
                    if (!isMyRfqSnapshotReady) {
                        pendingMyRfqPayloads.add(message.message());
                    } else {
                        handleMyRfqFeed(message.message());
                    }
                    break;
                case POSITION_DESTINATION:
                    handlePositionFeed(message.message());
                    break;
                case SETTLEMENT_DESTINATION:
                    handleSettlementFeed(message.message());
                    break;
            }
        } else if (message.command() == StompCommand.ERROR) {
            logger.error("Stomp ERROR: {}", message.message());
            handleError();
        }
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
        return WebSocketMessage.class;
    }

    private void handleError() {
        isConnected = false;

        // clearing any existing data
        rfqMap.clear();

        new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (!isShuttingDown && !isConnected) {
                logger.debug("Trying to reconnect...");
                initWebSocketConnection();
            }
        }).start();
    }

    private void initActionList() {
        actions.addAll(List.of(
                Triple.of(this::createRfq, tossCreateRfq, "creating RFQ"),
                Triple.of(this::updateRfq, tossModifyRfq, "updating RFQ"),
                Triple.of(this::createOffer, tossCreateOffer, "creating offer"),
                Triple.of(this::updateOffer, tossModifyOffer, "updating offer"),
                Triple.of(this::answerOffer, tossAnswerOffer, "answering offer"),
                Triple.of(this::createCounter, tossCreateCounter, "creating counter-offer"),
                Triple.of(this::updateCounter, tossModifyCounter, "updating counter-offer"),
                Triple.of(this::answerCounter, tossAnswerCounter, "answering counter-offer")
        ));
    }

    private void initData() {
        defaultPrices.forEach((key, value) -> {
            logger.info("Loaded default price for {}: {}", key, value);
            indexes.putIfAbsent(key, value);
        });
        loadFiatRates();
        try {
            CompletableFuture.allOf(
                    apiEngine.getAssets()
                            .thenAccept(list -> {
                                assets.addAll(list);
                                list.forEach(a -> assetMap.put(a.symbol(), a));
                            }),
                    apiEngine.getIndexSnapshots()
                            .thenAccept(indexes::putAll)
            ).get(TIMEOUT, TimeUnit.SECONDS);

            logger.info("Read {} assets and snapshot for {} indexes/rates", assets.size(), indexes.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch assets and index snapshot", e);
        }
    }

    private void loadFiatRates() {
        try {
            logger.info("Loading fiat rates from external source...");

            String content = IOUtils.toString(new URL(externalFiatRatesUrl), StandardCharsets.UTF_8);
            Splitter.on("\r\n").omitEmptyStrings().splitToList(content).forEach(data -> {
                logger.info("Read fiat rate data: {}", data);
                List<String> values = Splitter.on(',').omitEmptyStrings().splitToList(data);
                indexes.put(values.get(2), Double.valueOf(values.get(3)));
            });
            indexes.putIfAbsent(PIVOT_CURRENCY, 1.0);
        } catch (Exception e) {
            logger.error("Failed to load fiat rates from external source", e);
        }
    }

    private void loadPositions() {
        try {
            logger.info("Loading positions...");
            apiEngine.getPositions()
                    .thenAccept(list -> {
                        logger.info("Received {} positions", list.size());
                        list.forEach(this::handlePosition);
                    });
        } catch (Exception e) {
            logger.error("Failed to load positions", e);
        }
    }

    private void loadSettlements() {
        try {
            logger.info("Loading settlements...");
            apiEngine.getSettlements()
                    .thenAccept(list -> {
                        logger.info("Received {} settlements", list.size());
                        list.forEach(this::handleSettlement);
                    });
        } catch (Exception e) {
            logger.error("Failed to load settlements", e);
        }
    }

    private void loadMyRfqs() {
        try {
            logger.info("Loading my own RFQs...");
            apiEngine.getMyRfqs()
                    .thenAccept(list -> {
                        logger.info("Received {} own RFQs", list.size());
                        list.forEach(this::handleMyRfq);
                        while (!pendingMyRfqPayloads.isEmpty()) {
                            handleMyRfqFeed(pendingMyRfqPayloads.remove(0));
                        }
                        isMyRfqSnapshotReady = true;
                    });
        } catch (Exception e) {
            logger.error("Failed to load my own RFQs", e);
        }
    }

    private void loadRfqPosts() {
        try {
            logger.info("Loading RFQ posts...");
            apiEngine.getRfqPosts()
                    .thenAccept(list -> {
                        logger.info("Received {} RFQ posts", list.size());
                        list.forEach(this::handleRfqPost);
                        while (!pendingRfqPostPayloads.isEmpty()) {
                            handleRfqPostFeed(pendingRfqPostPayloads.remove(0));
                        }
                        isRfqPostSnapshotReady = true;
                    });
        } catch (Exception e) {
            logger.error("Failed to load RFQ posts", e);
        }
    }

    private void initWebSocketConnection() {
        logger.info("Initializing WebSocket connection...");

        StompHeaders stompHeaders = new StompHeaders();
        stompHeaders.setAll(signer.getAuthHeaders(
                "GET", "/feeds", null
        ));
        stompClient.connect(
                websocketUrl,
                (WebSocketHttpHeaders) null,
                stompHeaders,
                this
        );
    }

    private void handleIndexSnapshotFeed(String payload) {
        if (printIndexSnapshot) {
            logger.debug("Received index snapshot: {}", payload);
        }
        try {
            JsonNode node = mapper.readTree(payload);
            JsonNode indexesNode = node.path("indexes").path("indexes");
            if (!indexesNode.isMissingNode()) {
                indexesNode.fields()
                        .forEachRemaining(e -> indexes.put(e.getKey(), e.getValue().path("L").doubleValue()));
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse index snapshot data", e);
        }
    }

    private void handleRfqPostFeed(String payload) {
        logger.debug("Received RFQ post feed: {}", payload);
        try {
            JsonNode node = mapper.readTree(payload);
            String event = node.get("event").asText();

            if ("EVENT_RFQ_POST_UPDATE".equals(event)) {
                JsonNode rfqNode = node.path("rfq");
                if (!rfqNode.isMissingNode()) {
                    RfqEntry rfqEntry = mapper.treeToValue(rfqNode, RfqEntry.class);
                    handleRfqPost(rfqEntry);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to parse RFQ post data", e);
        }
    }

    private void handleMyRfqFeed(String payload) {
        logger.debug("Received my RFQ feed: {}", payload);

        try {
            JsonNode node = mapper.readTree(payload);

            JsonNode rfqNode = node.path("rfq");
            if (!rfqNode.isMissingNode()) {
                RfqEntry rfqEntry = mapper.treeToValue(rfqNode, RfqEntry.class);
                handleMyRfq(rfqEntry);
            }
        } catch (IOException e) {
            logger.error("Failed to parse own RFQ data", e);
        }
    }

    private void handlePositionFeed(String payload) {
        logger.debug("Received position feed: {}", payload);

        try {
            JsonNode node = mapper.readTree(payload);
            JsonNode positionsNode = node.path("positions");
            if (!positionsNode.isMissingNode()) {
                ArrayNode arrayNode = (ArrayNode) positionsNode;
                StreamSupport.stream(arrayNode.spliterator(), false)
                        .map(dataNode -> {
                            try {
                                return mapper.treeToValue(dataNode, PositionEntry.class);
                            } catch (Exception ignored) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .forEach(this::handlePosition);
            }
        } catch (IOException e) {
            logger.error("Failed to parse position feed data");
        }
    }

    private void handleSettlementFeed(String payload) {
        logger.debug("Received settlement feed: {}", payload);

        try {
            JsonNode node = mapper.readTree(payload);
            JsonNode settlementNode = node.path("settlement");
            if (!settlementNode.isMissingNode()) {
                SettlementEntry settlementEntry = mapper.treeToValue(settlementNode, SettlementEntry.class);
                handleSettlement(settlementEntry);
            }
        } catch (IOException e) {
            logger.error("Failed to parse settlement feed data", e);
        }
    }

    private void handleRfqPost(RfqEntry rfqEntry) {
        String rfqId = rfqEntry.getId();
        if (rfqEntry.getStatus() == RfqStatus.DONE) {
            rfqMap.remove(rfqId);
            return;
        }
        RfqEntry oldRfqEntry = rfqMap.get(rfqId);
        if (oldRfqEntry != null) {
            mergeRfqDetails(rfqEntry, oldRfqEntry);
        } else {
            rfqMap.put(rfqId, rfqEntry);
        }
    }

    private void handleMyRfq(RfqEntry rfqEntry) {
        String rfqId = rfqEntry.getId();
        if (rfqEntry.getStatus() == RfqStatus.DONE) {
            rfqMap.remove(rfqId);
            return;
        }
        RfqEntry oldRfqEntry = rfqMap.get(rfqId);
        if (oldRfqEntry != null) {
            mergeRfqDetails(rfqEntry, oldRfqEntry);
            mergeRfqOffers(rfqEntry, oldRfqEntry);
        } else {
            rfqMap.put(rfqId, rfqEntry);
        }
    }

    private void handlePosition(PositionEntry positionEntry) {
        PositionEntry existing = positionMap.get(positionEntry.getAsset());
        if (existing == null || positionEntry.getUpdateTime() > existing.getUpdateTime()) {
            positionMap.put(positionEntry.getAsset(), positionEntry);
        }
    }

    private void handleSettlement(SettlementEntry settlementEntry) {
        switch (settlementEntry.getStatus()) {
            case PENDING_ACCEPT -> apiEngine.acceptSettlement(settlementEntry.getId());
            case PENDING_SETTLE, PENDING_BOTH_SETTLE -> apiEngine.performSettlement(settlementEntry.getId());
        }
    }

    private void mergeRfqDetails(RfqEntry rfqEntry, RfqEntry oldRfqEntry) {
        Optional.ofNullable(rfqEntry.getBaseAsset()).ifPresent(oldRfqEntry::setBaseAsset);
        Optional.ofNullable(rfqEntry.getQuoteAsset()).ifPresent(oldRfqEntry::setQuoteAsset);
        Optional.ofNullable(rfqEntry.getQty()).ifPresent(oldRfqEntry::setQty);
        Optional.ofNullable(rfqEntry.getSide()).ifPresent(oldRfqEntry::setSide);
        Optional.ofNullable(rfqEntry.getStatus()).ifPresent(oldRfqEntry::setStatus);
        Optional.ofNullable(rfqEntry.getRequester()).ifPresent(oldRfqEntry::setRequester);
        Optional.ofNullable(rfqEntry.getExpiryTime()).ifPresent(oldRfqEntry::setExpiryTime);
        Optional.ofNullable(rfqEntry.getTtl()).ifPresent(oldRfqEntry::setTtl);
        Optional.ofNullable(rfqEntry.getQty()).ifPresent(oldRfqEntry::setQty);
        Optional.ofNullable(rfqEntry.getUpdateTime()).ifPresent(oldRfqEntry::setUpdateTime);
    }

    private void mergeRfqOffers(RfqEntry rfqEntry, RfqEntry oldRfqEntry) {
        List<OfferEntry> oldOfferEntries = oldRfqEntry.getOffers();
        if (oldOfferEntries == null) {
            oldOfferEntries = new ArrayList<>();
            oldRfqEntry.setOffers(oldOfferEntries);
        }
        Map<String, OfferEntry> oldOfferEntryMap = convertToMap(oldOfferEntries, OfferEntry::getId);

        for (OfferEntry offerEntry : ListUtils.emptyIfNull(rfqEntry.getOffers())) {
            OfferEntry oldOfferEntry = oldOfferEntryMap.get(offerEntry.getId());
            if (oldOfferEntry == null) {
                // new offer in update, put it into list directly
                oldOfferEntries.add(offerEntry);
                continue;
            }
            if (offerEntry.getOfferor() != null) {
                oldOfferEntry.setOfferor(offerEntry.getOfferor());
            }
            if (offerEntry.getOffer() != null) {
                if (oldOfferEntry.getOffer() == null) {
                    oldOfferEntry.setOffer(offerEntry.getOffer());
                } else {
                    copyOfferDetails(offerEntry.getOffer(), oldOfferEntry.getOffer());
                }
            }
            if (offerEntry.getCounter() != null) {
                if (oldOfferEntry.getCounter() == null) {
                    oldOfferEntry.setCounter(offerEntry.getCounter());
                } else {
                    copyOfferDetails(offerEntry.getCounter(), oldOfferEntry.getCounter());
                }
            }
            if (offerEntry.getConfirm() != null) {
                oldOfferEntry.setConfirm(offerEntry.getConfirm());
            }
        }
    }

    private void copyOfferDetails(OfferDetails offerDetails, OfferDetails oldOfferDetails) {
        Optional.ofNullable(offerDetails.getNonce()).ifPresent(oldOfferDetails::setNonce);
        Optional.ofNullable(offerDetails.getStatus()).ifPresent(oldOfferDetails::setStatus);
        // update bid/ask only if they're being updated, offer for both-sided RFQs can change from bid-only to ask-only
        if (offerDetails.getBidPx() != null || offerDetails.getBidQty() != null || offerDetails.getAskPx() != null || offerDetails.getAskQty() != null) {
            oldOfferDetails.setBidPx(offerDetails.getBidPx());
            oldOfferDetails.setBidQty(offerDetails.getBidQty());
            oldOfferDetails.setAskPx(offerDetails.getAskPx());
            oldOfferDetails.setAskQty(offerDetails.getAskQty());
        }
        Optional.ofNullable(offerDetails.getTtl()).ifPresent(oldOfferDetails::setTtl);
        Optional.ofNullable(offerDetails.getCreateTime()).ifPresent(oldOfferDetails::setCreateTime);
        Optional.ofNullable(offerDetails.getUpdateTime()).ifPresent(oldOfferDetails::setUpdateTime);
        Optional.ofNullable(offerDetails.getExpiryTime()).ifPresent(oldOfferDetails::setExpiryTime);
    }

    private boolean shouldUpdateItem(long expiry) {
        return !updateExpiringItemsOnly || expiry - Instant.now().getEpochSecond() <= expiryBuffer;
    }

    private CompletableFuture<Void> createRfq() {
        List<RfqEntry> myLiveRfqs = getEligibleRfqs(true, false, null);
        if (myLiveRfqs.size() >= maxLiveRfqsPerUser) {
            logger.info("Not creating new RFQ as this would exceed max live RFQ count of {}: {} active, {} expired",
                    maxLiveRfqsPerUser,
                    myLiveRfqs.stream().filter(rfq -> rfq.getStatus() == RfqStatus.ACTIVE).count(),
                    myLiveRfqs.stream().filter(rfq -> rfq.getStatus() == RfqStatus.EXPIRED).count()
            );
            return CompletableFuture.completedFuture(null);
        }

        String settlementMethod = getRandomFromList(supportedSettlementMethods.stream().toList());
        List<Asset> supportedAssets = assets.stream().filter(a -> SetUtils.emptyIfNull(supportedSettlementAssetsMap.get(settlementMethod)).contains(a.symbol())).toList();
        String baseAsset = getRandomAsset(supportedAssets, true, null).symbol();
        String quoteAsset = getRandomAsset(supportedAssets, true, baseAsset).symbol();
        Side side = getRandomSide(true);
        int ttl = getSuggestedTtl(false, useMinTtl);
        // qty capped at 5000 USDT
        double qtyBound = Optional.ofNullable(indexes.get(baseAsset))
                .filter(i -> i > 0)
                .map(i -> maxNotionalForRandomQty / i)
                .orElse(maxNotionalForRandomQty);
        double qty = getRandomQty(0, qtyBound);

        return apiEngine.createRfq(
                baseAsset,
                quoteAsset,
                settlementMethod,
                side,
                ttl,
                qty
        );
    }

    private CompletableFuture<Void> updateRfq() {
        RfqEntry targetRfq = getRandomFromList(getEditableRfqs());
        if (targetRfq == null) {
            logger.trace("No eligible RFQ to update/cancel");
            return CompletableFuture.completedFuture(null);
        }
        if (toss(tossModifyRfqUpdate)) {
            if (shouldUpdateItem(targetRfq.getExpiryTime())) {
                return apiEngine.updateRfq(
                        targetRfq.getId(),
                        getSuggestedTtl(false, useMinTtl)
                );
            }
            return CompletableFuture.completedFuture(null);
        } else if (isCancellable(targetRfq)) {
            return apiEngine.cancelRfq(
                    targetRfq.getId()
            );
        } else {
            logger.trace("Selected RFQ is not cancellable.");
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> createOffer() {
        // entries in rfqMap:
        // - active
        // - with requester
        // - with no previous offers
        List<RfqEntry> eligibleRfqPosts = getEligibleRfqs(
                false,
                true,
                rfq -> {
                    if (ListUtils.emptyIfNull(rfq.getOffers())
                            .stream()
                            .noneMatch(offerEntry -> offerEntry.getOfferor() == null)) {
                        Set<String> settlementAssets = SetUtils.emptyIfNull(supportedSettlementAssetsMap.get(rfq.getSettlementMethod()));
                        return settlementAssets.contains(rfq.getBaseAsset()) && settlementAssets.contains(rfq.getQuoteAsset());
                    }
                    return false;
                }
        );
        List<RfqEntry> targetRfqs = quotingMode ?
                eligibleRfqPosts :
                Optional.ofNullable(getRandomFromList(eligibleRfqPosts)).map(List::of).orElse(Collections.emptyList());
        if (CollectionUtils.isEmpty(targetRfqs)) {
            logger.trace("No eligible RFQ for creating offer");
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (RfqEntry targetRfq : targetRfqs) {
            double reference = getReferencePrice(targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
            if (reference == 0.0) {
                logger.warn("No reference price for creating offer: baseAsset={}, quoteAsset={}", targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
                return CompletableFuture.completedFuture(null);
            }
            Side offerSide = switch (targetRfq.getSide()) {
                case BUY -> Side.SELL;
                case SELL -> Side.BUY;
                default -> getRandomSide(true);
            };
            futures.add(apiEngine.createOffer(
                    targetRfq.getId(),
                    getSuggestedTtl(true, useMinTtl),
                    offerSide != Side.SELL ? getRandomPrice(reference, marketWidth, Side.BUY) : null,
                    offerSide != Side.SELL ? getRandomQty(targetRfq.getMinQty().doubleValue(), targetRfq.getQty().doubleValue()) : null,
                    offerSide != Side.BUY ? getRandomPrice(reference, marketWidth, Side.SELL) : null,
                    offerSide != Side.BUY ? getRandomQty(targetRfq.getMinQty().doubleValue(), targetRfq.getQty().doubleValue()) : null
            ));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> updateOffer() {
        List<Pair<RfqEntry, OfferEntry>> editableOffers = getEditableOffers();
        List<Pair<RfqEntry, OfferEntry>> targetOffers = quotingMode ?
                editableOffers :
                Optional.ofNullable(getRandomFromList(editableOffers)).map(List::of).orElse(Collections.emptyList());
        if (CollectionUtils.isEmpty(targetOffers)) {
            logger.trace("No eligible offer to update");
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Pair<RfqEntry, OfferEntry> pair : targetOffers) {
            RfqEntry targetRfq = pair.getLeft();
            OfferEntry targetOffer = pair.getRight();
            if (toss(tossModifyOfferUpdate)) {
                if (shouldUpdateItem(targetOffer.getOffer().getExpiryTime())) {
                    double reference = getReferencePrice(targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
                    if (reference == 0.0) {
                        logger.warn("No reference price for updating offer: baseAsset={}, quoteAsset={}", targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
                        futures.add(CompletableFuture.completedFuture(null));
                        continue;
                    }
                    Side offerSide = switch (targetRfq.getSide()) {
                        case BUY -> Side.SELL;
                        case SELL -> Side.BUY;
                        default -> getRandomSide(true);
                    };
                    futures.add(apiEngine.updateOffer(
                            targetRfq.getId(),
                            targetOffer.getId(),
                            targetOffer.getOffer().getNonce(),
                            getSuggestedTtl(true, useMinTtl),
                            offerSide != Side.SELL ? getRandomPrice(reference, marketWidth, Side.BUY) : null,
                            offerSide != Side.SELL ? getRandomQty(targetRfq.getMinQty().doubleValue(), targetRfq.getQty().doubleValue()) : null,
                            offerSide != Side.BUY ? getRandomPrice(reference, marketWidth, Side.SELL) : null,
                            offerSide != Side.BUY ? getRandomQty(targetRfq.getMinQty().doubleValue(), targetRfq.getQty().doubleValue()) : null
                    ));
                    continue;
                }
                futures.add(CompletableFuture.completedFuture(null));
            } else if (isCancellable(targetOffer.getOffer())) {
                futures.add(apiEngine.cancelOffer(
                        targetRfq.getId(),
                        targetOffer.getId(),
                        targetOffer.getOffer().getNonce()
                ));
            } else {
                logger.trace("Selected offer is not cancellable.");
                futures.add(CompletableFuture.completedFuture(null));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> answerOffer() {
        Pair<RfqEntry, OfferEntry> pair = getAnswerableOffer();
        if (pair == null) {
            logger.trace("No offer to answer");
            return CompletableFuture.completedFuture(null);
        }
        RfqEntry targetRfq = pair.getLeft();
        OfferEntry targetOffer = pair.getRight();
        if (toss(tossAnswerOfferAccept)) {
            Side offerSide;
            if (targetOffer.getOffer().getBidQty() != null && targetOffer.getOffer().getAskQty() != null) {
                offerSide = getRandomSide(false);
            } else if (targetOffer.getOffer().getBidQty() != null) {
                offerSide = Side.BUY;
            } else if (targetOffer.getOffer().getAskQty() != null) {
                offerSide = Side.SELL;
            } else {
                return CompletableFuture.completedFuture(null);
            }

            double reference = getReferencePrice(targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
            if (reference == 0.0) {
                logger.warn("No reference price for answering offer: baseAsset={}, quoteAsset={}", targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
                return CompletableFuture.completedFuture(null);
            }
            double myPrice = getRandomPrice(reference, marketWidth, offerSide);
            double acceptingQty;
            Side acceptingSide;
            if (offerSide == Side.BUY) {
                acceptingSide = Side.SELL;
                double offerPrice = targetOffer.getOffer().getBidPx().doubleValue();
                if (offerPrice < myPrice) {
                    // price is too low to accept
                    logger.debug("Offer price is too low to accept: offerPrice={}, offerSide={}, baseAsset={}, quoteAsset={}, reference={}, myPrice={}",
                            offerPrice, offerSide, targetRfq.getBaseAsset(), targetRfq.getQuoteAsset(), reference, myPrice);
                    return CompletableFuture.completedFuture(null);
                }
                acceptingQty = getRandomQty(targetOffer.getOffer().getMinQty().doubleValue(), targetOffer.getOffer().getBidQty().doubleValue());
            } else {
                acceptingSide = Side.BUY;
                double offerPrice = targetOffer.getOffer().getAskPx().doubleValue();
                if (offerPrice > myPrice) {
                    // price is too high to accept
                    logger.debug("Offer price is too high to accept: offerPrice={}, offerSide={}, baseAsset={}, quoteAsset={}, reference={}, myPrice={}",
                            offerPrice, offerSide, targetRfq.getBaseAsset(), targetRfq.getQuoteAsset(), reference, myPrice);
                    return CompletableFuture.completedFuture(null);
                }
                acceptingQty = getRandomQty(targetOffer.getOffer().getMinQty().doubleValue(), targetOffer.getOffer().getAskQty().doubleValue());
            }

            return apiEngine.acceptOffer(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getOffer().getNonce(),
                    acceptingSide,
                    acceptingQty
            );
        } else {
            return apiEngine.rejectOffer(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getOffer().getNonce()
            );
        }
    }

    private CompletableFuture<Void> createCounter() {
        // entries in rfqMap:
        // - with no requester
        // - with offers
        //    - with offeror
        //    - with no counter
        //    - with no confirm
        Predicate<OfferEntry> offerEntryPredicate = o -> o.getOfferor() != null &&
                o.getOffer() != null &&
                o.getCounter() == null &&
                o.getConfirm() == null;
        List<RfqEntry> eligibleOwnRfqs = getEligibleRfqsWithOffers(true, false, offerEntryPredicate);
        RfqEntry targetRfq = getRandomFromList(eligibleOwnRfqs);
        if (targetRfq == null) {
            logger.trace("No eligible RFQ for creating counter-offer");
            return CompletableFuture.completedFuture(null);
        }
        List<OfferEntry> eligibleOffers = ListUtils.emptyIfNull(targetRfq.getOffers())
                .stream()
                .filter(offerEntryPredicate)
                .collect(Collectors.toList());
        OfferEntry targetOffer = getRandomFromList(eligibleOffers);
        if (targetOffer == null) {
            logger.trace("No eligible offer for creating counter-offer");
            return CompletableFuture.completedFuture(null);
        }
        double reference = getReferencePrice(targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
        if (reference == 0.0) {
            logger.warn("No reference price for sending counter-offer: baseAsset={}, quoteAsset={}", targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
            return CompletableFuture.completedFuture(null);
        }
        return apiEngine.createCounter(
                targetRfq.getId(),
                targetOffer.getId(),
                getSuggestedTtl(true, useMinTtl),
                targetOffer.getOffer().getAskPx() != null ? getRandomPrice(reference, marketWidth, Side.BUY) : null,
                targetOffer.getOffer().getAskQty() != null ? getRandomQty(targetRfq.getMinQty().doubleValue(), targetOffer.getOffer().getAskQty().doubleValue()) : null,
                targetOffer.getOffer().getBidPx() != null ? getRandomPrice(reference, marketWidth, Side.SELL) : null,
                targetOffer.getOffer().getBidQty() != null ? getRandomQty(targetRfq.getMinQty().doubleValue(), targetOffer.getOffer().getBidQty().doubleValue()) : null
        );
    }

    private CompletableFuture<Void> updateCounter() {
        Pair<RfqEntry, OfferEntry> pair = getEditableCounterOffer();
        if (pair == null) {
            logger.trace("No eligible counter-offer to update");
            return CompletableFuture.completedFuture(null);
        }
        RfqEntry targetRfq = pair.getLeft();
        OfferEntry targetOffer = pair.getRight();
        if (toss(tossModifyCounterUpdate)) {
            if (shouldUpdateItem(targetOffer.getCounter().getExpiryTime())) {
                double reference = getReferencePrice(targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
                if (reference == 0.0) {
                    logger.warn("No reference price for sending counter-offer: baseAsset={}, quoteAsset={}", targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
                    return CompletableFuture.completedFuture(null);
                }
                return apiEngine.updateCounter(
                        targetRfq.getId(),
                        targetOffer.getId(),
                        targetOffer.getCounter().getNonce(),
                        getSuggestedTtl(true, useMinTtl),
                        targetOffer.getOffer().getAskPx() != null ? getRandomPrice(reference, marketWidth, Side.BUY) : null,
                        targetOffer.getOffer().getAskQty() != null ? getRandomQty(targetRfq.getMinQty().doubleValue(), targetOffer.getOffer().getAskQty().doubleValue()) : null,
                        targetOffer.getOffer().getBidPx() != null ? getRandomPrice(reference, marketWidth, Side.SELL) : null,
                        targetOffer.getOffer().getBidQty() != null ? getRandomQty(targetRfq.getMinQty().doubleValue(), targetOffer.getOffer().getBidQty().doubleValue()) : null
                );
            }
            return CompletableFuture.completedFuture(null);
        } else if (isCancellable(targetOffer.getCounter())) {
            return apiEngine.cancelCounter(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getCounter().getNonce()
            );
        } else {
            logger.trace("Selected counter-offer is not cancellable.");
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> answerCounter() {
        Pair<RfqEntry, OfferEntry> pair = getAnswerableCounterOffer();
        if (pair == null) {
            logger.trace("No eligible counter-offer to answer");
            return CompletableFuture.completedFuture(null);
        }
        RfqEntry targetRfq = pair.getLeft();
        OfferEntry targetOffer = pair.getRight();
        if (toss(tossAnswerCounterAccept)) {
            Side counterOfferSide;
            if (targetOffer.getCounter().getBidQty() != null && targetOffer.getCounter().getAskQty() != null) {
                counterOfferSide = getRandomSide(false);
            } else if (targetOffer.getCounter().getBidQty() != null) {
                counterOfferSide = Side.BUY;
            } else if (targetOffer.getCounter().getAskQty() != null) {
                counterOfferSide = Side.SELL;
            } else {
                return CompletableFuture.completedFuture(null);
            }

            double reference = getReferencePrice(targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
            if (reference == 0.0) {
                logger.warn("No reference price for answering counter-offer: baseAsset={}, quoteAsset={}", targetRfq.getBaseAsset(), targetRfq.getQuoteAsset());
                return CompletableFuture.completedFuture(null);
            }
            double myPrice = getRandomPrice(reference, marketWidth, counterOfferSide);
            double acceptingQty;
            Side acceptingSide;
            if (counterOfferSide == Side.BUY) {
                acceptingSide = Side.SELL;
                double counterOfferPrice = targetOffer.getCounter().getBidPx().doubleValue();
                if (counterOfferPrice < myPrice) {
                    // price is too low to accept
                    logger.debug("Counter-offer price is too low to accept: counterOfferPrice={}, counterOfferSide={}, baseAsset={}, quoteAsset={}, reference={}, myPrice={}",
                            counterOfferPrice, counterOfferSide, targetRfq.getBaseAsset(), targetRfq.getQuoteAsset(), reference, myPrice);
                    return CompletableFuture.completedFuture(null);
                }
                acceptingQty = getRandomQty(targetOffer.getCounter().getMinQty().doubleValue(), targetOffer.getCounter().getBidQty().doubleValue());
            } else {
                acceptingSide = Side.BUY;
                double counterOfferPrice = targetOffer.getCounter().getAskPx().doubleValue();
                if (counterOfferPrice > myPrice) {
                    // price is too high to accept
                    logger.debug("Counter-offer price is too high to accept: counterOfferPrice={}, counterOfferSide={}, baseAsset={}, quoteAsset={}, reference={}, myPrice={}",
                            counterOfferPrice, counterOfferSide, targetRfq.getBaseAsset(), targetRfq.getQuoteAsset(), reference, myPrice);
                    return CompletableFuture.completedFuture(null);
                }
                acceptingQty = getRandomQty(targetOffer.getCounter().getMinQty().doubleValue(), targetOffer.getCounter().getAskQty().doubleValue());
            }

            return apiEngine.acceptCounter(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getCounter().getNonce(),
                    acceptingSide,
                    acceptingQty
            );
        } else {
            return apiEngine.rejectCounter(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getCounter().getNonce()
            );
        }
    }

    private double getReferencePrice(String baseAsset, String quoteAsset) {
        double baseAssetPrice = getRateToUSD(baseAsset);
        double quoteAssetPrice = getRateToUSD(quoteAsset);
        return quoteAssetPrice != 0.0 ? baseAssetPrice / quoteAssetPrice : 0.0;
    }

    private double getRateToUSD(String asset) {
        return Optional.ofNullable(assetMap.get(asset)).map(a -> {
            double rateToUSD = ObjectUtils.defaultIfNull(indexes.get(a.symbol()), 0.0);
            return a.isCrypto() || rateToUSD == 0.0 ?
                    rateToUSD :
                    (1 / rateToUSD);
        }).orElse(0.0);
    }

    private List<RfqEntry> getEditableRfqs() {
        // entries in rfqMap
        // - not done
        // - with no requester
        return getEligibleRfqs(
                true,
                false,
                rfq -> rfq.getStatus() != RfqStatus.DONE
        );
    }

    private List<Pair<RfqEntry, OfferEntry>> getEditableOffers() {
        // entries in rfqMap:
        // - with requester
        // - with (single) offer
        //    - neither in CONFIRMED nor ENDED status
        //    - with no offeror
        Predicate<OfferEntry> offerEntryPredicate = o -> o.getOfferor() == null &&
                o.getOffer() != null &&
                o.getOffer().getStatus() != OfferStatus.CONFIRMED &&
                o.getOffer().getStatus() != OfferStatus.ENDED;
        List<RfqEntry> eligibleRfqPosts = getEligibleRfqsWithOffers(false, true, offerEntryPredicate);
        return eligibleRfqPosts.stream()
                .map(rfq -> Pair.of(rfq, rfq.getOffers().get(0)))
                .toList();
    }

    private Pair<RfqEntry, OfferEntry> getAnswerableOffer() {
        // entries in rfqMap:
        // - with no requester
        // - with offers
        //    - ACTIVE
        //    - with offeror
        Predicate<OfferEntry> offerEntryPredicate = o -> o.getOfferor() != null &&
                o.getOffer() != null &&
                o.getOffer().getStatus() == OfferStatus.ACTIVE;

        List<RfqEntry> eligibleOwnRfqs = getEligibleRfqsWithOffers(true, false, offerEntryPredicate);
        RfqEntry targetRfq = getRandomFromList(eligibleOwnRfqs);
        if (targetRfq == null) {
            return null;
        }
        List<OfferEntry> eligibleOffers = ListUtils.emptyIfNull(targetRfq.getOffers()).stream()
                .filter(offerEntryPredicate)
                .collect(Collectors.toList());
        OfferEntry targetOffer = getRandomFromList(eligibleOffers);
        if (targetOffer == null) {
            return null;
        }
        return Pair.of(targetRfq, targetOffer);
    }

    private Pair<RfqEntry, OfferEntry> getEditableCounterOffer() {
        // entries in rfqMap:
        // - with no requester
        // - with offers
        //   - with offeror
        //   - with offer
        //   - with counter neither in CONFIRMED nor ENDED status
        Predicate<OfferEntry> offerEntryPredicate = o -> o.getOfferor() != null &&
                o.getOffer() != null &&
                o.getCounter() != null &&
                o.getCounter().getStatus() != OfferStatus.CONFIRMED &&
                o.getCounter().getStatus() != OfferStatus.ENDED;
        List<RfqEntry> eligibleOwnRfqs = getEligibleRfqsWithOffers(true, false, offerEntryPredicate);
        RfqEntry targetRfq = getRandomFromList(eligibleOwnRfqs);
        if (targetRfq == null) {
            return null;
        }
        List<OfferEntry> eligibleOffers = ListUtils.emptyIfNull(targetRfq.getOffers()).stream()
                .filter(offerEntryPredicate)
                .collect(Collectors.toList());
        OfferEntry targetOffer = getRandomFromList(eligibleOffers);
        if (targetOffer == null) {
            return null;
        }
        return Pair.of(targetRfq, targetOffer);
    }

    private Pair<RfqEntry, OfferEntry> getAnswerableCounterOffer() {
        // entries in rfqMap:
        // - with requester
        // - with (single) offer:
        //    - with no offeror
        //    - with offer
        //    - with ACTIVE counter
        Predicate<OfferEntry> offerEntryPredicate = o -> o.getOfferor() == null &&
                o.getOffer() != null &&
                o.getCounter() != null &&
                o.getCounter().getStatus() == OfferStatus.ACTIVE;
        List<RfqEntry> eligibleRfqPosts = getEligibleRfqsWithOffers(false, false, offerEntryPredicate);
        RfqEntry targetRfq = getRandomFromList(eligibleRfqPosts);
        if (targetRfq == null) {
            return null;
        }
        OfferEntry targetOffer = targetRfq.getOffers().get(0);
        return Pair.of(targetRfq, targetOffer);
    }

    private boolean isCancellable(RfqEntry rfqEntry) {
        // only active rfq is cancellable
        return rfqEntry != null &&
                rfqEntry.getStatus() == RfqStatus.ACTIVE &&
                // in order to avoid cancelling expiring RFQ
                rfqEntry.getExpiryTime() - Instant.now().getEpochSecond() > 1;
    }

    private boolean isCancellable(OfferDetails offerDetails) {
        // only active offer/counter is cancellable
        return offerDetails != null &&
                offerDetails.getStatus() == OfferStatus.ACTIVE &&
                // in order to avoid cancelling expiring offers
                offerDetails.getExpiryTime() - Instant.now().getEpochSecond() > 1;
    }

    private List<RfqEntry> getEligibleRfqs(boolean isOwn, boolean activeOnly, Predicate<RfqEntry> predicate) {
        return rfqMap.values().stream()
                .filter(rfq -> supportedSettlementMethods.contains(rfq.getSettlementMethod()) &&
                        isOwn == (rfq.getRequester() == null) &&
                        (!activeOnly || rfq.getStatus() == RfqStatus.ACTIVE) &&
                        (predicate == null || predicate.test(rfq))
                )
                .toList();
    }

    private List<RfqEntry> getEligibleRfqsWithOffers(boolean isOwn, boolean activeOnly, Predicate<OfferEntry> offerEntryPredicate) {
        return getEligibleRfqs(
                isOwn,
                activeOnly,
                rfq -> CollectionUtils.isNotEmpty(rfq.getOffers()) &&
                        rfq.getOffers()
                                .stream()
                                .anyMatch(offerEntryPredicate)
        );
    }
}
