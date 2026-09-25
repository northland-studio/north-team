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
 *   <li>渲染优先级用 {@link EventPriority#HIGHEST}：如果服务端另有插件（例如
 *       EssentialsX 自带 chat provider）也设置了 renderer，**后设置的会覆盖先设置的**，
 *       用最高优先级保证我们最后落笔；</li>
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

    /**
     * 诊断观察点：以最低优先级、且不忽略取消地接一次事件，只写 debug 日志。
     *
     * <p>存在的意义：{@link AsyncChatEvent} 有可能被其它插件（登录类插件、禁言类插件）取消，
     * 也可能在某些服务端版本上根本不触发。渲染处理器为了尊重"被取消就别渲染"用了
     * {@code ignoreCancelled = true}，一旦事件被取消就静默跳过 —— 排查时完全看不出区别。
     * 这个观察点把这些情况记进日志，便于一眼判断「事件没来」还是「来了但被取消」。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void observe(AsyncChatEvent event) {
        PluginConfig config = plugin.config();
        if (config != null && config.debug()) {
            plugin.info("[debug] 收到 AsyncChatEvent: " + event.getPlayer().getName()
                    + " cancelled=" + event.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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
        if (config.debug()) {
            plugin.info("[debug] 渲染聊天: " + player.getName()
                    + " 队伍=" + (unit.isPresent() ? unit.get().safeKey() : "<无队伍>")
                    + " 格式=" + format);
        }
        event.renderer((source, sourceDisplayName, message, viewer) ->
                ChatFormat.render(format, source.getName(), unit.orElse(null), sourceDisplayName, message));
    }
}
