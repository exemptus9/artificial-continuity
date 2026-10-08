package org.continuity.recall;

import android.content.Context;
import android.security.keystore.*;
import android.util.AtomicFile;
import java.io.*;
import java.security.KeyStore;
import javax.crypto.*;
import org.json.*;

public final class Vault {
  static final String ALIAS = "total-recall-v1";

  public interface Change {
    void apply(JSONObject s) throws Exception;
  }

  static SecretKey key() throws Exception {
    KeyStore k = KeyStore.getInstance("AndroidKeyStore");
    k.load(null);
    if (!k.containsAlias(ALIAS)) {
      KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(256)
              .build());
      g.generateKey();
    }
    return (SecretKey) k.getKey(ALIAS, null);
  }

  static AtomicFile file(Context c) {
    return new AtomicFile(new File(c.getNoBackupFilesDir(), "vault.v1"));
  }

  public static synchronized JSONObject read(Context c) throws Exception {
    AtomicFile f = file(c);
    if (!f.getBaseFile().exists() && !new File(f.getBaseFile() + ".bak").exists()) {
      JSONObject s = Core.initial(System.currentTimeMillis());
      write(c, s);
      return s;
    }
    // Missing/corrupt key or ciphertext never silently initializes a new archive.
    JSONObject s =
        new JSONObject(
            new String(
                Crypto.decrypt(key(), f.readFully()), java.nio.charset.StandardCharsets.UTF_8));
    if (!Core.STORE.equals(s.getString("format")))
      throw new Exception(
          "Store version unsupported; export with the previous app before migrating");
    return s;
  }

  static void write(Context c, JSONObject s) throws Exception {
    byte[] raw = Core.utf8(s.toString());
    if (raw.length > Core.MAX_ARCHIVE) throw new Exception("Local store capacity reached");
    byte[] encrypted = Crypto.encrypt(key(), raw);
    AtomicFile f = file(c);
    FileOutputStream out = null;
    try {
      out = f.startWrite();
      out.write(encrypted);
      f.finishWrite(out);
    } catch (Exception e) {
      if (out != null) f.failWrite(out);
      throw e;
    }
  }

  public static synchronized JSONObject change(Context c, Change change) throws Exception {
    JSONObject s = read(c);
    change.apply(s);
    write(c, s);
    return s;
  }

  public static synchronized void replace(Context c, JSONObject state) throws Exception {
    write(c, state);
  }

  public static synchronized void remove(Context c) throws Exception {
    file(c).delete();
    for (File f : c.getCacheDir().listFiles()) if (f.isFile()) f.delete();
    for (File f : c.getNoBackupFilesDir().listFiles()) if (f.isFile()) f.delete();
    KeyStore k = KeyStore.getInstance("AndroidKeyStore");
    k.load(null);
    k.deleteEntry(ALIAS);
  }

  public static byte[] bounded(InputStream in, int max) throws Exception {
    try (InputStream src = in;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n, total = 0;
      while ((n = src.read(b)) != -1) {
        total += n;
        if (total > max) throw new Exception("File exceeds supported size");
        out.write(b, 0, n);
      }
      return out.toByteArray();
    }
  }

  public static void gap(JSONObject p, String note, long from, long to) throws Exception {
    JSONArray a = p.getJSONArray("gaps");
    a.put(Core.obj("from_ms", from, "to_ms", to, "reason", note));
    while (a.length() > 100) a.remove(0);
  }
}
