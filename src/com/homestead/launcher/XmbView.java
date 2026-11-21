/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package local.xmb.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * The classic XMB: a horizontal category axis and a vertical list, measured in the
 * reference's 1920x1080 coordinates. Video has its own TextureView; the transparent
 * UI is invalidated only for navigation, clock updates and short transitions.
 * Both Homestead's activity and its early Home overlay host this same view.
 */
public final class XmbView extends FrameLayout {
    public interface Callback {
        void onActivate(AppModel.Item item);
        void onOptions();
    }

    private static final String TAG = "Homestead";
    private static final String[] NAMES = {
            "Settings", "Photo", "Music", "Video", "TV/Video Services", "Game", "Network", "Friends"
    };
    private static final String[] CATEGORY_ART = {
            "icons/Layer_32.png", "icons/photo.png", "icons/music.png", "icons/video.png",
            "icons/tv.png", "icons/game.png", "icons/network.png", "icons/friends.png"
    };
    private static final String[] CORE_ART = {
            "icons/Layer_41.png", "icons/Layer_26.png", "icons/ln_main.png", "icons/Layer_43.png",
            "icons/pw_home.png", "icons/src_hdmi.png", "logos/netflix.png", "logos/primevideo.png",
            "logos/disney.png", "logos/hulu.png", "logos/hbomax.png", "logos/spotify.png", "logos/youtube.png",
            "logos/youtubemusic.png"
    };
    private static final int TV = 4;
    private static final float AXIS_X = 565f, CAT_Y = 283f, SELECTED_Y = 500f, GAP = 200f;
    private static final float COLLAPSED_Y = 840f;
    private static final HashMap<String, Bitmap> ART = new HashMap<String, Bitmap>();
    private static final Shader LEFT_FADE = new LinearGradient(0, 0, 380, 0,
            0x33000000, 0x00000000, Shader.TileMode.CLAMP);
    private static final Shader ICON_GLOW = new RadialGradient(0, 0, 1f,
            0x23FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private static boolean artReady;
    private static Typeface rodin;
    // Both hosts can share these stills. Keep exactly the two supported themes and
    // never recycle a bitmap that another activity/overlay may still be drawing.
    private static final Bitmap[] FALLBACKS = new Bitmap[2];

    private static final class Entry {
        final AppModel.Item item;
        final String title, description, art;
        Entry(AppModel.Item it, String t, String d, String a) {
            item = it; title = t; description = d; art = a;
        }
    }

    private final Callback callback;
    private final SharedPreferences preferences;
    private final ArrayList<ArrayList<Entry>> lists = new ArrayList<ArrayList<Entry>>();
    private final int[] indices = new int[NAMES.length];
    private final ImageView still;
    private final TextureView video;
    private final MenuCanvas menu;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final HashMap<String, Integer> sounds = new HashMap<String, Integer>();
    private MediaPlayer player;
    private Surface videoSurface;
    private SoundPool soundPool;
    private ValueAnimator animation;
    private int category = TV, previousCategory = TV;
    private float categoryPosition = TV, fromCategoryPosition = TV, progress = 1f;
    private float listPosition, fromListPosition, previousListPosition;
    private boolean listExpanded = true;
    private float reveal = 1f, fromReveal = 1f;
    private boolean hasItems, homeActive = true, decoderFailed;
    private boolean animatedWave = true, menuSounds = true;
    private String colorTheme;
    private int videoWidth, videoHeight;
    private String date = "";

    public XmbView(Context c, Callback cb) {
        super(c);
        callback = cb;
        preferences = c.getSharedPreferences("xmb", Context.MODE_PRIVATE);
        colorTheme = selectedTheme();
        setBackgroundColor(Color.BLACK);
        // The wave is this home's idle screen. Keep Amazon's ambient ads from replacing
        // it; this view's window is hidden while apps run, and explicit Sleep still works.
        setKeepScreenOn(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setClipChildren(true);
        for (int i = 0; i < NAMES.length; i++) lists.add(new ArrayList<Entry>());
        if (rodin == null) {
            try { rodin = Typeface.createFromAsset(c.getAssets(), "xmb/rodin.ttf"); }
            catch (RuntimeException e) { rodin = Typeface.create("sans-serif-light", Typeface.NORMAL); }
        }
        text.setTypeface(rodin);
        warmArt(c);

        still = new ImageView(c);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bg = fallback(c, colorTheme);
        if (bg != null) still.setImageBitmap(bg);
        addView(still, new LayoutParams(-1, -1));

        video = new TextureView(c);
        video.setOpaque(false);
        video.setAlpha(0f);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { refreshResources(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { fitVideo(); }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { releaseVideo(); return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s) { /* No UI invalidation per frame. */ }
        });
        addView(video, new LayoutParams(-1, -1));
        menu = new MenuCanvas(c);
        addView(menu, new LayoutParams(-1, -1));
        fillEmptyCategories();
        updateClock();
    }

    public void setItems(List<AppModel.Item> items) {
        if (animation != null) animation.cancel();
        String key = hasItems ? selectedKey() : null;
        for (List<Entry> list : lists) list.clear();
        boolean hasTvSettings = false;
        AppModel.Item youtube = null;
        for (AppModel.Item it : items) {
            if (it.type == AppModel.TYPE_APP && "com.amazon.firetv.youtube".equals(it.pkg)) youtube = it;
            if (it.type == AppModel.TYPE_SHORTCUT && AppModel.SC_MORE.equals(it.cls)) hasTvSettings = true;
            int cat;
            if (it.type == AppModel.TYPE_INPUT || it.type == AppModel.TYPE_LIVETV) cat = 3;
            else if (it.type != AppModel.TYPE_APP) cat = 0;
            else if (streaming(it)) cat = TV;
            else {
                String pkg = it.pkg == null ? "" : it.pkg.toLowerCase(Locale.US);
                if (pkg.contains("spotify") || pkg.contains("amazon.mp3") || pkg.contains("tidal")) cat = 2;
                else if (pkg.contains("photos") || pkg.contains("gallery")) cat = 1;
                else cat = 6;
            }
            lists.get(cat).add(new Entry(it, title(it), description(it), itemArt(it)));
        }
        // On Fire TV, YouTube Music lives inside the official YouTube app.
        // A distinct shortcut key keeps it separate from the normal YouTube entry.
        if (youtube != null) {
            AppModel.Item music = new AppModel.Item();
            music.type = AppModel.TYPE_SHORTCUT;
            music.pkg = youtube.pkg;
            music.cls = AppModel.SC_YT_MUSIC;
            music.label = "YouTube Music";
            lists.get(2).add(new Entry(music, music.label,
                    "Via YouTube: select Music in the left menu", "logos/youtubemusic.png"));
        }
        // The stock grid synthesizes this as a top-bar button rather than a model item.
        if (!hasTvSettings) {
            AppModel.Item settings = new AppModel.Item();
            settings.type = AppModel.TYPE_SHORTCUT;
            settings.cls = AppModel.SC_MORE;
            settings.label = "TV Settings";
            lists.get(0).add(0, new Entry(settings, "TV Settings", "Picture, sound, network and device settings", "icons/Layer_26.png"));
        }
        fillEmptyCategories();
        for (int i = 0; i < indices.length; i++) indices[i] = Math.min(indices[i], lists.get(i).size() - 1);
        if (!hasItems) {
            category = TV;
            indices[TV] = 0;
            long latest = 0;
            for (int i = 0; i < lists.get(TV).size(); i++) {
                AppModel.Item it = lists.get(TV).get(i).item;
                if (it != null && it.lastUsed > latest) { latest = it.lastUsed; indices[TV] = i; }
            }
        } else if (key != null) selectKey(key);
        hasItems = true;
        categoryPosition = category;
        listPosition = indices[category];
        previousCategory = category;
        progress = 1f;
        reveal = listExpanded ? 1f : 0f;
        refreshResources();
        menu.invalidate();
    }

    public String selectedKey() {
        if (!listExpanded) return "axis:" + category;
        Entry e = selectedEntry();
        return e != null && e.item != null ? e.item.key() : "cat:" + category;
    }

    public boolean selectKey(String key) {
        if (key == null) return false;
        boolean axis = key.startsWith("axis:");
        if (axis || key.startsWith("cat:")) {
            try {
                int n = Integer.parseInt(key.substring(axis ? 5 : 4));
                if (n < 0 || n >= NAMES.length) return false;
                if (animation != null) animation.cancel();
                category = n;
                if (axis) indices[n] = 0;
                categoryPosition = n;
                listPosition = indices[n];
                previousCategory = n;
                progress = 1f;
                listExpanded = !axis;
                reveal = listExpanded ? 1f : 0f;
                menu.invalidate();
                return true;
            } catch (NumberFormatException e) { return false; }
        }
        for (int c = 0; c < lists.size(); c++) {
            List<Entry> list = lists.get(c);
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                if (e.item != null && key.equals(e.item.key())) {
                    if (animation != null) animation.cancel();
                    category = c;
                    indices[c] = i;
                    categoryPosition = c;
                    listPosition = i;
                    previousCategory = c;
                    progress = 1f;
                    listExpanded = true;
                    reveal = 1f;
                    menu.invalidate();
                    return true;
                }
            }
        }
        return false;
    }

    public void takeStartFocus() {
        if (animation != null) animation.cancel();
        category = TV;
        indices[TV] = 0;
        categoryPosition = TV;
        listPosition = 0f;
        previousCategory = TV;
        progress = 1f;
        listExpanded = true;
        reveal = 1f;
        requestFocus();
        menu.invalidate();
    }
    public void takeFocus() { requestFocus(); }

    public boolean handleKey(KeyEvent e) {
        int key = e.getKeyCode();
        boolean navigation = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean accept = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (!navigation && !accept && key != KeyEvent.KEYCODE_MENU && key != KeyEvent.KEYCODE_BACK) return false;
        if (!homeActive) return false;
        if (navigation) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (!listExpanded) {
                    if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        foldList(true);
                        play("cursor");
                        return true;
                    }
                } else if (key == KeyEvent.KEYCODE_DPAD_UP && indices[category] == 0) {
                    // Reaching the first row while holding Up is distinct from the
                    // extra, fresh press that folds its list away.
                    if (e.getRepeatCount() == 0) {
                        foldList(false);
                        play("cursor");
                    }
                    return true;
                }
                int nextCat = category, nextIndex = indices[category];
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) nextCat = Math.max(0, category - 1);
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) nextCat = Math.min(NAMES.length - 1, category + 1);
                if (key == KeyEvent.KEYCODE_DPAD_UP) nextIndex = Math.max(0, nextIndex - 1);
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) nextIndex = Math.min(lists.get(category).size() - 1, nextIndex + 1);
                if (nextCat != category || nextIndex != indices[category]) {
                    boolean changedCategory = nextCat != category;
                    beginTransition(nextCat, changedCategory ? (listExpanded ? indices[nextCat] : 0) : nextIndex);
                    play(changedCategory ? "category" : "cursor");
                }
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_UP) return true;
        if (key == KeyEvent.KEYCODE_MENU) {
            play("decide");
            callback.onOptions();
        } else if (key == KeyEvent.KEYCODE_BACK) {
            if (!listExpanded) {
                if (category != TV) beginTransition(TV, 0);
            } else if (indices[category] != 0) beginTransition(category, 0);
            else if (category != TV) beginTransition(TV, indices[TV]);
            play("cancel");
        } else {
            if (!listExpanded) {
                foldList(true);
                play("decide");
                return true;
            }
            // A second quick Select must not launch an item while its list is
            // still hidden behind the axis's return to the expanded position.
            if (reveal < 1f) return true;
            Entry selected = selectedEntry();
            if (selected != null && selected.item != null) {
                play("decide");
                callback.onActivate(selected.item);
            }
        }
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return handleKey(e) || super.dispatchKeyEvent(e);
    }

    public void updateClock() {
        Date now = new Date();
        date = new SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now);
        if (menu != null) {
            refreshResources();
            menu.invalidate();
        }
    }

    /** The host passes false while a settings panel covers the home menu. */
    public void setMenuActive(boolean active) {
        homeActive = active;
        refreshResources();
        menu.invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshResources();
    }

    @Override protected void onDetachedFromWindow() {
        if (animation != null) animation.cancel();
        releaseVideo();
        releaseSounds();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (video != null) refreshResources();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (video != null) refreshResources();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (video != null) refreshResources();
    }

    private Entry selectedEntry() {
        List<Entry> list = lists.get(category);
        return list.isEmpty() ? null : list.get(Math.min(indices[category], list.size() - 1));
    }

    private void fillEmptyCategories() {
        String[] empty = { "Settings", "No photo apps installed", "No music apps installed", "No inputs available",
                "No streaming apps installed", "No games installed", "No other apps installed", "Friends" };
        for (int i = 0; i < lists.size(); i++) {
            if (!lists.get(i).isEmpty()) continue;
            String detail = i == 7 ? "PlayStation Network is not available on this TV."
                    : i == 0 ? "Press Menu for launcher options." : "Installed apps appear when available.";
            lists.get(i).add(new Entry(null, empty[i], detail, CATEGORY_ART[i]));
        }
    }

    private static boolean streaming(AppModel.Item it) {
        String p = it.pkg == null ? "" : it.pkg.toLowerCase(Locale.US);
        return p.contains("netflix") || p.equals(AppModel.PRIME_VIDEO_PKG) || p.contains("disney")
                || p.contains("hulu") || p.contains("youtube") || p.contains("hbo") || p.contains("peacock")
                || p.contains("paramount") || p.contains("plex") || p.contains("appletv");
    }

    private static String title(AppModel.Item it) {
        if (it.type == AppModel.TYPE_SETTINGS) return "Home Screen Settings";
        if (it.type == AppModel.TYPE_SHORTCUT && AppModel.SC_MORE.equals(it.cls)) return "TV Settings";
        return it.label == null ? "Application" : it.label;
    }

    private static String description(AppModel.Item it) {
        if (it.type == AppModel.TYPE_SETTINGS) return "Background, sound and launcher options";
        if (it.type == AppModel.TYPE_SHORTCUT) return "Fire TV settings";
        if (it.type == AppModel.TYPE_HEALTH) return "TV performance and storage";
        if (it.type == AppModel.TYPE_AMAZON_HOME) return "Open the original Fire TV home screen";
        if (it.type == AppModel.TYPE_INPUT) return "Switch TV input";
        if (it.type == AppModel.TYPE_LIVETV) return "Watch antenna or cable channels";
        return "";
    }

    private static String itemArt(AppModel.Item it) {
        String p = it.pkg == null ? "" : it.pkg.toLowerCase(Locale.US);
        if (p.contains("netflix")) return "logos/netflix.png";
        if (p.equals(AppModel.PRIME_VIDEO_PKG)) return "logos/primevideo.png";
        if (p.contains("disney")) return "logos/disney.png";
        if (p.contains("hulu")) return "logos/hulu.png";
        if (p.contains("hbo")) return "logos/hbomax.png";
        if (p.contains("spotify")) return "logos/spotify.png";
        if ("com.amazon.firetv.youtube".equals(p)) return "logos/youtube.png";
        if (it.type == AppModel.TYPE_INPUT) return "icons/src_hdmi.png";
        if (it.type == AppModel.TYPE_LIVETV) return "icons/tv.png";
        if (it.type == AppModel.TYPE_SETTINGS) return "icons/ln_main.png";
        if (it.type == AppModel.TYPE_HEALTH || it.type == AppModel.TYPE_RESTORE) return "icons/Layer_43.png";
        if (it.type == AppModel.TYPE_AMAZON_HOME) return "icons/pw_home.png";
        if (it.type == AppModel.TYPE_SHORTCUT) return "icons/Layer_26.png";
        return null;
    }

    private void beginTransition(int nextCategory, int nextIndex) {
        beginTransition(nextCategory, nextIndex, 180L, false);
    }

    private void foldList(boolean expanded) {
        listExpanded = expanded;
        if (expanded && reveal <= .7f) listPosition = 0f;
        beginTransition(category, expanded ? 0 : indices[category], 200L, true);
    }

    private void beginTransition(int nextCategory, int nextIndex, long duration, boolean fold) {
        if (animation != null) animation.cancel();
        previousCategory = category;
        previousListPosition = listPosition;
        fromCategoryPosition = categoryPosition;
        category = nextCategory;
        indices[category] = nextIndex;
        if (previousCategory != category) listPosition = nextIndex;
        fromListPosition = listPosition;
        fromReveal = reveal;
        progress = 0f;
        animation = ValueAnimator.ofFloat(0f, 1f);
        animation.setDuration(duration);
        animation.setInterpolator(fold ? new LinearInterpolator() : new DecelerateInterpolator(2f));
        animation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                categoryPosition = fromCategoryPosition + (category - fromCategoryPosition) * progress;
                listPosition = fromListPosition + (indices[category] - fromListPosition) * progress;
                reveal = fromReveal + ((listExpanded ? 1f : 0f) - fromReveal) * progress;
                menu.invalidate();
            }
        });
        animation.start();
    }

    private final class MenuCanvas extends View {
        MenuCanvas(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        @Override protected void onDraw(Canvas c) {
            float scale = Math.min(getWidth() / 1920f, getHeight() / 1080f);
            c.save();
            c.translate((getWidth() - 1920f * scale) / 2f, (getHeight() - 1080f * scale) / 2f);
            c.scale(scale, scale);
            paint.setShader(LEFT_FADE);
            c.drawRect(0, 0, 380, 1080, paint);
            paint.setShader(null);
            // Fade the rows during the first 60ms of a fold, then lower the axis.
            // Expanding reverses those phases, so glyphs never cross visible rows.
            float axisY = CAT_Y + (COLLAPSED_Y - CAT_Y) * (1f - smoothStep(reveal / .7f));
            float listOpacity = smoothStep((reveal - .7f) / .3f);
            for (int i = 0; i < NAMES.length; i++) {
                float distance = Math.abs(i - categoryPosition);
                float focus = Math.max(0f, 1f - distance);
                float x = AXIS_X + (i - categoryPosition) * GAP + (i == category ? 0 : Math.signum(i - categoryPosition) * 10f);
                float y = axisY + 6f * (1f - focus);
                float size = 94f * (0.7f + 0.3f * focus);
                drawIcon(c, CATEGORY_ART[i], null, x, y, size, size, 0.7f + 0.3f * focus, focus > .5f);
                if (focus > 0f) drawText(c, NAMES[i], x, axisY + 83f, 24f, focus, true, true);
            }
            if (listOpacity > 0f && previousCategory != category && progress < 1f) {
                drawList(c, previousCategory, previousListPosition, AXIS_X + (previousCategory - categoryPosition) * GAP,
                        (1f - progress) * listOpacity);
                drawList(c, category, listPosition, AXIS_X + (category - categoryPosition) * GAP, progress * listOpacity);
            } else if (listOpacity > 0f) drawList(c, category, listPosition, AXIS_X, listOpacity);
            drawDate(c);
            if (!homeActive) {
                paint.setColor(0x68000000);
                c.drawRect(0, 0, 1920, 1080, paint);
            }
            c.restore();
        }
    }

    private static float smoothStep(float value) {
        float t = Math.max(0f, Math.min(1f, value));
        return t * t * (3f - 2f * t);
    }

    private void drawList(Canvas c, int cat, float position, float x, float opacity) {
        List<Entry> entries = lists.get(cat);
        int first = Math.max(0, (int) Math.floor(position) - 5);
        int last = Math.min(entries.size() - 1, (int) Math.ceil(position) + 3);
        for (int i = first; i <= last; i++) {
            float distance = i - position;
            int lower = (int) Math.floor(distance);
            // Between two rows, retain the classic large gap around the focused item.
            // A repeated key starts from this rendered coordinate rather than snapping
            // to the previous integer target before the next transition.
            float y = itemY(lower) + (itemY(lower + 1) - itemY(lower)) * (distance - lower);
            float focus = Math.max(0f, 1f - Math.abs(distance));
            Entry entry = entries.get(i);
            float alpha = opacity * (0.6f + 0.4f * focus);
            float iconScale = 0.52f + 0.48f * focus;
            Bitmap ownIcon = entry.item == null ? null : entry.item.icon;
            drawIcon(c, entry.art, ownIcon, x, y, 118f * iconScale, 100f * iconScale, alpha, focus > .8f);
            drawText(c, entry.title, x + 116f, y + 9f - 9f * focus,
                    28f + 4f * focus, alpha, false, focus > .8f);
            if (focus > 0f && !TextUtils.isEmpty(entry.description))
                drawText(c, entry.description, x + 116f, y + 44f, 24f, opacity * focus * .8f, false, false);
        }
    }

    private static float itemY(int distance) {
        if (distance == 0) return SELECTED_Y;
        return distance > 0 ? SELECTED_Y + 180f + (distance - 1) * 92f : 122f + (distance + 1) * 92f;
    }

    private void drawDate(Canvas c) {
        text.setTextSize(22f);
        float width = text.measureText(date);
        drawText(c, date, 1824f - width, 112f, 22f, .62f, false, false);
    }

    private void drawText(Canvas c, String value, float x, float y, float size, float alpha, boolean centered, boolean glow) {
        text.setTextSize(size);
        text.setTextAlign(centered ? Paint.Align.CENTER : Paint.Align.LEFT);
        text.setColor(Color.WHITE);
        text.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha))));
        text.setShadowLayer(glow ? 9f : 3f, 0, 1f, glow ? 0xAAFFFFFF : 0xDD000000);
        c.drawText(value, x, y, text);
        text.clearShadowLayer();
    }

    private void drawIcon(Canvas c, String asset, Bitmap ownIcon, float x, float y, float width, float height,
                          float alpha, boolean glow) {
        Bitmap bitmap = asset == null ? ownIcon : ART.get(asset);
        if (bitmap == null) bitmap = ownIcon;
        if (bitmap == null) bitmap = ART.get("icons/Layer_41.png");
        if (bitmap == null || bitmap.isRecycled()) return;
        if (glow) {
            c.save();
            c.translate(x, y);
            c.scale(width * .8f, width * .8f);
            paint.setShader(ICON_GLOW);
            paint.setAlpha(Math.round(alpha * 255f));
            c.drawCircle(0, 0, 1f, paint);
            paint.setShader(null);
            c.restore();
        }
        float s = Math.min(width / bitmap.getWidth(), height / bitmap.getHeight());
        float w = bitmap.getWidth() * s, h = bitmap.getHeight() * s;
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f));
        c.drawBitmap(bitmap, null, rect, paint);
        paint.setAlpha(255);
    }

    private static Bitmap art(Context c, String path) {
        if (ART.containsKey(path)) return ART.get(path);
        Bitmap bitmap = null;
        try (InputStream in = c.getAssets().open("xmb/" + path)) { bitmap = BitmapFactory.decodeStream(in); }
        catch (Exception e) { Log.w(TAG, "XMB art unavailable: " + path); }
        ART.put(path, bitmap);
        return bitmap;
    }

    private static void warmArt(Context c) {
        if (artReady) return;
        for (String path : CATEGORY_ART) {
            Bitmap bitmap = art(c, path);
            if (bitmap != null) bitmap.prepareToDraw();
        }
        for (String path : CORE_ART) {
            Bitmap bitmap = art(c, path);
            if (bitmap != null) bitmap.prepareToDraw();
        }
        artReady = true;
    }

    private static Bitmap fallback(Context c, String theme) {
        int index = "dark".equals(theme) ? 1 : 0;
        if (FALLBACKS[index] != null) return FALLBACKS[index];
        Bitmap gradient = null, waves = null;
        try (InputStream g = c.getAssets().open("xmb/bg_" + theme + "_g.png");
             InputStream r = c.getAssets().open("xmb/bg_" + theme + "_r.png")) {
            gradient = BitmapFactory.decodeStream(g);
            waves = BitmapFactory.decodeStream(r);
            if (gradient == null || waves == null) return null;
            Bitmap image = Bitmap.createBitmap(waves.getWidth(), waves.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            RectF bounds = new RectF(0, 0, image.getWidth(), image.getHeight());
            canvas.drawBitmap(gradient, null, bounds, p);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            canvas.drawBitmap(waves, null, bounds, p);
            FALLBACKS[index] = image;
        } catch (Exception e) { Log.w(TAG, "XMB still unavailable", e); }
        finally {
            if (gradient != null) gradient.recycle();
            if (waves != null) waves.recycle();
        }
        return FALLBACKS[index];
    }

    private String selectedTheme() {
        return "dark".equals(preferences.getString("color_theme", "blue")) ? "dark" : "blue";
    }

    private boolean visibleHome() {
        return isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                && hasWindowFocus() && homeActive;
    }

    private void refreshResources() {
        String nextTheme = selectedTheme();
        if (!nextTheme.equals(colorTheme)) {
            releaseVideo();
            colorTheme = nextTheme;
            decoderFailed = false;
            still.setImageBitmap(fallback(getContext(), colorTheme));
        }
        animatedWave = preferences.getBoolean("animated_wave", true);
        menuSounds = preferences.getBoolean("menu_sounds", true);
        boolean visible = visibleHome();
        if (visible && animatedWave && !decoderFailed && video.isAvailable()) startVideo();
        else releaseVideo();
        if (visible && menuSounds) loadSounds();
        else releaseSounds();
    }

    private void startVideo() {
        if (player != null) return;
        try {
            videoSurface = new Surface(video.getSurfaceTexture());
            final MediaPlayer next = new MediaPlayer();
            player = next;
            next.setSurface(videoSurface);
            next.setVolume(0f, 0f);
            next.setLooping(true);
            try (AssetFileDescriptor file = getContext().getAssets().openFd("xmb/bg_" + colorTheme + ".mp4")) {
                next.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            }
            next.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    if (player != mp || !visibleHome() || !animatedWave) return;
                    videoWidth = mp.getVideoWidth();
                    videoHeight = mp.getVideoHeight();
                    fitVideo();
                    mp.start();
                }
            });
            next.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (mp == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        video.setAlpha(1f);
                        still.setVisibility(INVISIBLE);
                    }
                    return false;
                }
            });
            next.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (mp == player) {
                        decoderFailed = true;
                        releaseVideo();
                    }
                    return true;
                }
            });
            next.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "XMB video unavailable; keeping static waves", e);
            decoderFailed = true;
            releaseVideo();
        }
    }

    private void fitVideo() {
        if (videoWidth <= 0 || videoHeight <= 0 || video.getWidth() <= 0 || video.getHeight() <= 0) return;
        float viewRatio = video.getWidth() / (float) video.getHeight();
        float videoRatio = videoWidth / (float) videoHeight;
        Matrix transform = new Matrix();
        if (videoRatio > viewRatio) transform.setScale(videoRatio / viewRatio, 1f, video.getWidth() / 2f, video.getHeight() / 2f);
        else transform.setScale(1f, viewRatio / videoRatio, video.getWidth() / 2f, video.getHeight() / 2f);
        video.setTransform(transform);
    }

    private void releaseVideo() {
        if (still != null) still.setVisibility(VISIBLE);
        if (video != null) video.setAlpha(0f);
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            old.setOnPreparedListener(null);
            old.setOnErrorListener(null);
            old.setOnInfoListener(null);
            try { old.release(); } catch (RuntimeException ignored) { }
        }
        if (videoSurface != null) { videoSurface.release(); videoSurface = null; }
    }

    private void loadSounds() {
        if (soundPool != null) return;
        soundPool = new SoundPool.Builder().setMaxStreams(3)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        for (String name : new String[] { "cursor", "category", "decide", "cancel" }) {
            try (AssetFileDescriptor fd = getContext().getAssets().openFd("xmb/sounds/" + name + ".wav")) {
                sounds.put(name, soundPool.load(fd, 1));
            } catch (Exception e) { Log.w(TAG, "XMB sound unavailable: " + name); }
        }
    }

    private void play(String name) {
        if (!menuSounds || soundPool == null) return;
        Integer sound = sounds.get(name);
        if (sound != null) soundPool.play(sound, .18f, .18f, 1, 0, 1f);
    }

    private void releaseSounds() {
        if (soundPool != null) { soundPool.release(); soundPool = null; }
        sounds.clear();
    }
}
