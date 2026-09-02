package br.com.pedrodalben.easyvip.platform;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionBridgeTest {

    @Test
    void opFallbackAllowsWhenAllBridgesReject() {
        assertTrue(PermissionBridge.resolvePermission(false, false, false, true));
    }

    @Test
    void nonOpStillNeedsARealPermission() {
        assertFalse(PermissionBridge.resolvePermission(false, false, false, false));
    }

    @Test
    void anyBridgeCanGrantAccessBeforeOpFallback() {
        assertTrue(PermissionBridge.resolvePermission(true, false, false, false));
        assertTrue(PermissionBridge.resolvePermission(false, true, false, false));
        assertTrue(PermissionBridge.resolvePermission(false, false, true, false));
    }

    @Test
    void disabledLuckPermsAsyncOperationsCompleteWithoutExternalIo() {
        boolean previous = EasyVipConfig.integrations.luckpermsEnabled;
        EasyVipConfig.integrations.luckpermsEnabled = false;
        try {
            UUID player = UUID.randomUUID();
            assertTrue(PermissionBridge.setGroupAsync(player, "diamond", true)
                    .toCompletableFuture().join());
            assertTrue(PermissionBridge.setPermissionAsync(player, "easyvip.use", true)
                    .toCompletableFuture().join());
            assertTrue(PermissionBridge.createGroupAsync("diamond")
                    .toCompletableFuture().join());
        } finally {
            EasyVipConfig.integrations.luckpermsEnabled = previous;
        }
    }
}
