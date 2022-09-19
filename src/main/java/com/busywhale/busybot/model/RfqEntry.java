package com.busywhale.busybot.model;

import java.math.BigDecimal;
import java.util.List;

public class RfqEntry {
    private String id;
    private String longAsset;
    private String shortAsset;
    private Side side;
    private BigDecimal qty;
    private Integer ttl;
    private RfqStatus status;
    private Long expiryTime;
    private Long updateTime;
    private Long createTime;
    private UserDetails requester;
    private List<OfferEntry> offers;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLongAsset() {
        return longAsset;
    }

    public void setLongAsset(String longAsset) {
        this.longAsset = longAsset;
    }

    public String getShortAsset() {
        return shortAsset;
    }

    public void setShortAsset(String shortAsset) {
        this.shortAsset = shortAsset;
    }

    public Side getSide() {
        return side;
    }

    public void setSide(Side side) {
        this.side = side;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public Integer getTtl() {
        return ttl;
    }

    public void setTtl(Integer ttl) {
        this.ttl = ttl;
    }

    public RfqStatus getStatus() {
        return status;
    }

    public void setStatus(RfqStatus status) {
        this.status = status;
    }

    public Long getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(Long expiryTime) {
        this.expiryTime = expiryTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public UserDetails getRequester() {
        return requester;
    }

    public void setRequester(UserDetails requester) {
        this.requester = requester;
    }

    public List<OfferEntry> getOffers() {
        return offers;
    }

    public void setOffers(List<OfferEntry> offers) {
        this.offers = offers;
    }
}
