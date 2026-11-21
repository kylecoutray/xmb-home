/* Personal XMB home controls. GPL-3.0-or-later, like the Homestead host. */
package local.xmb.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

/** Only settings that affect this XMB build; system settings remain the TV's own. */
final class XmbOptions {
    private XmbOptions() { }

    static void prepare(Context c) {
        SharedPreferences p = c.getSharedPreferences("xmb", Context.MODE_PRIVATE);
        if (p.getBoolean("prepared", false)) return;
        Prefs.setOnboarded(c, true);
        Prefs.setSetupSeen(c, true);
        Prefs.setAutoUpdateCheck(c, false);
        Prefs.setAdbEnabled(c, false);
        Prefs.setTunnelEnabled(c, false);
        Prefs.setReapplyOnBoot(c, false);
        Prefs.setRestoreWatch(c, false);
        Prefs.setSort(c, Prefs.SORT_LAST_USED);
        Prefs.setShowNonTv(c, false);
        Prefs.setClockMode(c, Prefs.CLOCK_12H);
        Prefs.setSimpleMode(c, false);
        // The existing launcher remains installed, untouched, as a rollback option.
        Prefs.setHidden(c, "app:com.homestead.launcher", true);
        Prefs.setHidden(c, "app:com.amazon.hedwig", true);
        Prefs.setHidden(c, "app:com.amazon.bueller.music", true);
        Prefs.setHidden(c, "app:com.apple.airplay.amazon.airplay", true);
        Prefs.setHidden(c, AppModel.LIVETV_KEY, true);
        Prefs.setHidden(c, "in:com.droidlogic.tvinput/.services.AV1InputService/HW1", true);
        p.edit().putBoolean("prepared", true).apply();
    }

    static View build(final Context c, final SettingsPanel.Host host) {
        final SharedPreferences prefs = c.getSharedPreferences("xmb", Context.MODE_PRIVATE);
        final Panel panel = new Panel(c, "Home Screen Settings", null, Panel.SHEET);
        final Panel.Row theme = panel.addRow("Color theme",
                "dark".equals(prefs.getString("color_theme", "blue")) ? "Dark" : "Classic Blue", null);
        theme.setHelp("Select to switch between Classic Blue and Dark. Your choice is saved.");
        theme.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean dark = !"dark".equals(prefs.getString("color_theme", "blue"));
                prefs.edit().putString("color_theme", dark ? "dark" : "blue").apply();
                theme.setValue(dark ? "Dark" : "Classic Blue");
                panel.refreshTheme();
                host.rebuildGrid();
            }
        });
        final Panel.Row wave = panel.addRow("Animated wave",
                prefs.getBoolean("animated_wave", true) ? "On" : "Off", null).asSwitch();
        wave.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean next = !prefs.getBoolean("animated_wave", true);
                prefs.edit().putBoolean("animated_wave", next).apply();
                wave.setValue(next ? "On" : "Off");
                host.rebuildGrid();
            }
        });
        final Panel.Row sounds = panel.addRow("Menu sounds",
                prefs.getBoolean("menu_sounds", true) ? "On" : "Off", null).asSwitch();
        sounds.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean next = !prefs.getBoolean("menu_sounds", true);
                prefs.edit().putBoolean("menu_sounds", next).apply();
                sounds.setValue(next ? "On" : "Off");
                host.rebuildGrid();
            }
        });
        final Panel.Row sort = panel.addRow("App order",
                Prefs.sort(c) == Prefs.SORT_LAST_USED ? "Last used" : "Alphabetical", null);
        sort.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int next = Prefs.sort(c) == Prefs.SORT_LAST_USED
                        ? Prefs.SORT_ALPHA : Prefs.SORT_LAST_USED;
                Prefs.setSort(c, next);
                AppModel.resort(c);
                sort.setValue(next == Prefs.SORT_LAST_USED ? "Last used" : "Alphabetical");
                host.rebuildGrid();
            }
        });
        panel.addRow("TV settings", "", new Runnable() {
            public void run() { FireTvSettingsPanel.openAll(c, host); }
        }).opens();
        panel.addRow("Close", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return panel.root;
    }
}
