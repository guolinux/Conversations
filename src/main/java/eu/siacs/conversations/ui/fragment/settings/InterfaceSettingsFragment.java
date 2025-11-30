package eu.siacs.conversations.ui.fragment.settings;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.DynamicColors;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.activity.SettingsActivity;
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
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_background_selector, null, false);
        dialog.setContentView(view);

        final Integer[] wallpapers = {
                -1,
                R.drawable.theme_1,
                R.drawable.theme_2,
                R.drawable.theme_3,
                R.drawable.theme_4,
                R.drawable.theme_5,
                R.drawable.theme_6,
                R.drawable.theme_7,
                R.drawable.theme_8
        };

        GridLayout grid = view.findViewById(R.id.gridChatBackgrounds);
        AppSettings settings = new AppSettings(requireContext());
        final int currentSaved = settings.getChatBackgroundRes();

        final int[] selectedWallpaper = { currentSaved };
        final TextView[] lastSelectedBadge = { null };
        final FrameLayout[] lastSelectedContainer = { null };

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int columnCount = screenWidth > 900 ? 3 : 2;
        int imageSize = (screenWidth / columnCount) - (int) (24 * getResources().getDisplayMetrics().density);

        grid.setColumnCount(columnCount);
        grid.setAlignmentMode(GridLayout.ALIGN_MARGINS);
        grid.setUseDefaultMargins(true);
        FrameLayout uploadContainer = new FrameLayout(requireContext());
        GridLayout.LayoutParams uploadParams = new GridLayout.LayoutParams();
        uploadParams.width = imageSize;
        uploadParams.height = imageSize;
        uploadParams.setMargins(12, 12, 12, 12);
        uploadContainer.setLayoutParams(uploadParams);
        uploadContainer.setBackgroundResource(R.drawable.bg_upload_placeholder);

        ImageView plusIcon = new ImageView(requireContext());
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        plusIcon.setLayoutParams(iconParams);
        plusIcon.setImageResource(R.drawable.ic_camera_alt_24dp);
        uploadContainer.addView(plusIcon);

        uploadContainer.setOnClickListener(v -> openImagePicker());

        grid.addView(uploadContainer);
        for (Integer resId : wallpapers) {
            FrameLayout itemContainer = new FrameLayout(requireContext());
            GridLayout.LayoutParams containerParams = new GridLayout.LayoutParams();
            containerParams.width = imageSize;
            containerParams.height = imageSize;
            containerParams.setMargins(12, 12, 12, 12);
            itemContainer.setLayoutParams(containerParams);
            itemContainer.setTag(resId);

            ImageView imageView = new ImageView(requireContext());
            FrameLayout.LayoutParams ivParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            imageView.setLayoutParams(ivParams);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);

            if (resId != -1) {
                imageView.setImageResource(resId);
            }
            else {
                imageView.setImageResource(R.drawable.ic_no_wallpaper);
            }


            TextView badge = new TextView(requireContext());
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            int badgeMargin = (int) (6 * getResources().getDisplayMetrics().density);
            badgeParams.setMargins(badgeMargin, badgeMargin, badgeMargin, badgeMargin);
            badge.setLayoutParams(badgeParams);
            badge.setText("\u2713");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(12f);
            badge.setPadding(10, 6, 10, 6);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(ContextCompat.getColor(requireContext(), R.color.green_800));
            badge.setBackground(gd);
            badge.setVisibility(View.GONE);

            if (resId == currentSaved) {
                badge.setVisibility(View.VISIBLE);
                lastSelectedBadge[0] = badge;
                lastSelectedContainer[0] = itemContainer;
            }

            itemContainer.addView(imageView);
            itemContainer.addView(badge);

            itemContainer.setOnClickListener(v -> {
                Integer id = (Integer) v.getTag();
                if (id == null) return;

                if (lastSelectedBadge[0] != null && lastSelectedBadge[0] != badge) {
                    lastSelectedBadge[0].setVisibility(View.GONE);
                }

                badge.setVisibility(View.VISIBLE);
                lastSelectedBadge[0] = badge;
                lastSelectedContainer[0] = itemContainer;

                settings.setChatBackgroundRes(id);
                selectedWallpaper[0] = id;

                if (id == -1) {
                    Toast.makeText(requireContext(), R.string.delete, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), R.string.save, Toast.LENGTH_SHORT).show();
                }

                dialog.dismiss();
            });

            grid.addView(itemContainer);
        }


        dialog.show();
    }

    private void openImagePicker() {
        imagePickerLauncher.launch(new String[]{"image/*"});
    }


}
