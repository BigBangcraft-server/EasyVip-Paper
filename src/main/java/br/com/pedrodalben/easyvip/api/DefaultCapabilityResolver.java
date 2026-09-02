package br.com.pedrodalben.easyvip.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Small, dependency-free resolver used by adapters and domain tests. */
public final class DefaultCapabilityResolver implements CapabilityResolver {
    private final List<CapabilityGrant> grants;

    public DefaultCapabilityResolver(Collection<CapabilityGrant> grants) {
        this.grants = List.copyOf(Objects.requireNonNull(grants, "grants"));
    }

    @Override
    public PlayerEntitlementView resolve(UUID playerUuid, ScopeContext context) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(context, "context");

        Map<String, List<CapabilityGrant>> applicable = grants.stream()
                .filter(grant -> grant.scope().appliesTo(context))
                .collect(Collectors.groupingBy(CapabilityGrant::capability, LinkedHashMap::new, Collectors.toList()));

        Map<String, CapabilityValue> resolved = new LinkedHashMap<>();
        applicable.forEach((capability, values) -> resolved.put(capability, merge(capability, values)));
        return new PlayerEntitlementView(resolved);
    }

    private static CapabilityValue merge(String capability, List<CapabilityGrant> values) {
        List<CapabilityGrant> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.comparingInt(CapabilityGrant::priority).reversed()
                .thenComparing(CapabilityGrant::grantId));
        CapabilityGrant first = ordered.getFirst();
        if (ordered.stream().anyMatch(grant -> grant.mergeStrategy() != first.mergeStrategy())) {
            throw new IllegalArgumentException("Mixed merge strategies for capability " + capability);
        }

        return switch (first.mergeStrategy()) {
            case HIGHEST_PRIORITY -> first.value();
            case OR -> mergeBoolean(capability, ordered);
            case MAX -> mergeNumeric(capability, ordered);
        };
    }

    private static CapabilityValue mergeBoolean(String capability, List<CapabilityGrant> grants) {
        if (grants.stream().anyMatch(grant -> grant.value().kind() != CapabilityValue.Kind.BOOLEAN)) {
            throw incompatible(capability, MergeStrategy.OR);
        }
        return CapabilityValue.of(grants.stream().anyMatch(grant -> grant.value().asBoolean()));
    }

    private static CapabilityValue mergeNumeric(String capability, List<CapabilityGrant> grants) {
        if (grants.stream().anyMatch(grant -> grant.value().kind() != CapabilityValue.Kind.INTEGER
                && grant.value().kind() != CapabilityValue.Kind.DECIMAL)) {
            throw incompatible(capability, MergeStrategy.MAX);
        }
        if (grants.stream().allMatch(grant -> grant.value().kind() == CapabilityValue.Kind.INTEGER)) {
            return CapabilityValue.of(grants.stream().mapToInt(grant -> grant.value().asInt()).max().orElse(0));
        }
        return CapabilityValue.of(grants.stream()
                .map(grant -> grant.value().kind() == CapabilityValue.Kind.INTEGER
                        ? BigDecimal.valueOf(grant.value().asInt())
                        : grant.value().asDecimal())
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO));
    }

    private static IllegalArgumentException incompatible(String capability, MergeStrategy strategy) {
        return new IllegalArgumentException("Cannot apply " + strategy + " to capability " + capability);
    }
}
