package dev.emortal.minestom.core.module.kubernetes.command.currentserver;

import dev.emortal.api.model.playertracker.PlayerLocation;
import dev.emortal.minestom.core.module.kubernetes.PlayerTrackerManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minestom.server.command.builder.Command;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;

public final class CurrentServerCommand extends Command {
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.##");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final String MESSAGE = """
            <dark_purple>Proxy: <light_purple><proxy_id>
            <dark_purple>Server: <light_purple><server_id>""";
    private static final String COPY_MESSAGE = """
            Proxy: %s
            Server: %s
            Instance: %s
            Position: %s""";

    public CurrentServerCommand(@NotNull PlayerTrackerManager playerTrackerManager) {
        super("whereami");

        setDefaultExecutor((sender, context) -> {
            final Player player = (Player) sender;

            playerTrackerManager.retrievePlayerServer(player.getUuid(), location -> {
                final var serverId = Placeholder.unparsed("server_id", location.getServerId());
                final var proxyId = Placeholder.unparsed("proxy_id", location.getProxyId());

                sender.sendMessage(MINI_MESSAGE.deserialize(MESSAGE, serverId, proxyId)
                        .clickEvent(ClickEvent.copyToClipboard(createCopyableData(location, player)))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to copy", NamedTextColor.GREEN)))
                );
            });
        });
    }

    private String createCopyableData(@NotNull PlayerLocation location, @NotNull Player player) {
        return COPY_MESSAGE.formatted(
                location.getProxyId(),
                location.getServerId(),
                player.getInstance().getUniqueId(),
                formatPos(player.getPosition())
        );
    }

    private String formatPos(@NotNull Pos pos) {
        return String.format(
                "x: %s, y: %s, z: %s, yaw: %s, pitch: %s",
                DECIMAL_FORMAT.format(pos.x()), DECIMAL_FORMAT.format(pos.y()),
                DECIMAL_FORMAT.format(pos.z()), DECIMAL_FORMAT.format(pos.yaw()),
                DECIMAL_FORMAT.format(pos.pitch())
        );
    }
}