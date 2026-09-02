package br.com.pedrodalben.easyvip.api;

/** Stable, platform-neutral entry point for future Paper and Velocity adapters. */
public interface EasyVipApi {
    String API_VERSION = "1.0";

    EntitlementService entitlements();

    BenefitService benefits();
}
