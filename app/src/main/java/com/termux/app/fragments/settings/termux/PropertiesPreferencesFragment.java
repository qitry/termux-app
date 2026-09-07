package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Keep
public class PropertiesPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(TermuxPropertiesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_properties_preferences, rootKey);
    }

}

class TermuxPropertiesDataStore extends PreferenceDataStore {

    private final Context mContext;

    private static TermuxPropertiesDataStore mInstance;

    private TermuxPropertiesDataStore(Context context) {
        mContext = context;
    }

    public static synchronized TermuxPropertiesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TermuxPropertiesDataStore(context);
        }
        return mInstance;
    }

    /**
     * Read the raw properties files directly instead of the parsed in-memory properties:
     * TermuxSharedProperties replaces some keys during parsing (e.g. use-black-ui),
     * which would make switches snap back to off after saving.
     */
    @Override
    public String getString(String key, String defValue) {
        if (key == null) return defValue;
        Properties merged = new Properties();
        for (String path : TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST) {
            File file = new File(path);
            if (!file.isFile()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                merged.load(reader);
            } catch (IOException ignored) { }
        }
        String value = merged.getProperty(key);
        return value != null ? value : defValue;
    }

    @Override
    public void putString(String key, String value) {
        if (key == null) return;
        if (value == null || value.trim().isEmpty()) return;
        writeProperty(key, value.trim());
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        String value = getString(key, null);
        if (value == null) return defValue;
        return "true".equalsIgnoreCase(value.trim());
    }

    @Override
    public void putBoolean(String key, boolean value) {
        if (key == null) return;
        writeProperty(key, String.valueOf(value));
    }

    private void writeProperty(String key, String value) {
        File file = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Toast.makeText(mContext, R.string.termux_properties_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> lines = new ArrayList<>();
        boolean found = false;
        if (file.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("#") && trimmed.startsWith(key + "=")) {
                        lines.add(key + "=" + value);
                        found = true;
                    } else if (!trimmed.startsWith("#") && trimmed.startsWith(key + " ")) {
                        // "key = value" style
                        lines.add(key + "=" + value);
                        found = true;
                    } else {
                        lines.add(line);
                    }
                }
            } catch (IOException e) {
                Toast.makeText(mContext, R.string.termux_properties_save_failed, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (!found) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) lines.add("");
            lines.add(key + "=" + value);
        }

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException e) {
            Toast.makeText(mContext, R.string.termux_properties_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(mContext, R.string.termux_properties_saved, Toast.LENGTH_SHORT).show();
    }
}
