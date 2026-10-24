package io.github.clashautoproxy;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

public final class UiApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        int theme = getSharedPreferences("appearance", MODE_PRIVATE).getInt("mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(theme);
        DynamicColors.applyToActivitiesIfAvailable(this, new DynamicColorsOptions.Builder()
                .setPrecondition((activity, id) -> getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("dynamic", true))
                .build());
    }
}
