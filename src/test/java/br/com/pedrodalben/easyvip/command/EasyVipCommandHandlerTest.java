package br.com.pedrodalben.easyvip.command;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EasyVipCommandHandlerTest {

    @TempDir
    Path tempDir;

    private EasyVipCommandHandler handler;
    private Command mockCommand;

    @BeforeEach
    void setUp() {
        EasyVipConfig.initialize(tempDir);
        PersistenceManager.initialize(tempDir);
        handler = new EasyVipCommandHandler(null);
        mockCommand = mock(Command.class);
        when(mockCommand.getName()).thenReturn("easyvip");
    }

    @Test
    void playerWithoutPermissionIsDenied() {
        Player player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(false);
        when(player.isOp()).thenReturn(false);

        boolean result = handler.onCommand(player, mockCommand, "easyvip", new String[]{"help"});
        assertTrue(result);
        verify(player, atLeastOnce()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void consoleHasFullPermission() {
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);

        boolean result = handler.onCommand(console, mockCommand, "easyvip", new String[]{"help"});
        assertTrue(result);
        verify(console, atLeastOnce()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void tabCompletionProvidesSubcommands() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("easyvip.use")).thenReturn(true);
        when(sender.hasPermission("easyvip.admin")).thenReturn(true);

        List<String> completions = handler.onTabComplete(sender, mockCommand, "easyvip", new String[]{""});
        assertNotNull(completions);
        assertTrue(completions.contains("use"));
        assertTrue(completions.contains("admin"));
        assertTrue(completions.contains("network"));
        assertTrue(completions.contains("createvip"));
        assertTrue(completions.contains("select"));
    }

    @Test
    void tabCompletionFiltersPrefix() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("easyvip.use")).thenReturn(true);
        when(sender.hasPermission("easyvip.admin")).thenReturn(true);

        List<String> completions = handler.onTabComplete(sender, mockCommand, "easyvip", new String[]{"ad"});
        assertNotNull(completions);
        assertTrue(completions.contains("admin"));
        assertFalse(completions.contains("use"));
    }

    @Test
    void networkDiagnosticsRequireAdminPermission() {
        Player player = mock(Player.class);
        when(player.hasPermission("easyvip.use")).thenReturn(true);
        when(player.hasPermission("easyvip.admin")).thenReturn(false);
        when(player.isOp()).thenReturn(false);

        assertTrue(handler.onCommand(player, mockCommand, "easyvip", new String[]{"network", "status"}));
        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
}
