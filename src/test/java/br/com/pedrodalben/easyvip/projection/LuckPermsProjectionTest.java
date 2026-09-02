package br.com.pedrodalben.easyvip.projection;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LuckPermsProjectionTest {
    @Test
    void reconcilesOnlyManagedNodesAndPreservesUnrelatedPermissions() {
        UUID player = UUID.randomUUID();
        MemoryStore store = new MemoryStore(Set.of(
                LuckPermsProjection.managedNode("queue.priority"),
                "minecraft.command.fly"));

        LuckPermsProjection.ProjectionResult result = new LuckPermsProjection().reconcile(
                player, Set.of("minigame.private_match"), store);

        assertEquals(Set.of(LuckPermsProjection.managedNode("minigame.private_match")), result.added());
        assertEquals(Set.of(LuckPermsProjection.managedNode("queue.priority")), result.removed());
        assertTrue(store.nodes.contains("minecraft.command.fly"));
        assertTrue(store.nodes.contains(LuckPermsProjection.managedNode("minigame.private_match")));
        assertThrows(IllegalArgumentException.class, () -> LuckPermsProjection.managedNode("../admin"));
    }

    private static final class MemoryStore implements LuckPermsProjection.ManagedNodeStore {
        private final Set<String> nodes;

        private MemoryStore(Set<String> initial) {
            this.nodes = new HashSet<>(initial);
        }

        @Override
        public Set<String> managedNodes(UUID playerUuid) {
            return Set.copyOf(nodes);
        }

        @Override
        public void add(UUID playerUuid, String node) { nodes.add(node); }

        @Override
        public void remove(UUID playerUuid, String node) { nodes.remove(node); }
    }
}
