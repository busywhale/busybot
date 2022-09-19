package com.busywhale.busybot.model;

import java.math.BigDecimal;

public class ConfirmDetails {
    private Initiator by;
    private ConfirmSide side;
    private BigDecimal px;
    private BigDecimal qty;
    private Integer nonce;
    private Long createTime;

    public Initiator getBy() {
        return by;
    }

    public void setBy(Initiator by) {
        this.by = by;
    }

    public ConfirmSide getSide() {
        return side;
    }

    public void setSide(ConfirmSide side) {
        this.side = side;
    }

    public BigDecimal getPx() {
        return px;
    }

    public void setPx(BigDecimal px) {
        this.px = px;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public Integer getNonce() {
        return nonce;
    }

    public void setNonce(Integer nonce) {
        this.nonce = nonce;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
