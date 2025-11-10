package eu.siacs.conversations.ui.fragment.settings;

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;

public class InterfaceBubblesSettingsFragment extends XmppPreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_interface_bubbles, rootKey);
        final var showAvatars11 = findPreference(AppSettings.SHOW_AVATARS_11);
        final var showAvatarsAccount = findPreference(AppSettings.SHOW_AVATARS_ACCOUNTS);
        final SwitchPreferenceCompat alignStart = findPreference(AppSettings.ALIGN_START);
        if (showAvatars11 == null || showAvatarsAccount == null || alignStart == null) {
            throw new IllegalStateException(
                    "The preference resource file is missing some preferences");
        }
        Preference chatBackgroundPref = findPreference("pref_choose_chat_theme");
        if (chatBackgroundPref != null) {
            chatBackgroundPref.setOnPreferenceClickListener(preference -> {
                showBackgroundSelectorDialog(); // le modal pour le preview
                return true;
            });
        }
        updateShowAvatars11Summary(showAvatars11, alignStart.isChecked());
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        switch (key) {
            case AppSettings.ALIGN_START -> runOnUiThread(this::updateShowAvatars11Summary);
            case AppSettings.SHOW_CONNECTION_OPTIONS -> reconnectAccounts();
        }
    }

    private void updateShowAvatars11Summary() {
        final var showAvatars11 = findPreference(AppSettings.SHOW_AVATARS_11);
        updateShowAvatars11Summary(showAvatars11);
    }

    private void updateShowAvatars11Summary(final Preference preference) {
        final var appSettings = new AppSettings(requireContext());
        updateShowAvatars11Summary(preference, appSettings.isAlignStart());
    }

    private void updateShowAvatars11Summary(final Preference preference, final boolean alignStart) {
        preference.setSummary(
                alignStart
                        ? R.string.pref_show_11_chats_summary
                        : R.string.pref_show_11_sender_chats_summary);
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_title_bubbles);
    }

    private void showBackgroundSelectorDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_background_selector, null, false);
        dialog.setContentView(view);
        final int[] wallpapers = {
                R.drawable.theme_1,
                R.drawable.theme_2,
                R.drawable.theme_3,
                R.drawable.theme_4,
                R.drawable.theme_5,
                R.drawable.theme_6,
                R.drawable.theme_7,
                R.drawable.theme_8,
                R.drawable.theme_9
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

        for (int resId : wallpapers) {
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
            imageView.setImageResource(resId);

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
            gd.setColor(ContextCompat.getColor(requireContext(),R.color.green_800));
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

                Toast.makeText(requireContext(), R.string.save, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            grid.addView(itemContainer);
        }

        dialog.show();
    }
}
