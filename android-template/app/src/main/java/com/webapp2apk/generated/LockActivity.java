package com.webapp2apk.generated;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

/**
 * Full-screen gate shown whenever App Lock is enabled and the app hasn't
 * been unlocked yet for its current time in the foreground. Everything
 * about the actual challenge - fingerprint, face unlock, or the phone's own
 * PIN/pattern/password as a fallback - is handled entirely by the OS via
 * BiometricPrompt; this app never sees or stores anything about it.
 */
public class LockActivity extends AppCompatActivity {

    private boolean promptShowing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        TextView retryButton = findViewById(R.id.lockRetryButton);
        retryButton.setOnClickListener(v -> promptUnlock());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Covers both the first time this screen appears and coming back to
        // it after a cancelled/failed attempt without double-showing the
        // system prompt on top of itself.
        if (!promptShowing) {
            promptUnlock();
        }
    }

    private void promptUnlock() {
        promptShowing = true;

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_name))
                .setSubtitle("Unlock to continue")
                .setAllowedAuthenticators(AppLockManager.allowedAuthenticators())
                .build();

        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        App.sessionUnlocked = true;
                        setResult(RESULT_OK);
                        finish();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // User cancelled, hit "use another method" and backed out, or ran
                        // out of attempts - leave them on the lock screen with a manual
                        // retry button rather than guessing what they meant.
                        promptShowing = false;
                    }
                });

        try {
            prompt.authenticate(promptInfo);
        } catch (Exception e) {
            // The device claimed to support this during the earlier capability
            // check but can't actually run it now - fail open rather than
            // locking the person out of their own app permanently.
            App.sessionUnlocked = true;
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        // Leaving via back sends the person to the home screen rather than
        // exposing the locked WebView content underneath.
        moveTaskToBack(true);
    }
}
