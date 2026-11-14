package com.deepseekharness.app.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/** 用户授权版的权限说明；刷新界面不修改已有授权记录。 */
final class FullPermissionControls implements AutoCloseable {
  private final MaterialSwitch global;
  private final TextView status;
  private boolean closed;

  FullPermissionControls(Fragment owner, LinearLayout host, Runnable policyChanged) {
    Context ctx = owner.requireContext();
    LinearLayout card = new LinearLayout(ctx);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(20, 20, 20, 20);
    card.setBackgroundResource(R.drawable.bg_card);
    global = new MaterialSwitch(ctx);
    global.setText(UiText.choose("DSHA 安全拦截已移除", "DSHA safety restrictions removed"));
    global.setChecked(true);
    global.setEnabled(false);
    card.addView(global);
    status = new TextView(ctx);
    status.setTextSize(12);
    status.setTextColor(ctx.getColor(R.color.text_secondary));
    card.addView(status);
    host.addView(card, 2, new LinearLayout.LayoutParams(-1, -2));
    refresh();
  }

  void refresh() {
    if (closed) return;
    status.setText(
        UiText.choose(
            "本版保留 Root 命令限制和独立说明弹窗；Root 操作不能通过全局免确认跳过检查。普通通道和文件接口沿用用户授权设置。Root、Shizuku、ADB 连接及 Android 系统权限仍需在本机授予。",
            "Root command restrictions and dedicated notices remain enabled and cannot be bypassed by global auto approval. Ordinary channels and file interfaces retain the authorized settings. Root, Shizuku, ADB connections and Android system permissions still require grants on this device."));
  }

  void pause() {}

  void resume() {
    refresh();
  }

  void cancelForLocationChange() {
    refresh();
  }

  @Override
  public void close() {
    closed = true;
  }
}
