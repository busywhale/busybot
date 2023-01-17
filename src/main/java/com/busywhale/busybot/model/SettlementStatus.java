package com.busywhale.busybot.model;

public enum SettlementStatus {
    PENDING_ACCEPT,
    PENDING_COUNTERPARTY_ACCEPT,
    REJECTED,
    REJECTED_BY_COUNTERPARTY,
    VOID,
    PENDING_BOTH_SETTLE,
    PENDING_SETTLE,
    PENDING_COUNTERPARTY_SETTLE,
    SETTLED,
    UNGROUPED,
    ;
}
