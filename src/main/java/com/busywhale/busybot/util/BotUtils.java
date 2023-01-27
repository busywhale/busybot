package com.busywhale.busybot.util;

import com.busywhale.busybot.model.Asset;
import com.busywhale.busybot.model.Side;
import org.apache.commons.collections4.ListUtils;
import org.springframework.lang.NonNull;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BotUtils {
    private static final Random random = new Random(System.nanoTime());
    public static final int MIN_RFQ_TTL = 300;
    public static final int MIN_OFFER_TTL = 60;
    private static final int MAX_PRICE_DP = 6;
    private static final int MAX_QTY_DP = 2;

    public static <K, T> Map<K, T> convertToMap(List<T> offers, Function<T, K> keyFunc) {
        return ListUtils.emptyIfNull(offers)
                .stream()
                .collect(Collectors.toMap(keyFunc::apply, Function.identity()));
    }

    public static Asset getRandomAsset(List<Asset> assets, boolean cryptoOnly, String skipSymbol) {
        Asset asset = null;
        while (asset == null ||
                (cryptoOnly && !asset.isCrypto()) ||
                (skipSymbol != null && skipSymbol.equals(asset.symbol()))
        ) {
            asset = assets.get(random.nextInt(assets.size()));
        }
        return asset;
    }

    public static Side getRandomSide(boolean includeBoth) {
        return Side.values()[random.nextInt(includeBoth ? 3 : 2)];
    }

    public static int getSuggestedTtl(boolean isOffer, boolean useMin) {
        return (isOffer ? MIN_OFFER_TTL : MIN_RFQ_TTL) + (useMin ? 0 : (60 * random.nextInt(10)));
    }

    public static double getRandomPrice(double reference, double width, Side side) {
        double maxRange = reference * width;
        double delta = getRandom(0.05, 1.0) * maxRange * (side == Side.BUY ? -1 : 1);
        return Math.max(1 / Math.pow(10d, MAX_PRICE_DP), roundToNearest(reference + delta, MAX_PRICE_DP));
    }

    public static double getRandomQty(Double bound) {
        double effectiveBound = bound != null ? bound : 10.0;
        return Math.min(effectiveBound, Math.max(1 / Math.pow(10d, MAX_QTY_DP), roundToNearest(getRandom(0.5, 1.0) * effectiveBound, MAX_QTY_DP)));
    }

    public static <T> T getRandomFromList(List<T> list) {
        if (CollectionUtils.isEmpty(list)) {
            return null;
        }
        return list.get(random.nextInt(list.size()));
    }

    public static double roundToNearest(double d, int dp) {
        double multiplier = Math.pow(10, dp);
        return Math.round(multiplier * d) / multiplier;
    }

    public static boolean toss(double target) {
        return target > random.nextDouble();
    }

    private static double getRandom(double min, double max) {
        if (max <= min) {
            return random.nextDouble();
        }
        return random.nextDouble() * (max - min) + min;
    }
}
