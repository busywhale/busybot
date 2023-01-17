package com.busywhale.busybot.model;

import java.math.BigDecimal;
import java.util.List;

public class TradeEntry {
    private String id;
    private List<TradeLeg> legs;
    private long tradeTime;
    private BigDecimal margin;
    private TradeStatus status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<TradeLeg> getLegs() {
        return legs;
    }

    public void setLegs(List<TradeLeg> legs) {
        this.legs = legs;
    }

    public long getTradeTime() {
        return tradeTime;
    }

    public void setTradeTime(long tradeTime) {
        this.tradeTime = tradeTime;
    }

    public BigDecimal getMargin() {
        return margin;
    }

    public void setMargin(BigDecimal margin) {
        this.margin = margin;
    }

    public TradeStatus getStatus() {
        return status;
    }

    public void setStatus(TradeStatus status) {
        this.status = status;
    }
}
