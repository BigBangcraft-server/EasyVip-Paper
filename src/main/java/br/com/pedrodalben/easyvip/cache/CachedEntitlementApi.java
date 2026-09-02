package br.com.pedrodalben.easyvip.cache;

import br.com.pedrodalben.easyvip.api.BenefitService;
import br.com.pedrodalben.easyvip.api.EasyVipApi;
import br.com.pedrodalben.easyvip.api.EntitlementService;
import br.com.pedrodalben.easyvip.api.EffectiveEntitlementView;
import br.com.pedrodalben.easyvip.api.PlayerEntitlementView;
import br.com.pedrodalben.easyvip.api.ScopeContext;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Cache-first API adapter. A miss delegates to SQL-backed entitlement evaluation. */
public final class CachedEntitlementApi implements EasyVipApi {
    private final EasyVipApi delegate;
    private final EntitlementCache cache;
    private final Executor asyncExecutor;
    private final BenefitService cachedBenefits = new BenefitService() {
        @Override
        public PlayerEntitlementView player(UUID playerUuid, ScopeContext context) {
            return CachedEntitlementApi.this.player(playerUuid, context);
        }
    };

    public CachedEntitlementApi(EasyVipApi delegate, EntitlementCache cache) {
        this(delegate, cache, Runnable::run);
    }

    public CachedEntitlementApi(EasyVipApi delegate, EntitlementCache cache, Executor asyncExecutor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
    }

    @Override
    public EntitlementService entitlements() {
        return this::player;
    }

    @Override
    public BenefitService benefits() {
        return cachedBenefits;
    }

    @Override
    public PlayerEntitlementView player(UUID playerUuid, ScopeContext context) {
        return effective(playerUuid, context).capabilities();
    }

    @Override
    public EffectiveEntitlementView effective(UUID playerUuid, ScopeContext context) {
        return cache.get(playerUuid, context, () -> delegate.effective(playerUuid, context));
    }

    /** Cold-cache SQL work can be kept off a Paper/Folia event thread. */
    public CompletionStage<PlayerEntitlementView> playerAsync(UUID playerUuid, ScopeContext context) {
        return CompletableFuture.supplyAsync(() -> player(playerUuid, context), asyncExecutor);
    }

    public void invalidate(UUID playerUuid, long aggregateVersion) {
        cache.invalidate(playerUuid, aggregateVersion);
    }

    public EntitlementCache cache() { return cache; }
}
