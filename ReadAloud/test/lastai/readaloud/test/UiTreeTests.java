package lastai.readaloud.test;

import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import lastai.readaloud.dialog.UiTree;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Mimics the game's UI classes: public panels with getChildrenCopy(), private subclasses, labels, buttons. */
public final class UiTreeTests {
    public static class Panel {
        final List<Object> kids = new ArrayList<Object>();

        public Panel(Object... k) {
            kids.addAll(Arrays.asList(k));
        }

        public List<Object> getChildrenCopy() {
            return new ArrayList<Object>(kids);
        }
    }

    /** Like the game's anonymous inner panels: not public, inherits the method. */
    private static final class Hidden extends Panel {
        Hidden(Object... k) {
            super(k);
        }
    }

    public static class Leaf {}

    public static class Throws extends Panel {
        @Override
        public List<Object> getChildrenCopy() {
            throw new IllegalStateException("boom");
        }
    }

    static LabelAPI label(final String text) {
        return (LabelAPI) Proxy.newProxyInstance(UiTreeTests.class.getClassLoader(), new Class<?>[]{LabelAPI.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method m, Object[] args) {
                if (m.getName().equals("getText")) return text;
                if (m.getName().equals("toString")) return "label:" + text;
                return null;
            }
        });
    }

    static ButtonAPI button(final String text) {
        return (ButtonAPI) Proxy.newProxyInstance(UiTreeTests.class.getClassLoader(), new Class<?>[]{ButtonAPI.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method m, Object[] args) {
                if (m.getName().equals("getText")) return text;
                return null;
            }
        });
    }

    static List<String> texts(List<LabelAPI> ls) {
        List<String> out = new ArrayList<String>();
        for (LabelAPI l : ls) out.add(l.getText());
        return out;
    }

    public static void labelsInTreeOrderThroughHiddenClasses() {
        Panel textPanel = new Panel(new Hidden(new Panel(label("one"), label("two"), new Hidden(label("three"))), new Leaf()),
                label("four"));
        T.eq(Arrays.asList("one", "two", "three", "four"), texts(UiTree.labels(textPanel)), "labels");
        T.yes(UiTree.canWalk(textPanel), "walkable");
        T.no(UiTree.canWalk(new Leaf()), "leaf isn't");
    }

    public static void buttonsAreCollectedSeparately() {
        Panel options = new Panel(new Panel(button("Trade"), label("not an option")), button("Leave"));
        List<String> b = new ArrayList<String>();
        for (ButtonAPI x : UiTree.buttons(options)) b.add(x.getText());
        T.eq(Arrays.asList("Trade", "Leave"), b, "buttons");
        T.eq(Arrays.asList("not an option"), texts(UiTree.labels(options)), "labels skip buttons");
    }

    public static void aFailingNodeIsSkipped() {
        Panel root = new Panel(label("a"), new Throws(), label("b"));
        T.eq(Arrays.asList("a", "b"), texts(UiTree.labels(root)), "rest still read");
    }

    public static void deepOrCyclicTreesStop() {
        Panel a = new Panel();
        Panel b = new Panel(a, label("x"));
        a.kids.add(b); // cycle
        List<LabelAPI> ls = UiTree.labels(a);
        T.yes(ls.size() > 0 && ls.size() < 50, "bounded: " + ls.size());
    }
}
