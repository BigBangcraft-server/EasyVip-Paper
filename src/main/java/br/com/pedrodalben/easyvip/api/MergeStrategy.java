package br.com.pedrodalben.easyvip.api;

/** Deterministic merge rule for multiple grants of the same capability. */
public enum MergeStrategy {
    OR,
    MAX,
    HIGHEST_PRIORITY
}
