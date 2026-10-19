package org.json;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** Only the flat string-to-long object used for daily statistics, not a full JSON parser. */
public class JSONObject {
    private final Map<String, Long> values = new LinkedHashMap<String, Long>();
    public JSONObject(Map<?, ?> source) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            values.put(String.valueOf(entry.getKey()), ((Number) entry.getValue()).longValue());
        }
    }
    public JSONObject(String source) {
        String text = source.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) throw new IllegalArgumentException("object");
        String body = text.substring(1, text.length() - 1).trim();
        if (body.length() == 0) return;
        Pattern member = Pattern.compile("\\s*\"([^\"\\\\]*)\"\\s*:\\s*(-?[0-9]+)\\s*");
        for (String part : body.split(",", -1)) {
            Matcher match = member.matcher(part);
            if (!match.matches()) throw new IllegalArgumentException("flat numeric object only");
            values.put(match.group(1), Long.parseLong(match.group(2)));
        }
    }
    public Iterator<String> keys() { return values.keySet().iterator(); }
    public long optLong(String key, long fallback) { Long value = values.get(key); return value == null ? fallback : value; }
    @Override public String toString() {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (out.length() > 1) out.append(',');
            out.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return out.append('}').toString();
    }
}
