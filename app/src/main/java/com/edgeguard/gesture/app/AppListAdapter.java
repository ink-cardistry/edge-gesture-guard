package com.edgeguard.gesture.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.edgeguard.gesture.R;
import com.edgeguard.gesture.common.Rule;
import com.edgeguard.gesture.common.RuleStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 可展开的应用列表：点一行展开四个复选框，勾选即保存并立刻广播给 SystemUI。
 *
 * 四个开关：
 *   左 / 右  —— A12-A17 都已在 SystemUI 内实现，可靠性最高；
 *   顶部下拉 —— 在 SystemUI 的 shade 触摸链上实现（功能二）；
 *   底部上滑 —— 实验性（功能一：策略 A 坐标判断；A16/17 的最终执行在 Launcher 进程）。
 *
 * 复选框**永远可点**：即使当前探测不到入口也允许用户先配置好规则，
 * 真实是否生效以状态卡里的实测结果为准（不在这层假装支持或假装不支持）。
 */
public final class AppListAdapter extends BaseAdapter {

    public interface Listener {
        void onRulesChanged(String packageName, boolean blockLeft, boolean blockRight,
                            boolean blockBottom, boolean blockTopShade);
    }

    private final Context context;
    private final RuleStore store;
    private final Listener listener;
    private final LayoutInflater inflater;

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> visible = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();

    private String query = "";
    private boolean bottomObserved = false;
    private boolean shadeSupported = false;

    public AppListAdapter(Context context, RuleStore store, Listener listener) {
        this.context = context;
        this.store = store;
        this.listener = listener;
        this.inflater = LayoutInflater.from(context);
    }

    public void setData(List<AppEntry> data) {
        all.clear();
        if (data != null) {
            all.addAll(data);
        }
        applyFilter();
    }

    public void setQuery(String newQuery) {
        query = newQuery == null ? "" : newQuery.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    /**
     * 更新两条"实验性"通道的实测状态，只影响提示文案，不影响可点性。
     *
     * @param bottomObserved 输入通道是否真的观测到过底部区域事件（seen &gt; 0）
     * @param shadeSupported 通知栏触摸链是否挂上了（shade hooks &gt; 0）
     */
    public void setSupport(boolean bottomObserved, boolean shadeSupported) {
        if (this.bottomObserved != bottomObserved || this.shadeSupported != shadeSupported) {
            this.bottomObserved = bottomObserved;
            this.shadeSupported = shadeSupported;
            notifyDataSetChanged();
        }
    }

    /** 兼容旧调用点。 */
    public void setBottomSupported(boolean supported) {
        setSupport(supported, shadeSupported);
    }

    private void applyFilter() {
        visible.clear();
        for (AppEntry entry : all) {
            if (query.length() == 0 || entry.searchKey().contains(query)) {
                visible.add(entry);
            }
        }
        notifyDataSetChanged();
    }

    public void toggle(int position) {
        if (position < 0 || position >= visible.size()) {
            return;
        }
        String pkg = visible.get(position).packageName;
        if (!expanded.remove(pkg)) {
            expanded.add(pkg);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return visible.size();
    }

    @Override
    public Object getItem(int position) {
        return visible.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_app, parent, false);
            holder = new ViewHolder(view);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }

        final AppEntry item = visible.get(position);
        holder.label.setText(item.label);
        holder.packageName.setText(item.packageName);
        holder.icon.setImageDrawable(item.icon);

        Rule rule = store.getPackageRule(item.packageName);
        holder.summary.setText(summary(rule));
        holder.expand.setVisibility(expanded.contains(item.packageName) ? View.VISIBLE : View.GONE);

        // 先摘掉监听再 setChecked，避免绑定过程触发写盘/广播
        holder.left.setOnCheckedChangeListener(null);
        holder.right.setOnCheckedChangeListener(null);
        holder.bottom.setOnCheckedChangeListener(null);
        holder.topShade.setOnCheckedChangeListener(null);

        holder.left.setChecked(rule != null && rule.blockLeft);
        holder.right.setChecked(rule != null && rule.blockRight);
        holder.bottom.setChecked(rule != null && rule.blockBottom);
        holder.topShade.setChecked(rule != null && rule.blockTopShade);

        // 提示文案反映实测状态（复选框本身始终可点，方便先配置再去验证）
        holder.bottomHint.setText(bottomObserved
                ? R.string.bottom_observed_hint
                : R.string.bottom_unsupported_hint);
        holder.bottomHint.setVisibility(View.VISIBLE);
        holder.topHint.setText(shadeSupported
                ? R.string.top_shade_ok_hint
                : R.string.top_shade_unsupported_hint);
        holder.topHint.setVisibility(View.VISIBLE);

        CompoundButton.OnCheckedChangeListener changeListener =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        apply(item, holder);
                    }
                };
        holder.left.setOnCheckedChangeListener(changeListener);
        holder.right.setOnCheckedChangeListener(changeListener);
        holder.bottom.setOnCheckedChangeListener(changeListener);
        holder.topShade.setOnCheckedChangeListener(changeListener);

        return view;
    }

    private void apply(AppEntry item, ViewHolder holder) {
        boolean left = holder.left.isChecked();
        boolean right = holder.right.isChecked();
        boolean bottom = holder.bottom.isChecked();
        boolean top = holder.topShade.isChecked();
        store.setPackageRule(item.packageName, left, right, bottom, top);
        if (!left && !right && !bottom && !top) {
            expanded.remove(item.packageName);
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onRulesChanged(item.packageName, left, right, bottom, top);
        }
    }

    private String summary(Rule rule) {
        if (rule == null) {
            return context.getString(R.string.summary_none);
        }
        StringBuilder sb = new StringBuilder();
        if (rule.blockLeft) {
            sb.append("左 ");
        }
        if (rule.blockRight) {
            sb.append("右 ");
        }
        if (rule.blockTopShade) {
            sb.append("顶 ");
        }
        if (rule.blockBottom) {
            sb.append("底 ");
        }
        if (sb.length() == 0) {
            return context.getString(R.string.summary_none_configured);
        }
        return sb.append("已屏蔽").toString();
    }

    private static final class ViewHolder {

        final ImageView icon;
        final TextView label;
        final TextView packageName;
        final TextView summary;
        final View expand;
        final CheckBox left;
        final CheckBox right;
        final CheckBox bottom;
        final CheckBox topShade;
        final TextView bottomHint;
        final TextView topHint;

        ViewHolder(View root) {
            icon = root.findViewById(R.id.app_icon);
            label = root.findViewById(R.id.app_label);
            packageName = root.findViewById(R.id.app_package);
            summary = root.findViewById(R.id.app_summary);
            expand = root.findViewById(R.id.app_expand);
            left = root.findViewById(R.id.block_left);
            right = root.findViewById(R.id.block_right);
            bottom = root.findViewById(R.id.block_bottom);
            topShade = root.findViewById(R.id.block_top_shade);
            bottomHint = root.findViewById(R.id.bottom_hint);
            topHint = root.findViewById(R.id.top_hint);
        }
    }
}
