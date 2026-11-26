package org.totalrecall.listening;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Decodes the same 16 kHz mono PCM that enters the volatile audio window. Single worker thread. */
public final class OfflineSpeech implements AutoCloseable {
    private static final String MODEL = "vosk-model-small-en-us-0.15";
    private Model model;
    private Recognizer recognizer;
    private Recognizer commandRecognizer;
    private String pendingCommand;
    private static final String KEEP = "lexi keep the last minute";
    private static final String PAUSE = "lexi pause recall";
    private static final String MISSED = "lexi you missed one";

    public OfflineSpeech(Context context) throws IOException {
        this(context,true);
    }
    OfflineSpeech(Context context,boolean commands) throws IOException {
        File dir = unpack(context.getApplicationContext());
        try {
            model = new Model(dir.getAbsolutePath());
            recognizer = new Recognizer(model, 16000f);
            if(!commands)return;
            org.json.JSONArray grammar = new org.json.JSONArray();
            grammar.put(KEEP).put(PAUSE).put(MISSED).put("[unk]");
            commandRecognizer = new Recognizer(model, 16000f, grammar.toString());
        } catch (IOException | RuntimeException | LinkageError e) {
            close();
            throw new IOException("Offline speech could not start", e);
        }
    }

    /** Returns the finalized utterance on an endpoint and null on other frames. */
    public String accept(short[] pcm, int count) {
        if (commandRecognizer!=null && commandRecognizer.acceptWaveForm(pcm, count)) {
            String heard = text(commandRecognizer.getResult(), "text");
            if (KEEP.equals(heard) || PAUSE.equals(heard) || MISSED.equals(heard)) {
                pendingCommand = heard;
            }
        }
        return recognizer.acceptWaveForm(pcm, count) ? text(recognizer.getResult(), "text") : null;
    }

    /** An exact full-utterance command from a restricted decoder that also models unknown words. */
    public String pollCommand() {
        String heard = pendingCommand;
        pendingCommand = null;
        return heard;
    }

    /** Clear all speech hypotheses at an explicit recording boundary on the decoder thread. */
    public void resetStream() {
        recognizer.reset();
        commandRecognizer.reset();
        pendingCommand = null;
    }

    public String partial() {
        return text(recognizer.getPartialResult(), "partial");
    }

    public String finish() {
        return text(recognizer.getFinalResult(), "text");
    }

    private static String text(String json, String key) {
        try {
            String value = new JSONObject(json).optString(key, "").trim();
            return value.isEmpty() ? null : value;
        } catch (JSONException e) {
            Log.w("TotalRecallSpeech", "Offline recognizer returned an unreadable result");
            return null;
        }
    }

    @Override public void close() {
        if (commandRecognizer != null) {
            commandRecognizer.close();
            commandRecognizer = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
    }

    /** Build once in private storage. Only a complete staging directory can become the live model. */
    private static synchronized File unpack(Context context) throws IOException {
        File base = context.getNoBackupFilesDir();
        File target = new File(base, MODEL);
        if (target.isDirectory()) return target;
        File stage = new File(base, MODEL + ".installing");
        remove(stage);
        if (!stage.mkdirs()) throw new IOException("Cannot prepare local speech model");
        String prefix = MODEL + "/";
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                context.getAssets().open("offline-speech/" + MODEL + ".zip")))) {
            byte[] buffer = new byte[65536];
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.isDirectory()) continue;
                if (!entry.getName().startsWith(prefix)) throw new IOException("Unexpected model contents");
                File out = new File(stage, entry.getName().substring(prefix.length()));
                if (!out.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator)) {
                    throw new IOException("Invalid model path");
                }
                File parent = out.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create model directory");
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buffer)) != -1;) stream.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
            if (!new File(stage, "am/final.mdl").isFile() || !new File(stage, "graph/Gr.fst").isFile()
                    || !new File(stage, "conf/model.conf").isFile()) throw new IOException("Bundled model incomplete");
            if (!stage.renameTo(target)) throw new IOException("Cannot activate offline speech model");
        } finally {
            remove(stage);
        }
        return target;
    }

    private static void remove(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new IOException("Cannot clear incomplete speech model");
    }
}
