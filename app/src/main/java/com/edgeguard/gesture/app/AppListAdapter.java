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
 * 可展开的应用列表：点一行展开三个复选框，勾选即保存并立刻广播给 SystemUI。
 */
public final class AppListAdapter extends BaseAdapter {

    public interface Listener {
        void onRulesChanged(String packageName, boolean blockLeft, boolean blockRight, boolean blockBottom);
    }

    private final Context context;
    private final RuleStore store;
    private final Listener listener;
    private final LayoutInflater inflater;

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> visible = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();

    private String query = "";
    private boolean bottomSupported = false;

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

    public void setBottomSupported(boolean supported) {
        if (bottomSupported != supported) {
            bottomSupported = supported;
            notifyDataSetChanged();
        }
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

        holder.left.setChecked(rule != null && rule.blockLeft);
        holder.right.setChecked(rule != null && rule.blockRight);
        holder.bottom.setChecked(rule != null && rule.blockBottom);
        holder.bottom.setEnabled(bottomSupported);
        holder.bottomHint.setVisibility(bottomSupported ? View.GONE : View.VISIBLE);

        CompoundButton.OnCheckedChangeListener changeListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                apply(item, holder);
            }
        };
        holder.left.setOnCheckedChangeListener(changeListener);
        holder.right.setOnCheckedChangeListener(changeListener);
        holder.bottom.setOnCheckedChangeListener(changeListener);

        return view;
    }

    private void apply(AppEntry item, ViewHolder holder) {
        boolean left = holder.left.isChecked();
        boolean right = holder.right.isChecked();
        boolean bottom = holder.bottom.isChecked() && bottomSupported;
        store.setPackageRule(item.packageName, left, right, bottom);
        if (!left && !right && !bottom) {
            expanded.remove(item.packageName);
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onRulesChanged(item.packageName, left, right, bottom);
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
        final TextView bottomHint;

        ViewHolder(View root) {
            icon = root.findViewById(R.id.app_icon);
            label = root.findViewById(R.id.app_label);
            packageName = root.findViewById(R.id.app_package);
            summary = root.findViewById(R.id.app_summary);
            expand = root.findViewById(R.id.app_expand);
            left = root.findViewById(R.id.block_left);
            right = root.findViewById(R.id.block_right);
            bottom = root.findViewById(R.id.block_bottom);
            bottomHint = root.findViewById(R.id.bottom_hint);
        }
    }
}
