package br.com.pedrodalben.easyvip.api;

/** Stable names for events exchanged by future network adapters. */
public enum DomainEventType {
    ENTITLEMENT_GRANTED,
    ENTITLEMENT_EXTENDED,
    ENTITLEMENT_REVOKED,
    ENTITLEMENT_EXPIRED,
    BENEFIT_CHANGED,
    PACKAGE_CLAIMED,
    KEY_REDEEMED
}
