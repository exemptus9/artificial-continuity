package org.continuity.recall;

import org.json.*;

/** Strict RFC8259 subset: integral numbers only, no duplicate keys, bounded nesting. */
public final class StrictJson {
  final String s;
  int i = 0;

  StrictJson(String v) {
    s = v;
  }

  public static JSONObject parse(String v) throws Exception {
    if (Core.utf8(v).length > 48 * 1024 * 1024) throw new Exception("JSON exceeds limit");
    StrictJson p = new StrictJson(v);
    Object o = p.value(0);
    p.ws();
    if (p.i != v.length() || !(o instanceof JSONObject))
      throw new Exception("Expected one JSON object");
    return (JSONObject) o;
  }

  void ws() {
    while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
  }

  char next() throws Exception {
    if (i >= s.length()) throw new Exception("Truncated JSON");
    return s.charAt(i++);
  }

  Object value(int depth) throws Exception {
    if (depth > 40) throw new Exception("JSON nesting limit");
    ws();
    char c = next();
    if (c == '"') return string();
    if (c == '{') {
      JSONObject o = new JSONObject();
      ws();
      if (i < s.length() && s.charAt(i) == '}') {
        i++;
        return o;
      }
      while (true) {
        ws();
        if (next() != '"') throw new Exception("JSON key must be quoted");
        String key = string();
        if (o.has(key)) throw new Exception("Duplicate JSON key");
        ws();
        if (next() != ':') throw new Exception("Missing colon");
        o.put(key, value(depth + 1));
        ws();
        c = next();
        if (c == '}') return o;
        if (c != ',') throw new Exception("Expected comma");
      }
    }
    if (c == '[') {
      JSONArray a = new JSONArray();
      ws();
      if (i < s.length() && s.charAt(i) == ']') {
        i++;
        return a;
      }
      while (true) {
        a.put(value(depth + 1));
        if (a.length() > 30000) throw new Exception("Array limit");
        ws();
        c = next();
        if (c == ']') return a;
        if (c != ',') throw new Exception("Expected comma");
      }
    }
    int start = i - 1;
    while (i < s.length() && ",]} \r\n\t".indexOf(s.charAt(i)) < 0) i++;
    String t = s.substring(start, i);
    if (t.equals("true")) return true;
    if (t.equals("false")) return false;
    if (t.equals("null")) return JSONObject.NULL;
    if (!t.matches("-?(0|[1-9][0-9]*)")) throw new Exception("Unsupported JSON token");
    return Long.parseLong(t);
  }

  String string() throws Exception {
    StringBuilder b = new StringBuilder();
    while (true) {
      char c = next();
      if (c == '"') return b.toString();
      if (c < 32) throw new Exception("Unescaped control character");
      if (c != '\\') {
        b.append(c);
        continue;
      }
      c = next();
      switch (c) {
        case '"':
        case '\\':
        case '/':
          b.append(c);
          break;
        case 'b':
          b.append('\b');
          break;
        case 'f':
          b.append('\f');
          break;
        case 'n':
          b.append('\n');
          break;
        case 'r':
          b.append('\r');
          break;
        case 't':
          b.append('\t');
          break;
        case 'u':
          if (i + 4 > s.length()) throw new Exception("Bad Unicode escape");
          b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
          i += 4;
          break;
        default:
          throw new Exception("Bad escape");
      }
    }
  }
}
