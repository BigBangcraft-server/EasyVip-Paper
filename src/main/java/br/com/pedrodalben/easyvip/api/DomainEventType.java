package br.com.pedrodalben.easyvip.api;

/** Stable names for events exchanged by future network adapters. */
public enum DomainEventType {
    ENTITLEMENT_GRANTED,
    ENTITLEMENT_EXTENDED,
    ENTITLEMENT_UPDATED,
    ENTITLEMENT_REVOKED,
    ENTITLEMENT_EXPIRED,
    BENEFIT_CHANGED,
    CAPABILITIES_CHANGED,
    PACKAGE_CLAIMED,
    KEY_REDEEMED
}
