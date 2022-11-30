package com.busywhale.busybot.util;

import com.busywhale.busybot.model.Asset;
import com.busywhale.busybot.model.Side;
import org.apache.commons.collections4.ListUtils;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BotUtils {
    private static final Random random = new Random(System.nanoTime());

    public static <K, T> Map<K, T> convertToMap(List<T> offers, Function<T, K> keyFunc) {
        return ListUtils.emptyIfNull(offers)
                .stream()
                .collect(Collectors.toMap(keyFunc::apply, Function.identity()));
    }

    public static Asset getRandomAsset(List<Asset> assets, boolean cryptoOnly, String skipSymbol) {
        Asset asset = null;
        while (asset == null ||
                (cryptoOnly && !asset.isCrypto()) ||
                (skipSymbol != null && skipSymbol.equals(asset.getSymbol()))
        ) {
            asset = assets.get(random.nextInt(assets.size()));
        }
        return asset;
    }

    public static Side getRandomSide(boolean includeBoth) {
        return Side.values()[random.nextInt(includeBoth ? 3 : 2)];
    }

    public static int getRandomTtl() {
        return 300 + 60 * random.nextInt(10);
    }

    public static double getRandomPrice(double reference, Side side) {
        double maxRange = reference * 0.1;
        double delta = random.nextDouble() * maxRange * (side == Side.BUY ? -1 : 1);
        return Math.max(1 / Math.pow(10d, 2d), roundToNearest(reference + delta, 2));
    }

    public static double getRandomQty(Double bound) {
        return Math.max(1, roundToNearest(random.nextDouble() * (bound != null ? bound : 100.0), 2));
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
}
