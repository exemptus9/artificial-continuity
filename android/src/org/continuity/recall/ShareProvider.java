package org.continuity.recall;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import java.io.*;

public class ShareProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  File resolve(Uri u) throws FileNotFoundException {
    String n = u.getLastPathSegment();
    if (n == null || !n.matches("(context\\.(json|txt)|diagnostic\\.json|audio-[a-f0-9-]+\\.m4a)"))
      throw new FileNotFoundException();
    File f = new File(getContext().getCacheDir(), n);
    if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > 600000)
      throw new FileNotFoundException("Share expired; preview again");
    return f;
  }

  public String getType(Uri u) {
    return u.getPath().endsWith(".m4a")
        ? "audio/mp4"
        : u.getPath().endsWith(".txt") ? "text/plain" : "application/json";
  }

  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(resolve(u), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public Cursor query(Uri u, String[] p, String s, String[] a, String sort) {
    try {
      File f = resolve(u);
      MatrixCursor c = new MatrixCursor(new String[] {"_display_name", "_size"});
      c.addRow(new Object[] {f.getName(), f.length()});
      return c;
    } catch (Exception e) {
      return null;
    }
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
