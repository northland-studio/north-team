package top.xuanjian.northteam.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import top.xuanjian.northteam.NorthTeamPlugin;
import top.xuanjian.northteam.chat.ChatFormat;
import top.xuanjian.northteam.config.PluginConfig;
import top.xuanjian.northteam.model.TeamUnit;

import java.util.Optional;

/**
 * 聊天栏队伍前缀渲染（插件 1.0.1 新增，对应 {@code chat.enabled}）。
 *
 * <p>只做一件事：把玩家消息交给 {@link ChatFormat} 按模板重新渲染，让官网配置的
 * 队伍前缀出现在聊天栏（Paper 默认渲染不认记分板队伍装饰，见 {@link ChatFormat} 类注释）。
 *
 * <p>几个刻意的设计取舍：
 * <ul>
 *   <li>优先级用 {@link EventPriority#NORMAL}：如果服务端另有聊天插件（例如
 *       EssentialsXChat）也设了 renderer，后设置的会覆盖先设置的 —— 那种情况下应该
 *       把 {@code chat.enabled} 关掉，避免两个插件互相盖；</li>
 *   <li>{@code chat.format_no_team} 留空时**不改动**无队伍玩家的聊天，交回默认渲染；</li>
 *   <li>队伍归属只查已应用配置的成员名单（{@link NorthTeamPlugin#teamOf(String)}），
 *       不查 Bukkit 记分板 —— 本事件在异步线程触发，读记分板不是线程安全的。</li>
 * </ul>
 */
public final class ChatListener implements Listener {

    private final NorthTeamPlugin plugin;

    public ChatListener(NorthTeamPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        PluginConfig config = plugin.config();
        if (config == null || !config.chatEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<TeamUnit> unit = plugin.teamOf(player.getName());
        String format = unit.isPresent() ? config.chatFormat() : config.chatNoTeamFormat();
        if (format == null || format.isBlank()) {
            // 留空 = 保持默认渲染（无队伍玩家常见配置）
            return;
        }
        event.renderer((source, sourceDisplayName, message, viewer) ->
                ChatFormat.render(format, source.getName(), unit.orElse(null), sourceDisplayName, message));
    }
}
