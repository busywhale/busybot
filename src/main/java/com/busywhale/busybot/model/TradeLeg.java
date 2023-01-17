package com.busywhale.busybot.model;

import java.math.BigDecimal;

public class TradeLeg {
    private String asset;
    private BigDecimal qty;
    private BigDecimal fee;

    public String getAsset() {
        return asset;
    }

    public void setAsset(String asset) {
        this.asset = asset;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }
}
