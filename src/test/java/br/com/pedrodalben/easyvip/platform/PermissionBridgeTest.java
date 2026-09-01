package br.com.pedrodalben.easyvip.platform;

import org.junit.jupiter.api.Test;

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
}
