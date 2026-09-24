package top.xuanjian.northteam.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import top.xuanjian.northteam.apply.TeamApplier;

/**
 * 契约 4.2：离线玩家在下次登录时补入其队伍。
 *
 * <p>用 {@link EventPriority#MONITOR} 保证在其它插件处理完之后再入队，
 * 避免被后置逻辑覆盖。记分板操作在主线程执行（事件本就在主线程）。
 */
public final class PlayerJoinListener implements Listener {

    private final TeamApplier applier;

    public PlayerJoinListener(TeamApplier applier) {
        this.applier = applier;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        applier.assignOnJoin(event.getPlayer());
    }
}
