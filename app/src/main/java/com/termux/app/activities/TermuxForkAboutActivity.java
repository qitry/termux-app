package com.termux.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.BuildConfig;
import com.termux.R;

public class TermuxForkAboutActivity extends AppCompatActivity {

    private static final String AUTHOR_GITHUB_URL = "https://github.com/qitry";
    private static final String PROJECT_GITHUB_URL = "https://github.com/qitry/termux-app";
    private static final String AUTHOR_EMAIL = "lyl518@outlook.com";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux_fork_about);

        ((ImageButton) findViewById(R.id.about_back)).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.about_version)).setText(
            getString(R.string.termux_fork_about_version, BuildConfig.VERSION_NAME));

        findViewById(R.id.about_author_github).setOnClickListener(v -> openUrl(AUTHOR_GITHUB_URL));
        findViewById(R.id.about_project_github).setOnClickListener(v -> openUrl(PROJECT_GITHUB_URL));
        findViewById(R.id.about_email).setOnClickListener(v ->
            startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + AUTHOR_EMAIL))));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
    }
}
