package com.edgeguard.gesture.app;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.edgeguard.gesture.common.Logx;

/**
 * 控制中心磁贴：一键进入 / 退出游戏模式。
 *
 * 语义与常驻通知完全一致（都是"遥控器"）：只发命令，状态以 SystemUI 回推为准。
 * 由于游戏模式会屏蔽顶部下拉，磁贴在开启后基本够不着 —— 这是设计使然，
 * 退出主通道是"锁屏→解锁自动退出"（见 ArmController）。
 */
public final class GameTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            boolean next = !ArmClient.isArmed(this);
            ArmClient.setArmed(this, next, "tile");
            updateTile();
        } catch (Throwable t) {
            Logx.w("tile click failed", t);
        }
    }

    /** 状态变化时让系统重新回调 onStartListening（磁贴标签/状态才会刷新）。 */
    public static void requestRefresh(Context context) {
        try {
            TileService.requestListeningState(context,
                    new ComponentName(context, GameTileService.class));
        } catch (Throwable ignored) {
        }
    }

    private void updateTile() {
        try {
            Tile tile = getQsTile();
            if (tile == null) {
                return;
            }
            boolean armed = ArmClient.isArmed(this);
            tile.setState(armed ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(armed ? "游戏模式 已开" : "游戏模式");
            tile.setContentDescription(armed
                    ? "游戏模式已开启：屏蔽顶部下拉与底部上滑，锁屏解锁自动退出"
                    : "游戏模式已关闭");
            tile.updateTile();
        } catch (Throwable t) {
            Logx.w("updateTile failed", t);
        }
    }
}
