package br.com.pedrodalben.easyvip.delivery;

/** Explicit delivery semantics; there is no ambiguous "exactly once" policy. */
public enum DeliveryPolicy {
    ONCE,
    ONCE_PER_GRANT,
    ONCE_PER_DAY,
    ONCE_PER_PERIOD,
    ON_JOIN,
    ON_GROUP_JOIN,
    MANUAL_CLAIM
}
