package org.totalrecall.listening;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A fixture created by the installed 0.10.0 app and read after package replacement. */
public final class UpgradeProbe extends Instrumentation {
    private static final String PREFS = "upgrade_test_fixture";
    private static final String FEEDBACK = "keep";
    private static final String TITLE = "Upgrade fixture";
    private static final String CORRECTION = "Preserve the blue book";
    private String phase;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args == null ? "" : args.getString("phase", "");
        start();
    }

    @Override public void onStart() {
        Bundle receipt = new Bundle();
        Context target = getTargetContext();
        SharedPreferences fixture = target.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try (Store store = new Store(target)) {
            if ("seed".equals(phase)) {
                String marker = "upgrade-" + UUID.randomUUID();
                Method save = Store.class.getDeclaredMethod("save", SelectiveEngine.Clip.class);
                save.setAccessible(true);
                SelectiveEngine engine = new SelectiveEngine(new SelectiveEngine.Listener() {
                    @Override public void onClip(SelectiveEngine.Clip clip) {
                        try { save.invoke(store, clip); }
                        catch (Exception error) { throw new IllegalStateException(error); }
                    }
                    @Override public void onPauseRequested() { throw new AssertionError("Unexpected pause"); }
                });
                short[] pcm = new short[SelectiveEngine.RATE];
                for (int i = 0; i < pcm.length; i++) pcm[i] = (short)((i % 80) * 13 - 520);
                engine.pcm(pcm, pcm.length);
                engine.text("I decided to use the blue cover for the book " + marker, true);
                engine.finish();
                Store.Moment row = exactlyOne(store.search(marker));
                store.edit(row.id, TITLE, marker, CORRECTION);
                store.feedback(row.id, FEEDBACK);
                String digest = digest(row.audio);
                check(digest.equals(row.sha256), "Installed source WAV digest mismatch");
                check(fixture.edit().putString("marker", marker).putString("digest", digest)
                        .putString("uid", row.uid).putLong("id", row.id).putLong("bytes", row.audio.length())
                        .commit(), "Could not persist upgrade witness");
                receipt.putString("detail", "Created a WAV and catalog row with feedback, original transcript, title and correction.");
            } else if ("verify".equals(phase)) {
                String marker = fixture.getString("marker", null);
                check(marker != null, "Package replacement lost the fixture");
                Store.Moment row = exactlyOne(store.search(marker));
                check(row.id == fixture.getLong("id", -1) && row.uid.equals(fixture.getString("uid", "")),
                        "Catalog identity changed during upgrade");
                check(row.title.equals(TITLE) && row.note.equals(marker) && row.correction.equals(CORRECTION)
                        && row.feedback.equals(FEEDBACK) && row.transcript.contains("blue cover"),
                        "Catalog content or feedback changed");
                check(row.audio.length() == fixture.getLong("bytes", -1) && row.sha256.equals(fixture.getString("digest", ""))
                        && digest(row.audio).equals(row.sha256), "WAV changed during upgrade");
                receipt.putString("detail", "The prior UID, row ID, original transcript, edits, feedback and "
                        + row.audio.length() + " bytes of WAV audio survived package replacement; SHA-256 verified.");
                store.delete(row.id);
                check(fixture.edit().clear().commit(), "Could not clear synthetic test witness");
            } else {
                throw new IllegalArgumentException("Expected seed or verify");
            }
            receipt.putString("outcome", "PASS_upgrade_" + phase);
            finish(Activity.RESULT_OK, receipt);
        } catch (Throwable error) {
            receipt.putString("outcome", "FAIL_upgrade_" + phase);
            receipt.putString("detail", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, receipt);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Store.Moment exactlyOne(List<Store.Moment> found) {
        check(found.size() == 1, "Expected exactly one fixture");
        return found.get(0);
    }
    private static String digest(File path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] data = new byte[8192];
            for (int read; (read = input.read(data)) != -1;) digest.update(data, 0, read);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
