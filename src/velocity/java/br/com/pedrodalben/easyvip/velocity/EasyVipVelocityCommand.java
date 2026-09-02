package br.com.pedrodalben.easyvip.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Proxy command that consumes generic capabilities, never tier names. */
public final class EasyVipVelocityCommand implements SimpleCommand {
    private final EasyVipVelocityPlugin plugin;

    public EasyVipVelocityCommand(EasyVipVelocityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0 || "info".equalsIgnoreCase(args[0])) {
            sendInfo(invocation.source());
            return;
        }
        if ("network".equalsIgnoreCase(args[0])) {
            if (args.length > 1 && "nodes".equalsIgnoreCase(args[1])) {
                sendNodes(invocation.source());
            } else {
                sendStatus(invocation.source());
            }
            return;
        }
        if ("capability".equalsIgnoreCase(args[0]) && args.length > 1) {
            sendCapability(invocation.source(), args[1]);
            return;
        }
        if ("queue".equalsIgnoreCase(args[0])) {
            sendCapability(invocation.source(), "queue.priority");
            return;
        }
        if ("reserved".equalsIgnoreCase(args[0])) {
            sendCapability(invocation.source(), "network.reserved_slot");
            return;
        }
        if ("maintenance".equalsIgnoreCase(args[0])) {
            sendCapability(invocation.source(), "network.maintenance_bypass");
            return;
        }
        invocation.source().sendMessage(Component.text("Uso: /easyvip info | network [nodes] | capability <nome>"));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 0 && "network".equalsIgnoreCase(args[0])) {
            return invocation.source().hasPermission("easyvip.admin");
        }
        return invocation.source().hasPermission("easyvip.use");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length == 0) {
            return List.of("info", "network", "capability", "queue", "reserved", "maintenance");
        }
        if (invocation.arguments().length == 1 && "network".startsWith(invocation.arguments()[0].toLowerCase())) {
            return List.of("network");
        }
        return List.of();
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return CompletableFuture.completedFuture(suggest(invocation));
    }

    private void sendInfo(CommandSource source) {
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("/easyvip info requer um jogador conectado."));
            return;
        }
        if (plugin.api() == null) {
            source.sendMessage(Component.text("EasyVip está indisponível."));
            return;
        }
        plugin.api().playerAsync(player.getUniqueId(), br.com.pedrodalben.easyvip.api.ScopeContext.network())
                .whenComplete((view, error) -> {
                    if (error != null) source.sendMessage(Component.text("Não foi possível consultar suas capacidades."));
                    else source.sendMessage(Component.text("Capacidades: " + String.join(", ", view.capabilities().keySet())));
                });
    }

    private void sendCapability(CommandSource source, String capability) {
        if (!(source instanceof Player player) || plugin.api() == null) {
            source.sendMessage(Component.text("EasyVip está indisponível para este comando."));
            return;
        }
        plugin.api().playerAsync(player.getUniqueId(), br.com.pedrodalben.easyvip.api.ScopeContext.network())
                .whenComplete((view, error) -> {
                    if (error != null) {
                        source.sendMessage(Component.text("Não foi possível consultar a capacidade."));
                        return;
                    }
                    source.sendMessage(Component.text(capability + " = " + view.get(capability)));
                });
    }

    private void sendStatus(CommandSource source) {
        var cache = plugin.cache();
        var redis = plugin.redis();
        String redisStatus = redis == null ? "disabled" : (redis.isRunning() ? "running" : "stopped");
        String node = plugin.nodeIdentity() == null ? "uninitialized" : plugin.nodeIdentity().nodeId();
        String stats = cache == null ? "n/a" : "entries=" + cache.estimatedSize() + ", hits=" + cache.stats().hitCount()
                + ", misses=" + cache.stats().missCount();
        source.sendMessage(Component.text("EasyVip network: node=" + node + ", redis=" + redisStatus + ", cache=" + stats));
    }

    private void sendNodes(CommandSource source) {
        if (plugin.nodes() == null) {
            source.sendMessage(Component.text("Redis está desabilitado; nós não estão disponíveis."));
            return;
        }
        plugin.nodes().visibleNodes(java.time.Instant.now()).whenComplete((nodes, error) -> {
            if (error != null) source.sendMessage(Component.text("Não foi possível consultar os nós."));
            else source.sendMessage(Component.text("Nós ativos: " + nodes.stream()
                    .map(node -> node.identity().nodeId()).sorted().toList()));
        });
    }
}
