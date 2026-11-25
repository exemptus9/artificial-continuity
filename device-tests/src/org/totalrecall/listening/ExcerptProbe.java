package org.totalrecall.listening;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;

final class ExcerptProbe {
    static void verify(Context context, Store store, Store.Moment original) throws Exception {
        byte[] before = Files.readAllBytes(original.audio.toPath());
        int start = 611;
        int end = 7097;
        long row = store.saveExcerpt(original.id, start, end, "Synthetic excerpt");
        try {
            Store.Moment cut = store.get(row);
            byte[] trimmed = Files.readAllBytes(cut.audio.toPath());
            check(!cut.uid.equals(original.uid) && original.uid.equals(cut.parentId));
            check(cut.startSample == start && cut.endSample == end && trimmed.length == 44 + 2 * (end-start));
            check(Arrays.equals(Arrays.copyOfRange(before, 44+2*start, 44+2*end),
                    Arrays.copyOfRange(trimmed, 44, trimmed.length)));
            check(Arrays.equals(before, Files.readAllBytes(original.audio.toPath())));

            ByteArrayOutputStream zip = new ByteArrayOutputStream();
            store.exportZip(zip, Arrays.asList(original.id, row));
            final String prefix = "testing-" + UUID.randomUUID() + "-";
            final File directory = new File(context.getCacheDir(), prefix);
            check(directory.mkdir());
            Context privateVault = new ContextWrapper(context) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return directory; }
                @Override public File getDatabasePath(String name) { return context.getDatabasePath(prefix + name); }
                @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                        SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errors) {
                    return context.openOrCreateDatabase(prefix + name, mode, factory, errors);
                }
            };
            try (Store restored = new Store(privateVault)) {
                restored.importZip(new ByteArrayInputStream(zip.toByteArray()));
                boolean parent = false, child = false;
                for (Store.Moment moment : restored.search("")) {
                    if (moment.uid.equals(original.uid)) parent = Arrays.equals(before, Files.readAllBytes(moment.audio.toPath()));
                    if (moment.uid.equals(cut.uid)) child = cut.parentId.equals(moment.parentId)
                            && moment.startSample == start && moment.endSample == end
                            && Arrays.equals(trimmed, Files.readAllBytes(moment.audio.toPath()));
                }
                check(parent && child);
            } finally {
                context.deleteDatabase(prefix + "recall-listening.db");
                File audio = new File(directory, "listening-audio");
                File[] files = audio.listFiles();
                if (files != null) for (File file : files) file.delete();
                audio.delete(); directory.delete();
            }
        } finally {
            store.delete(row);
        }
    }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Excerpt PCM, source immutability, relationship or clean-vault ZIP round-trip failed");
    }
}
