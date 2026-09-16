package com.webapp2apk.generated;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.webkit.JavascriptInterface;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * Exposed to the WebView as window.AndroidNfc. Call from the page as:
 *
 *   AndroidNfc.isAvailable();          // true/false, check before using
 *   AndroidNfc.writeTextOnNextTap(s);  // arms a pending write; next tag
 *                                      // tapped gets this text written to it
 *   window.onNfcTag = function(text, tagId) { ... };  // called on every tap
 *
 * A tap that hits a tag with no NDEF text record (or no NDEF at all) still
 * calls onNfcTag with an empty text and the tag's raw ID - useful for
 * "just identify which physical tag/card this is" use cases (asset
 * tagging, loyalty cards) that don't need any data written first.
 *
 * Read/write only works while the app is in the foreground (see
 * MainActivity's enableForegroundDispatch/onNewIntent) - there is no
 * background NFC scanning, by design, since that would need the app to
 * declare itself as a default tag handler system-wide.
 */
final class NfcBridge {

    private final MainActivity activity;

    NfcBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return activity.isNfcAvailable();
    }

    @JavascriptInterface
    public void writeTextOnNextTap(String text) {
        activity.armPendingNfcWrite(text);
    }

    // --- NDEF encode/decode helpers, used by MainActivity's tag dispatch ---

    static NdefMessage buildTextMessage(String text) {
        byte[] langBytes = "en".getBytes(Charset.forName("US-ASCII"));
        byte[] textBytes;
        try {
            textBytes = text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            textBytes = text.getBytes();
        }
        byte[] payload = new byte[1 + langBytes.length + textBytes.length];
        payload[0] = (byte) langBytes.length; // status byte: UTF-8, lang code length
        System.arraycopy(langBytes, 0, payload, 1, langBytes.length);
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.length, textBytes.length);

        NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, new byte[0], payload);
        return new NdefMessage(new NdefRecord[]{record});
    }

    /** Returns "" (never null) if the tag has no readable NDEF text record. */
    static String readTextFrom(NdefMessage message) {
        if (message == null) return "";
        for (NdefRecord record : message.getRecords()) {
            if (record.getTnf() != NdefRecord.TNF_WELL_KNOWN) continue;
            if (!java.util.Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) continue;
            try {
                byte[] payload = record.getPayload();
                int langLength = payload[0] & 0x3F;
                boolean isUtf8 = (payload[0] & 0x80) == 0;
                String encoding = isUtf8 ? "UTF-8" : "UTF-16";
                return new String(payload, 1 + langLength, payload.length - 1 - langLength, encoding);
            } catch (Exception ignored) {
                // Malformed record on this tag - skip it, keep looking.
            }
        }
        return "";
    }

    static String tagIdToHex(Tag tag) {
        if (tag == null || tag.getId() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : tag.getId()) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
