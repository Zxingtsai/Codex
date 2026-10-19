import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.BufferedInputStream;
import java.util.*;
import java.util.zip.*;

/** Verify the hooks in the FINAL APK, rather than just checking modified source text. */
public final class VerifyDex {
    public static void main(String[] args) throws Exception {
        Map<String, List<String>> hooks = new LinkedHashMap<String, List<String>>();
        String service = "Lcom/sony/songpal/localplayer/playbackservice/PlaybackService;";
        String gapless = "Lcom/sony/songpal/localplayer/playbackservice/PlaybackService$25;";
        String settings = "Lcom/sony/walkman/highresmediaplayer/presenter/setting/SettingFragment;";
        hooks.put(service+"setPlayerStatus", Arrays.asList("onState"));
        hooks.put(service+"onDestroy", Arrays.asList("onServiceStop"));
        hooks.put(service+"executeOnCompletion", Arrays.asList("onTrackCompleted"));
        hooks.put(gapless+"onFetchNext", Arrays.asList("timerActive"));
        hooks.put(gapless+"executeOnMoveToNext", Arrays.asList("onTrackCompleted"));
        hooks.put(settings+"onCreateView", Arrays.asList("appendSettings"));
        Set<String> classes = new HashSet<String>();
        int dexCount = 0, hookCount = 0;
        try (ZipFile apk = new ZipFile(args[0])) {
            Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                dexCount++;
                DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.forApi(34), new BufferedInputStream(apk.getInputStream(entry)));
                for (ClassDef cls : dex.getClasses()) {
                    if (!classes.add(cls.getType())) throw new AssertionError("Duplicate class " + cls.getType());
                    for (Method method : cls.getMethods()) {
                        String key = cls.getType()+method.getName();
                        if (!hooks.containsKey(key)) continue;
                        List<String> calls = new ArrayList<String>();
                        for (Instruction instruction : method.getImplementation().getInstructions()) {
                            if (instruction instanceof ReferenceInstruction) {
                                Object ref = ((ReferenceInstruction)instruction).getReference();
                                if (ref instanceof MethodReference) {
                                    MethodReference call = (MethodReference)ref;
                                    if (call.getDefiningClass().equals("Lcom/rea/walkman/PlaybackExtras;")) calls.add(call.getName());
                                }
                            }
                        }
                        if (!calls.equals(hooks.get(key))) throw new AssertionError(key + " " + calls);
                        hooks.remove(key);
                        hookCount++;
                    }
                }
            }
        }
        if (dexCount != 7 || !hooks.isEmpty()) throw new AssertionError("Missing DEX/hook " + hooks.keySet());
        for (String cls : classes) {
            if (cls.startsWith("Lcom/rea/") && !cls.startsWith("Lcom/rea/walkman/")) throw new AssertionError(cls);
            if (cls.contains("ListeningClockTest") || cls.contains("PlaybackExtrasTest")) throw new AssertionError("Test shipped");
        }
        System.out.println("Final APK: " + dexCount + " DEX parsed; no duplicate classes; all " + hookCount + " service/UI hooks verified.");
    }
}
