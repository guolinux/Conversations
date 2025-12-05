package eu.siacs.conversations.ui.fragment.settings;

import static eu.siacs.conversations.ui.background.PatternGenerator.PatternType.*;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.DynamicColors;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.activity.SettingsActivity;
import eu.siacs.conversations.ui.background.PatternGenerator;
import eu.siacs.conversations.ui.background.WallpaperStorage;
import eu.siacs.conversations.ui.util.SettingsUtils;

public class InterfaceSettingsFragment extends XmppPreferenceFragment {
    private final ActivityResultLauncher<String[]> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );

                        AppSettings settings = new AppSettings(requireContext());
                        settings.setChatBackgroundUri(uri.toString());

                        Toast.makeText(requireContext(), R.string.save, Toast.LENGTH_SHORT).show();

                    } catch (SecurityException e) {
                        e.printStackTrace();
                    }
                }
            });

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_interface, rootKey);
        final var themePreference = findPreference("theme");
        final var dynamicColors = findPreference("dynamic_colors");
        if (themePreference == null || dynamicColors == null) {
            throw new IllegalStateException(
                    "The preference resource file did not contain theme or color preferences");
        }
        themePreference.setOnPreferenceChangeListener(
                (preference, newValue) -> {
                    if (newValue instanceof final String theme) {
                        final int desiredNightMode = AppSettings.getDesiredNightMode(theme);
                        requireSettingsActivity().setDesiredNightMode(desiredNightMode);
                    }
                    return true;
                });
        dynamicColors.setVisible(DynamicColors.isDynamicColorAvailable());
        dynamicColors.setOnPreferenceChangeListener(
                (preference, newValue) -> {
                    requireSettingsActivity().setDynamicColors(Boolean.TRUE.equals(newValue));
                    return true;
                });
        Preference chatBackgroundPref = findPreference("pref_choose_chat_theme");
        if (chatBackgroundPref != null) {
            chatBackgroundPref.setOnPreferenceClickListener(preference -> {
                showBackgroundSelectorDialog(); // le modal pour le preview
                return true;
            });
        }
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        if (key.equals(AppSettings.ALLOW_SCREENSHOTS)) {
            SettingsUtils.applyScreenshotSetting(requireActivity());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_title_interface);
    }

    public SettingsActivity requireSettingsActivity() {
        final var activity = requireActivity();
        if (activity instanceof SettingsActivity settingsActivity) {
            return settingsActivity;
        }
        throw new IllegalStateException(
                String.format(
                        "%s is not %s",
                        activity.getClass().getName(), SettingsActivity.class.getName()));
    }

    private void showBackgroundSelectorDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_background_selector, null, false);
        dialog.setContentView(view);

        GridLayout grid = view.findViewById(R.id.gridChatBackgrounds);
        AppSettings settings = new AppSettings(requireContext());

        final int currentSaved = settings.getChatBackgroundRes();
        final TextView[] lastSelectedBadge = {null};

        final PatternGenerator.PatternType[] patterns = {
                SOFT_GRADIENT,
                RADIAL_GRADIENT,
                SOFT_MESH,
                GRAINY_PAPER,
                FLOATING_SPHERES,
                SCRIBBLE_PRO,
                PLASMA
        };

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int columns = screenWidth > 900 ? 3 : 2;
        grid.setColumnCount(columns);

        int size = (screenWidth / columns) - (int) (32 * getResources().getDisplayMetrics().density);

        FrameLayout uploadItem = createUploadItem(size);
        grid.addView(uploadItem);
        uploadItem.setOnClickListener(v -> openImagePicker());

        FrameLayout noWallpaperItem = createNoWallpaperItem(size);
        grid.addView(noWallpaperItem);

        TextView badgeNone = createSelectionBadge();
        if (currentSaved == -1) badgeNone.setVisibility(View.VISIBLE);
        noWallpaperItem.addView(badgeNone);

        noWallpaperItem.setOnClickListener(v -> {
            clearLastBadge(lastSelectedBadge);
            badgeNone.setVisibility(View.VISIBLE);
            lastSelectedBadge[0] = badgeNone;

            settings.setChatBackgroundRes(-1);
            settings.setChatBackgroundUri(null);

            dialog.dismiss();
        });
        boolean isDarkMode = (
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES
        );
        DisplayMetrics metrics = requireContext().getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        for (int i = 0; i < patterns.length; i++) {

            FrameLayout item = new FrameLayout(requireContext());
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = size;
            p.height = size;
            p.setMargins(16, 16, 16, 16);
            item.setLayoutParams(p);

            int patternId = ("pattern_" + i).hashCode();
            item.setTag(patternId);

            Bitmap previewBmp = PatternGenerator.generate(
                    requireContext(),
                    patterns[i],
                    400,
                    isDarkMode
            );

            ImageView preview = new ImageView(requireContext());
            preview.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setImageBitmap(previewBmp);
            item.addView(preview);

            TextView badge = createSelectionBadge();
            if (currentSaved == patternId) badge.setVisibility(View.VISIBLE);
            item.addView(badge);

            int finalI = i;

            item.setOnClickListener(v -> {

                clearLastBadge(lastSelectedBadge);
                badge.setVisibility(View.VISIBLE);
                lastSelectedBadge[0] = badge;

                int id = patternId;

                settings.setChatBackgroundLightUri(null);
                settings.setChatBackgroundDarkUri(null);

                settings.setChatBackgroundRes(id);

                Context appCtx = requireContext().getApplicationContext();
                PatternGenerator.PatternType type = patterns[finalI];

                new Thread(() -> {
                    try {
                        Bitmap light = PatternGenerator.generate(appCtx, type, Math.max(width, height), false);
                        Bitmap dark  = PatternGenerator.generate(appCtx, type, Math.max(width, height), true);

                        Uri lightUri = WallpaperStorage.saveWallpaper(appCtx, light, "light_" + id + ".jpg");
                        Uri darkUri  = WallpaperStorage.saveWallpaper(appCtx, dark, "dark_" + id + ".jpg");

                        if (lightUri != null)
                            settings.setChatBackgroundLightUri(lightUri.toString());

                        if (darkUri != null)
                            settings.setChatBackgroundDarkUri(darkUri.toString());

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
                Toast.makeText(requireContext(), R.string.save, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            grid.addView(item);
        }

        dialog.show();
    }
    private FrameLayout createUploadItem(int size) {
        FrameLayout item = new FrameLayout(requireContext());
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = size;
        params.height = size;
        params.setMargins(16, 16, 16, 16);
        item.setLayoutParams(params);

        item.setBackgroundResource(R.drawable.bg_upload_placeholder);

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(R.drawable.ic_camera_alt_24dp);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        icon.setLayoutParams(ip);
        item.addView(icon);

        return item;
    }

    private TextView createSelectionBadge() {
        TextView badge = new TextView(requireContext());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        params.setMargins(8, 8, 8, 8);

        badge.setLayoutParams(params);
        badge.setText("✔");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(12);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#4CAF50"));
        badge.setBackground(bg);

        badge.setPadding(10, 8, 10, 8);
        badge.setVisibility(View.GONE);

        return badge;
    }
    private FrameLayout createNoWallpaperItem(int size) {
        FrameLayout item = new FrameLayout(requireContext());
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = size;
        params.height = size;
        params.setMargins(16, 16, 16, 16);
        item.setLayoutParams(params);

        ImageView iv = new ImageView(requireContext());
        iv.setImageResource(R.drawable.ic_no_wallpaper);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        item.addView(iv);
        return item;
    }
    private void clearLastBadge(TextView[] lastSelectedBadge) {
        if (lastSelectedBadge[0] != null) {
            lastSelectedBadge[0].setVisibility(View.GONE);
        }
    }
    private void openImagePicker() {
        imagePickerLauncher.launch(new String[]{"image/*"});
    }


}
