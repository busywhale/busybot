package com.busywhale.busybot.model;

import java.util.List;

public class SettlementEntry {
    private String id;
    private List<TradeEntry> trades;
    private List<SettlementLeg> legs;
    private long createTime;
    private GroupedBy groupBy;
    private SettlementStatus status;
    private String rejectReason;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<TradeEntry> getTrades() {
        return trades;
    }

    public void setTrades(List<TradeEntry> trades) {
        this.trades = trades;
    }

    public List<SettlementLeg> getLegs() {
        return legs;
    }

    public void setLegs(List<SettlementLeg> legs) {
        this.legs = legs;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public GroupedBy getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(GroupedBy groupBy) {
        this.groupBy = groupBy;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public void setStatus(SettlementStatus status) {
        this.status = status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }
}
