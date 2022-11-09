package com.busywhale.busybot.component;

import com.busywhale.busybot.model.*;
import com.busywhale.busybot.websocket.WebSocketMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.busywhale.busybot.util.BotUtils.*;

@Component
public class BotEngine extends StompSessionHandlerAdapter {
    private static final Logger logger = LogManager.getLogger(BotEngine.class);
    private static final int TIMEOUT = 5;
    private static final String INDEX_SNAPSHOTS_DESTINATION = "/topic/public/indexes";
    private static final String RFQ_ADS_DESTINATION = "/user/queue/rfq/ads";
    private static final String MY_RFQ_DESTINATION = "/user/queue/rfq/my";
    private static final String PIVOT_CURRENCY = "USD";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Asset> assets = new ArrayList<>();
    private final Map<String, Asset> assetMap = new HashMap<>();
    private final Map<String, Double> indexes = new ConcurrentHashMap<>();
    private final Map<String, RfqEntry> rfqMap = new ConcurrentHashMap<>();
    private final List<Triple<Supplier<CompletableFuture<Void>>, Double, String>> actions = new ArrayList<>();

    @Autowired
    private ApiEngine apiEngine;

    @Autowired
    private ApiKeySigner signer;

    @Autowired
    private WebSocketStompClient stompClient;

    @Value("${busywhale.websocket.url}")
    private String websocketUrl;

    @Value("${bot.action.create_rfq.chance:0.0}")
    private double tossCreateRfq;

    @Value("${bot.action.modify_rfq.chance:0.0}")
    private double tossModifyRfq;

    @Value("${bot.action.modify_rfq.update.chance:0.5}")
    private double tossModifyRfqUpdate;

    @Value("${bot.action.create_offer.chance:0.0}")
    private double tossCreateOffer;

    @Value("${bot.action.modify_offer.chance:0.0}")
    private double tossModifyOffer;

    @Value("${bot.action.modify_offer.update.chance:0.5}")
    private double tossModifyOfferUpdate;

    @Value("${bot.action.answer_offer.chance:0.0}")
    private double tossAnswerOffer;

    @Value("${bot.action.answer_offer.accept.chance:0.5}")
    private double tossAnswerOfferAccept;

    @Value("${bot.action.create_counter.chance:0.0}")
    private double tossCreateCounter;

    @Value("${bot.action.modify_counter.chance:0.0}")
    private double tossModifyCounter;

    @Value("${bot.action.modify_counter.update.chance:0.5}")
    private double tossModifyCounterUpdate;

    @Value("${bot.action.answer_counter.chance:0.0}")
    private double tossAnswerCounter;

    @Value("${bot.action.answer_counter.accept.chance:0.5}")
    private double tossAnswerCounterAccept;

    @Value("${external.rates.fiat.url:-}")
    private String externalFiatRatesUrl;

    private boolean isShuttingDown = false;
    private boolean isConnected = false;
    private boolean isRfqAdSnapshotReady = false;
    private boolean isMyRfqSnapshotReady = false;
    private final List<String> pendingRfqAdPayloads = new ArrayList<>();
    private final List<String> pendingMyRfqPayloads = new ArrayList<>();

    @PostConstruct
    public void postConstruct() {
        initData();
        initActionList();
        initWebSocketConnection();
    }

    @PreDestroy
    public void preDestroy() {
        isShuttingDown = true;
    }

    @Scheduled(fixedDelayString = "${bot.action.interval:10000}")
    public void doAction() {
        if (!isConnected) {
            // no performing any action if it's not connected
            return;
        }
        actions.forEach(action -> {
            if (toss(action.getMiddle())) {
                logger.debug("Performing action: {}", action.getRight());
                try {
                    action.getLeft().get().get(TIMEOUT, TimeUnit.SECONDS);
                } catch (ExecutionException | InterruptedException | TimeoutException e) {
                    logger.error("Timed out when {}", action.getRight());
                }
            } else {
                logger.debug("Skipping: {}", action.getRight());
            }
        });
    }

