package android.content;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
/** Service-only Context fake. UI and actual filesystem behavior are deliberately absent. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    public final Map<String, Object> values = new LinkedHashMap<String, Object>();
    public int commits;
    public int applies;
    private final SharedPreferences preferences;
    public Context() {
        preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if (name.equals("edit")) return editor();
                    if (name.equals("getAll")) return new LinkedHashMap<String, Object>(values);
                    if (name.equals("contains")) return values.containsKey(args[0]);
                    if (name.startsWith("get") && args != null && args.length == 2)
                        return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
                    if (name.equals("toString")) return "MemorySharedPreferences";
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return proxy == args[0];
                    return null;
                }
            });
    }
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    private SharedPreferences.Editor editor() {
        return (SharedPreferences.Editor) Proxy.newProxyInstance(
            SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
            new InvocationHandler() {
                final Map<String, Object> pending = new LinkedHashMap<String, Object>();
                boolean clear;
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if (name.startsWith("put")) { pending.put((String) args[0], args[1]); return proxy; }
                    if (name.equals("remove")) { pending.put((String) args[0], this); return proxy; }
                    if (name.equals("clear")) { clear = true; return proxy; }
                    if (name.equals("commit") || name.equals("apply")) {
                        if (clear) values.clear();
                        for (Map.Entry<String, Object> entry : pending.entrySet()) {
                            if (entry.getValue() == this || entry.getValue() == null) values.remove(entry.getKey());
                            else values.put(entry.getKey(), entry.getValue());
                        }
                        if (name.equals("commit")) { commits++; return true; }
                        applies++; return null;
                    }
                    if (name.equals("toString")) return "MemoryEditor";
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return proxy == args[0];
                    return null;
                }
            });
    }
}
