package org.continuity.recall;

import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

/** Standard JCA AES-256-GCM, PBKDF2-HMAC-SHA256; no custom cipher. */
public final class Crypto {
  public static final int ITERATIONS = 210000;

  public static byte[] random(int n) {
    byte[] b = new byte[n];
    new SecureRandom().nextBytes(b);
    return b;
  }

  public static byte[] encrypt(SecretKey key, byte[] plain) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key);
    c.updateAAD(Core.utf8("TotalRecall sealed v1"));
    byte[] iv = c.getIV(), enc = c.doFinal(plain);
    byte[] out = new byte[iv.length + enc.length];
    System.arraycopy(iv, 0, out, 0, iv.length);
    System.arraycopy(enc, 0, out, iv.length, enc.length);
    return out;
  }

  public static byte[] decrypt(SecretKey key, byte[] enc) throws Exception {
    if (enc.length < 28 || enc.length > Core.MAX_ARCHIVE + 1024)
      throw new Exception("Invalid sealed data size");
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, enc, 0, 12));
    c.updateAAD(Core.utf8("TotalRecall sealed v1"));
    return c.doFinal(enc, 12, enc.length - 12);
  }

  static SecretKey derive(char[] password, byte[] salt, int iterations) throws Exception {
    PBEKeySpec p = new PBEKeySpec(password, salt, iterations, 256);
    try {
      return new SecretKeySpec(
          SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(p).getEncoded(),
          "AES");
    } finally {
      p.clearPassword();
    }
  }

  public static JSONObject seal(byte[] raw, char[] password) throws Exception {
    if (password.length < 12)
      throw new Exception(
          "Use a passphrase of at least 12 characters; keep a separate recovery copy");
    byte[] salt = random(16);
    return Core.obj(
        "format",
        "TotalRecallSealed/1",
        "kdf",
        "PBKDF2-HMAC-SHA256",
        "iterations",
        ITERATIONS,
        "salt",
        Base64.getEncoder().encodeToString(salt),
        "cipher",
        "AES-256-GCM",
        "data",
        Base64.getEncoder().encodeToString(encrypt(derive(password, salt, ITERATIONS), raw)));
  }

  public static byte[] open(JSONObject o, char[] password) throws Exception {
    if (!"TotalRecallSealed/1".equals(o.getString("format"))
        || !"PBKDF2-HMAC-SHA256".equals(o.getString("kdf"))
        || !"AES-256-GCM".equals(o.getString("cipher"))
        || o.getInt("iterations") != ITERATIONS)
      throw new Exception("Unsupported encryption parameters");
    byte[] salt = Base64.getDecoder().decode(o.getString("salt"));
    if (salt.length != 16) throw new Exception("Invalid salt");
    return decrypt(
        derive(password, salt, ITERATIONS), Base64.getDecoder().decode(o.getString("data")));
  }
}