    @Override
    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
        logger.info("New WebSocket session established: {}", session.getSessionId());
        isConnected = true;
        session.subscribe(INDEX_SNAPSHOTS_DESTINATION, this);
        session.subscribe(RFQ_ADS_DESTINATION, this);
        session.subscribe(MY_RFQ_DESTINATION, this);

        apiEngine.getMyRfqs()
                .thenAccept(list -> {
                    logger.info("Received {} own RFQs", list.size());
                    list.forEach(this::handleMyRfq);
                    while (!pendingMyRfqPayloads.isEmpty()) {
                        handleMyRfqFeed(pendingMyRfqPayloads.remove(0));
                    }
                    isMyRfqSnapshotReady = true;
                });
        apiEngine.getRfqAds()
                .thenAccept(list -> {
                    logger.info("Received {} RFQ ads", list.size());
                    list.forEach(this::handleRfqAd);
                    while (!pendingRfqAdPayloads.isEmpty()) {
                        handleRfqAdsFeed(pendingRfqAdPayloads.remove(0));
                    }
                    isRfqAdSnapshotReady = true;
                });
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
        logger.error("WebSocket transport error ({}): {}", session.getSessionId(), exception.getMessage());
        handleError();
    }

    @Override
    public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
        logger.error("Error in WebSocket connection", exception);
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
        WebSocketMessage message = (WebSocketMessage) payload;

        if (message.getCommand() == StompCommand.MESSAGE) {
            String destination = headers.getDestination();
            if (destination == null) {
                return;
            }
            switch (destination) {
                case INDEX_SNAPSHOTS_DESTINATION:
                    handleIndexSnapshotFeed(message.getMessage());
                    break;
                case RFQ_ADS_DESTINATION:
                    if (!isRfqAdSnapshotReady) {
                        pendingRfqAdPayloads.add(message.getMessage());
                    } else {
                        handleRfqAdsFeed(message.getMessage());
                    }
                    break;
                case MY_RFQ_DESTINATION:
                    if (!isMyRfqSnapshotReady) {
                        pendingMyRfqPayloads.add(message.getMessage());
                    } else {
                        handleMyRfqFeed(message.getMessage());
                    }
                    break;
            }
        } else if (message.getCommand() == StompCommand.ERROR) {
            logger.error("Stomp ERROR: {}", message.getMessage());
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
        loadFiatRates();
        try {
            CompletableFuture.allOf(
                    apiEngine.getAssets()
                            .thenAccept(list -> {
                                assets.addAll(list);
                                list.forEach(a -> assetMap.put(a.getSymbol(), a));
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
        logger.debug("Received index snapshot: {}", payload);

        try {
            JsonNode node = mapper.readTree(payload);
            JsonNode indexesNode = node.path("indexes").path("indexes");
            if (!indexesNode.isMissingNode()) {
                indexesNode.fields()
                        .forEachRemaining(e -> indexes.put(e.getKey(), e.getValue().doubleValue()));
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse index snapshot data", e);
        }
    }

    private void handleRfqAdsFeed(String payload) {
        logger.debug("Received RFQ ad feed: {}", payload);
        try {
            JsonNode node = mapper.readTree(payload);
            String event = node.get("event").asText();

            if ("EVENT_RFQ_ADS_UPDATE".equals(event)) {
                JsonNode rfqNode = node.path("rfq");
                if (!rfqNode.isMissingNode()) {
                    RfqEntry rfqEntry = mapper.treeToValue(rfqNode, RfqEntry.class);
                    handleRfqAd(rfqEntry);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to parse RFQ ads data", e);
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

    private void handleRfqAd(RfqEntry rfqEntry) {
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

    private void mergeRfqDetails(RfqEntry rfqEntry, RfqEntry oldRfqEntry) {
        Optional.ofNullable(rfqEntry.getLongAsset()).ifPresent(oldRfqEntry::setLongAsset);
        Optional.ofNullable(rfqEntry.getShortAsset()).ifPresent(oldRfqEntry::setShortAsset);
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
        Optional.ofNullable(offerDetails.getBidPx()).ifPresent(oldOfferDetails::setBidPx);
        Optional.ofNullable(offerDetails.getBidQty()).ifPresent(oldOfferDetails::setBidQty);
        Optional.ofNullable(offerDetails.getAskPx()).ifPresent(oldOfferDetails::setAskPx);
        Optional.ofNullable(offerDetails.getAskQty()).ifPresent(oldOfferDetails::setAskQty);
        Optional.ofNullable(offerDetails.getTtl()).ifPresent(oldOfferDetails::setTtl);
        Optional.ofNullable(offerDetails.getCreateTime()).ifPresent(oldOfferDetails::setCreateTime);
        Optional.ofNullable(offerDetails.getUpdateTime()).ifPresent(oldOfferDetails::setUpdateTime);
        Optional.ofNullable(offerDetails.getExpiryTime()).ifPresent(oldOfferDetails::setExpiryTime);
    }

    private CompletableFuture<Void> createRfq() {
        String longAsset = getRandomAsset(assets, true, null).getSymbol();
        String shortAsset = getRandomAsset(assets, false, longAsset).getSymbol();
        Side side = getRandomSide(true);
        int ttl = getRandomTtl();
        double qty = getRandomQty(null);

        return apiEngine.createRfq(
                longAsset,
                shortAsset,
                side,
                ttl,
                qty
        );
    }

    private CompletableFuture<Void> updateRfq() {
        RfqEntry targetRfq = getEditableRfq();
        if (targetRfq == null) {
            logger.info("No eligible RFQ to update/cancel");
            return CompletableFuture.completedFuture(null);
        }
        if (toss(tossModifyRfqUpdate)) {
            return apiEngine.updateRfq(
                    targetRfq.getId(),
                    getRandomTtl()
            );
        } else if (isCancellable(targetRfq)) {
            return apiEngine.cancelRfq(
                    targetRfq.getId()
            );
        } else {
            logger.info("Selected RFQ is not cancellable.");
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> createOffer() {
        // entries in rfqMap:
        // - active
        // - with requester
        // - with no previous offers
        List<RfqEntry> eligibleRfqs = rfqMap.values().stream()
                .filter(rfq -> rfq.getStatus() == RfqStatus.ACTIVE &&
                        rfq.getRequester() != null &&
                        ListUtils.emptyIfNull(rfq.getOffers())
                                .stream()
                                .noneMatch(offerEntry -> offerEntry.getOfferor() == null)
                )
                .collect(Collectors.toList());
        RfqEntry targetRfq = getRandomFromList(eligibleRfqs);
        if (targetRfq == null) {
            logger.info("No eligible RFQ for creating offer");
            return CompletableFuture.completedFuture(null);
        }
        double reference = getReferencePrice(targetRfq.getLongAsset(), targetRfq.getShortAsset());
        if (reference == 0.0) {
            logger.warn("No reference price for creating offer: longAsset={}, shortAsset={}", targetRfq.getLongAsset(), targetRfq.getShortAsset());
            return CompletableFuture.completedFuture(null);
        }
        Side offerSide;
        switch (targetRfq.getSide()) {
            case BUY:
                offerSide = Side.SELL;
                break;
            case SELL:
                offerSide = Side.BUY;
                break;
            default:
                offerSide = getRandomSide(true);
                break;
        }
        return apiEngine.createOffer(
                targetRfq.getId(),
                getRandomTtl(),
                offerSide != Side.SELL ? getRandomPrice(reference, Side.BUY) : null,
                offerSide != Side.SELL ? getRandomQty(targetRfq.getQty().doubleValue()) : null,
                offerSide != Side.BUY ? getRandomPrice(reference, Side.SELL) : null,
                offerSide != Side.BUY ? getRandomQty(targetRfq.getQty().doubleValue()) : null
        );
    }

    private CompletableFuture<Void> updateOffer() {
        Pair<RfqEntry, OfferEntry> pair = getEditableOffer();
        if (pair == null) {
            logger.info("No eligible offer to update");
            return CompletableFuture.completedFuture(null);
        }
        RfqEntry targetRfq = pair.getLeft();
        OfferEntry targetOffer = pair.getRight();
        if (toss(tossModifyOfferUpdate)) {
            double reference = getReferencePrice(targetRfq.getLongAsset(), targetRfq.getShortAsset());
            if (reference == 0.0) {
                logger.warn("No reference price for updating offer: longAsset={}, shortAsset={}", targetRfq.getLongAsset(), targetRfq.getShortAsset());
                return CompletableFuture.completedFuture(null);
            }
            Side offerSide;
            switch (targetRfq.getSide()) {
                case BUY:
                    offerSide = Side.SELL;
                    break;
                case SELL:
                    offerSide = Side.BUY;
                    break;
                default:
                    offerSide = getRandomSide(true);
                    break;
            }
            return apiEngine.updateOffer(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getOffer().getNonce(),
                    getRandomTtl(),
                    offerSide != Side.SELL ? getRandomPrice(reference, Side.BUY) : null,
                    offerSide != Side.SELL ? getRandomQty(targetRfq.getQty().doubleValue()) : null,
                    offerSide != Side.BUY ? getRandomPrice(reference, Side.SELL) : null,
                    offerSide != Side.BUY ? getRandomQty(targetRfq.getQty().doubleValue()) : null
            );
        } else if (isCancellable(targetOffer.getOffer())) {
            return apiEngine.cancelOffer(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getOffer().getNonce()
            );
        } else {
            logger.debug("Selected offer is not cancellable.");
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> answerOffer() {
        Pair<RfqEntry, OfferEntry> pair = getAnswerableOffer();
        if (pair == null) {
            logger.info("No offer to answer");
            return CompletableFuture.completedFuture(null);
        }
        RfqEntry targetRfq = pair.getLeft();
        OfferEntry targetOffer = pair.getRight();
        if (toss(tossAnswerOfferAccept)) {
            Side acceptingSide;
            if (targetOffer.getOffer().getBidQty() != null && targetOffer.getOffer().getAskQty() != null) {
                acceptingSide = getRandomSide(false);
            } else if (targetOffer.getOffer().getBidQty() != null) {
                acceptingSide = Side.SELL;
            } else if (targetOffer.getOffer().getAskQty() != null) {
                acceptingSide = Side.BUY;
            } else {
                return CompletableFuture.completedFuture(null);
            }

            return apiEngine.acceptOffer(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getOffer().getNonce(),
                    acceptingSide,
                    getRandomQty(acceptingSide == Side.SELL ?
                            targetOffer.getOffer().getBidQty().doubleValue() :
                            targetOffer.getOffer().getAskQty().doubleValue()
                    )
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
        List<RfqEntry> eligibleRfqs = rfqMap.values().stream()
                .filter(rfq -> rfq.getRequester() == null &&
                        CollectionUtils.isNotEmpty(rfq.getOffers()) &&
                        rfq.getOffers()
                                .stream()
                                .anyMatch(offerEntryPredicate)
                )
                .collect(Collectors.toList());
        RfqEntry targetRfq = getRandomFromList(eligibleRfqs);
        if (targetRfq == null) {
            logger.info("No eligible RFQ for creating counter-offer");
            return CompletableFuture.completedFuture(null);
        }
        List<OfferEntry> eligibleOffers = ListUtils.emptyIfNull(targetRfq.getOffers())
                .stream()
                .filter(offerEntryPredicate)
                .collect(Collectors.toList());
        OfferEntry targetOffer = getRandomFromList(eligibleOffers);
        if (targetOffer == null) {
            logger.info("No eligible offer for creating counter-offer");
            return CompletableFuture.completedFuture(null);
        }
        double reference = getReferencePrice(targetRfq.getLongAsset(), targetRfq.getShortAsset());
        if (reference == 0.0) {
            logger.warn("No reference price for sending counter-offer: longAsset={}, shortAsset={}", targetRfq.getLongAsset(), targetRfq.getShortAsset());
            return CompletableFuture.completedFuture(null);
        }
        return apiEngine.createCounter(
                targetRfq.getId(),
                targetOffer.getId(),
                getRandomTtl(),
                targetOffer.getOffer().getAskPx() != null ? getRandomPrice(reference, Side.BUY) : null,
                targetOffer.getOffer().getAskQty() != null ? getRandomQty(targetOffer.getOffer().getAskQty().doubleValue()) : null,
                targetOffer.getOffer().getBidPx() != null ? getRandomPrice(reference, Side.SELL) : null,
                targetOffer.getOffer().getBidQty() != null ? getRandomQty(targetOffer.getOffer().getBidQty().doubleValue()) : null
        );
    }

    private CompletableFuture<Void> updateCounter() {
        Pair<RfqEntry, OfferEntry> pair = getEditableCounterOffer();
        if (pair == null) {
            logger.info("No eligible counter-offer to update");
            return CompletableFuture.completedFuture(null);
        }
        RfqEntry targetRfq = pair.getLeft();
        OfferEntry targetOffer = pair.getRight();
        if (toss(tossModifyCounterUpdate)) {
            double reference = getReferencePrice(targetRfq.getLongAsset(), targetRfq.getShortAsset());
            if (reference == 0.0) {
                logger.warn("No reference price for sending counter-offer: longAsset={}, shortAsset={}", targetRfq.getLongAsset(), targetRfq.getShortAsset());
                return CompletableFuture.completedFuture(null);
            }
            return apiEngine.updateCounter(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getCounter().getNonce(),
                    getRandomTtl(),
                    targetOffer.getOffer().getAskPx() != null ? getRandomPrice(reference, Side.BUY) : null,
                    targetOffer.getOffer().getAskQty() != null ? getRandomQty(targetOffer.getOffer().getAskQty().doubleValue()) : null,
                    targetOffer.getOffer().getBidPx() != null ? getRandomPrice(reference, Side.SELL) : null,
                    targetOffer.getOffer().getBidQty() != null ? getRandomQty(targetOffer.getOffer().getBidQty().doubleValue()) : null
            );
        } else if (isCancellable(targetOffer.getCounter())) {
            return apiEngine.cancelCounter(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getCounter().getNonce()
            );
        } else {
            logger.debug("Selected counter-offer is not cancellable.");
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> answerCounter() {
        Pair<RfqEntry, OfferEntry> pair = getAnswerableCounterOffer();
        if (pair == null) {
            logger.info("No eligible counter-offer to answer");
            return CompletableFuture.completedFuture(null);
        }
        RfqEntry targetRfq = pair.getLeft();
        OfferEntry targetOffer = pair.getRight();
        if (toss(tossAnswerCounterAccept)) {
            Side acceptingSide;
            if (targetOffer.getCounter().getBidQty() != null && targetOffer.getCounter().getAskQty() != null) {
                acceptingSide = getRandomSide(false);
            } else if (targetOffer.getCounter().getBidQty() != null) {
                acceptingSide = Side.SELL;
            } else if (targetOffer.getCounter().getAskQty() != null) {
                acceptingSide = Side.BUY;
            } else {
                return CompletableFuture.completedFuture(null);
            }
            return apiEngine.acceptCounter(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getCounter().getNonce(),
                    acceptingSide,
                    getRandomQty(acceptingSide == Side.SELL ?
                            targetOffer.getCounter().getBidQty().doubleValue() :
                            targetOffer.getCounter().getAskQty().doubleValue()
                    )
            );
        } else {
            return apiEngine.rejectCounter(
                    targetRfq.getId(),
                    targetOffer.getId(),
                    targetOffer.getCounter().getNonce()
            );
        }
    }

    private double getReferencePrice(String longAsset, String shortAsset) {
        double longAssetPrice = getRateToUSD(longAsset);
        double shortAssetPrice = getRateToUSD(shortAsset);
        return shortAssetPrice != 0.0 ? longAssetPrice / shortAssetPrice : 0.0;
    }

    private double getRateToUSD(String asset) {
        return Optional.ofNullable(assetMap.get(asset)).map(a -> {
            double rateToUSD = ObjectUtils.defaultIfNull(indexes.get(a.getSymbol()), 0.0);
            return a.isCrypto() || rateToUSD == 0.0 ?
                    rateToUSD :
                    (1 / rateToUSD);
        }).orElse(0.0);
    }

    private RfqEntry getEditableRfq() {
        // entries in rfqMap
        // - not done
        // - with no requester
        List<RfqEntry> eligibleRfqs = rfqMap.values().stream()
                .filter(rfq -> rfq.getStatus() != RfqStatus.DONE &&
                        rfq.getRequester() == null
                )
                .collect(Collectors.toList());
        return getRandomFromList(eligibleRfqs);
    }

    private Pair<RfqEntry, OfferEntry> getEditableOffer() {
        // entries in rfqMap:
        // - with requester
        // - with (single) offer
        //    - neither in CONFIRMED nor ENDED status
        //    - with no offeror
        Predicate<OfferEntry> offerEntryPredicate = o -> o.getOfferor() == null &&
                o.getOffer() != null &&
                o.getOffer().getStatus() != OfferStatus.CONFIRMED &&
                o.getOffer().getStatus() != OfferStatus.ENDED;
        List<RfqEntry> eligibleRfqs = rfqMap.values().stream()
                .filter(rfq -> rfq.getRequester() != null &&
                        CollectionUtils.isNotEmpty(rfq.getOffers()) &&
                        rfq.getOffers()
                                .stream()
                                .anyMatch(offerEntryPredicate)
                )
                .collect(Collectors.toList());
        RfqEntry targetRfq = getRandomFromList(eligibleRfqs);
        if (targetRfq == null) {
            return null;
        }
        OfferEntry targetOffer = targetRfq.getOffers().get(0);
        return Pair.of(targetRfq, targetOffer);
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
        List<RfqEntry> eligibleRfqs = rfqMap.values().stream()
                .filter(rfq -> rfq.getRequester() == null &&
                        CollectionUtils.isNotEmpty(rfq.getOffers()) &&
                        rfq.getOffers()
                                .stream()
                                .anyMatch(offerEntryPredicate)
                )
                .collect(Collectors.toList());
        RfqEntry targetRfq = getRandomFromList(eligibleRfqs);
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
        List<RfqEntry> eligibleRfqs = rfqMap.values().stream()
                .filter(rfq -> rfq.getRequester() == null &&
                        CollectionUtils.isNotEmpty(rfq.getOffers()) &&
                        rfq.getOffers()
                                .stream()
                                .anyMatch(offerEntryPredicate)
                )
                .collect(Collectors.toList());
        RfqEntry targetRfq = getRandomFromList(eligibleRfqs);
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
        List<RfqEntry> eligibleRfqs = rfqMap.values().stream()
                .filter(rfq -> rfq.getRequester() != null &&
                        CollectionUtils.isNotEmpty(rfq.getOffers()) &&
                        rfq.getOffers()
                                .stream()
                                .anyMatch(offerEntryPredicate)
                )
                .collect(Collectors.toList());
        RfqEntry targetRfq = getRandomFromList(eligibleRfqs);
        if (targetRfq == null) {
            return null;
        }
        OfferEntry targetOffer = targetRfq.getOffers().get(0);
        return Pair.of(targetRfq, targetOffer);
    }

    private boolean isCancellable(RfqEntry rfqEntry) {
        // only active rfq is cancellable
        return rfqEntry != null &&
                rfqEntry.getStatus() == RfqStatus.ACTIVE;
    }

    private boolean isCancellable(OfferDetails offerDetails) {
        // only active offer/counter is cancellable
        return offerDetails != null &&
                offerDetails.getStatus() == OfferStatus.ACTIVE;
    }
}
