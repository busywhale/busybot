package com.busywhale.busybot.model;

import java.math.BigDecimal;

public class OfferDetails {
    private BigDecimal bidPx;
    private BigDecimal bidQty;
    private BigDecimal askPx;
    private BigDecimal askQty;
    private Long ttl;
    private OfferStatus status;
    private Integer nonce;
    private Long expiryTime;
    private Long updateTime;
    private Long createTime;

    public BigDecimal getBidPx() {
        return bidPx;
    }

    public void setBidPx(BigDecimal bidPx) {
        this.bidPx = bidPx;
    }

    public BigDecimal getBidQty() {
        return bidQty;
    }

    public void setBidQty(BigDecimal bidQty) {
        this.bidQty = bidQty;
    }

    public BigDecimal getAskPx() {
        return askPx;
    }

    public void setAskPx(BigDecimal askPx) {
        this.askPx = askPx;
    }

    public BigDecimal getAskQty() {
        return askQty;
    }

    public void setAskQty(BigDecimal askQty) {
        this.askQty = askQty;
    }

    public Long getTtl() {
        return ttl;
    }

    public void setTtl(Long ttl) {
        this.ttl = ttl;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public void setStatus(OfferStatus status) {
        this.status = status;
    }

    public Integer getNonce() {
        return nonce;
    }

    public void setNonce(Integer nonce) {
        this.nonce = nonce;
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
}
