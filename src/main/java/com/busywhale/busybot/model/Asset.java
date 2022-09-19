package com.busywhale.busybot.model;

public class Asset {
    private final String symbol;
    private final boolean isCrypto;

    public Asset(String symbol, boolean isCrypto) {
        this.symbol = symbol;
        this.isCrypto = isCrypto;
    }

    public String getSymbol() {
        return symbol;
    }

    public boolean isCrypto() {
        return isCrypto;
    }
}
