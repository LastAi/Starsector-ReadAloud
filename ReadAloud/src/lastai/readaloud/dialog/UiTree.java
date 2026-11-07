package lastai.readaloud.dialog;

import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import lastai.readaloud.ReadAloudLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the game's UI tree to read text the API doesn't expose.
 *
 * TextPanelAPI can add paragraphs but not read them back. Its implementation is an
 * ordinary UI panel, though, and every UI panel has a public {@code getChildrenCopy()}
 * method (not in the API, but not obfuscated either). Paragraphs are LabelAPI children
 * of the panel's scroller, option buttons are ButtonAPI children of the option panel.
 *
 * Only that one public method is called, through MethodHandles (java.lang.reflect is
 * blocked for mods). If a game update renames it, the walk finds nothing, the problem
 * is logged once, and the mod simply has no text to read.
 */
public final class UiTree {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodType RETURNS_LIST = MethodType.methodType(List.class);
    /** java.lang.reflect.Modifier.PUBLIC; that class itself is blocked for mods. */
    private static final int PUBLIC = 0x0001;
    private static final int MAX_DEPTH = 10;
    private static final int MAX_NODES = 4000;

    /** Class -> handle, or NONE when the class has no usable getChildrenCopy(). */
    private static final Map<Class<?>, MethodHandle> CHILDREN = new HashMap<Class<?>, MethodHandle>();
    private static final MethodHandle NONE = MethodHandles.constant(Object.class, null);

    private UiTree() {}

    /** All labels under {@code root}, in the order the UI holds them (reading order). Buttons are skipped. */
    public static List<LabelAPI> labels(Object root) {
        List<LabelAPI> out = new ArrayList<LabelAPI>();
        collect(root, 0, out, null, new int[]{0});
        return out;
    }

    /** All buttons under {@code root}. */
    public static List<ButtonAPI> buttons(Object root) {
        List<ButtonAPI> out = new ArrayList<ButtonAPI>();
        collect(root, 0, null, out, new int[]{0});
        return out;
    }

    /** True if {@code root}'s class has the children method this mod needs. */
    public static boolean canWalk(Object root) {
        return root != null && handleFor(root.getClass()) != NONE;
    }

    private static void collect(Object node, int depth, List<LabelAPI> labels, List<ButtonAPI> buttons, int[] count) {
        if (node == null || depth > MAX_DEPTH || count[0]++ > MAX_NODES) return;
        if (node instanceof ButtonAPI) {
            if (buttons != null) buttons.add((ButtonAPI) node);
            return; // a button's own caption is not dialog text
        }
        if (node instanceof LabelAPI) {
            if (labels != null) labels.add((LabelAPI) node);
            return;
        }
        for (Object child : children(node)) collect(child, depth + 1, labels, buttons, count);
    }

    /** The node's children, or an empty list if it has none or they can't be read. */
    public static List<?> children(Object node) {
        MethodHandle h = handleFor(node.getClass());
        if (h == NONE) return Collections.emptyList();
        try {
            Object r = h.invoke(node);
            return r instanceof List ? (List<?>) r : Collections.emptyList();
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("children:" + node.getClass().getName(),
                    "reading UI children of " + node.getClass().getName() + " failed", t);
            return Collections.emptyList();
        }
    }

    private static synchronized MethodHandle handleFor(Class<?> cls) {
        MethodHandle h = CHILDREN.get(cls);
        if (h != null) return h;
        h = NONE;
        // The method must be looked up on a public class: anonymous or private UI classes
        // inherit it from a public base class.
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            if ((c.getModifiers() & PUBLIC) == 0) continue;
            try {
                h = LOOKUP.findVirtual(c, "getChildrenCopy", RETURNS_LIST);
                break;
            } catch (NoSuchMethodException e) {
                break; // a public class without it: its bases won't have it either
            } catch (IllegalAccessException e) {
                // try the next base class
            }
        }
        CHILDREN.put(cls, h);
        return h;
    }
}
