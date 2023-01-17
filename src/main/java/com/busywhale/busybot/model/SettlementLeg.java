package com.busywhale.busybot.model;

import java.math.BigDecimal;

public class SettlementLeg {
    private String asset;
    private BigDecimal qty;
    private BigDecimal feeQty;

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

    public BigDecimal getFeeQty() {
        return feeQty;
    }

    public void setFeeQty(BigDecimal feeQty) {
        this.feeQty = feeQty;
    }
}
