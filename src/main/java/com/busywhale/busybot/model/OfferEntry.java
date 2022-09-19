package com.busywhale.busybot.model;

public class OfferEntry {
    private String id;
    private UserDetails offeror;
    private OfferDetails offer;
    private OfferDetails counter;
    private ConfirmDetails confirm;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public UserDetails getOfferor() {
        return offeror;
    }

    public void setOfferor(UserDetails offeror) {
        this.offeror = offeror;
    }

    public OfferDetails getOffer() {
        return offer;
    }

    public void setOffer(OfferDetails offer) {
        this.offer = offer;
    }

    public OfferDetails getCounter() {
        return counter;
    }

    public void setCounter(OfferDetails counter) {
        this.counter = counter;
    }

    public ConfirmDetails getConfirm() {
        return confirm;
    }

    public void setConfirm(ConfirmDetails confirm) {
        this.confirm = confirm;
    }
}
