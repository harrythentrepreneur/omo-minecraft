package com.agentcraft.classroom;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A clickable in-game calculator rendered as a 54-slot chest GUI.
 *
 * <p>Two tricks make it readable despite vanilla GUIs not rendering item text:
 *   - the running expression / answer lives in the inventory <b>title</b>
 *     (the one always-visible string), updated live by {@link
 *     com.agentcraft.listeners.CalculatorListener};
 *   - number keys carry their digit as a <b>stack-count badge</b> (2-9 show
 *     the number on the key; 0 and 1 are read from the standard layout).
 *
 * ENTER feeds the expression to {@link AlgebraEval}, so the keypad does
 * arithmetic, simplifies in {@code x}, and solves equations for {@code x}.
 */
public final class CalculatorGui implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int DISPLAY = 4;

    /** slot -> token. Control tokens: ENTER, C (clear), BS (backspace). */
    private static final Map<Integer, String> BUTTONS = new HashMap<>();
    static {
        BUTTONS.put(10, "7"); BUTTONS.put(11, "8"); BUTTONS.put(12, "9"); BUTTONS.put(13, "/"); BUTTONS.put(14, "x"); BUTTONS.put(15, "C");
        BUTTONS.put(19, "4"); BUTTONS.put(20, "5"); BUTTONS.put(21, "6"); BUTTONS.put(22, "*"); BUTTONS.put(23, "("); BUTTONS.put(24, "BS");
        BUTTONS.put(28, "1"); BUTTONS.put(29, "2"); BUTTONS.put(30, "3"); BUTTONS.put(31, "-"); BUTTONS.put(32, ")"); BUTTONS.put(33, "ENTER");
        BUTTONS.put(37, "0"); BUTTONS.put(38, "."); BUTTONS.put(39, "="); BUTTONS.put(40, "+");                          BUTTONS.put(42, "ENTER");
    }

    private final Inventory inv;
    private final StringBuilder expr = new StringBuilder();
    private String lastResult = "0";
    private boolean justEvaluated = false;
    private boolean resultWasNumber = false;

    public CalculatorGui() {
        inv = Bukkit.createInventory(this, SIZE, Component.text("Calculator  ›  0", NamedTextColor.DARK_GRAY));
        ItemStack border = named(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), 1);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, border);
        for (Map.Entry<Integer, String> e : BUTTONS.entrySet()) inv.setItem(e.getKey(), buttonItem(e.getValue()));
        renderInput();
    }

    @Override
    public Inventory getInventory() { return inv; }

    /** The always-visible readout for the inventory title (set by the listener). */
    public String displayText() {
        if (justEvaluated) return "  =  " + lastResult;
        return "  ›  " + (expr.length() == 0 ? "0" : expr.toString());
    }

    /** Handle a click on a top-inventory slot. Returns true if it was a button. */
    public boolean handleClick(int slot) {
        String tok = BUTTONS.get(slot);
        if (tok == null) return false;
        switch (tok) {
            case "ENTER" -> doEvaluate();
            case "C" -> { expr.setLength(0); justEvaluated = false; renderInput(); }
            case "BS" -> {
                justEvaluated = false;
                if (expr.length() > 0) expr.deleteCharAt(expr.length() - 1);
                renderInput();
            }
            default -> {
                boolean fresh = "0123456789.x(".contains(tok);
                if (justEvaluated) {
                    // After ENTER: a fresh operand starts over; an operator
                    // continues from a numeric answer (like a real calculator).
                    if (fresh && !resultWasNumber) expr.setLength(0);
                    justEvaluated = false;
                }
                expr.append(tok);
                renderInput();
            }
        }
        return true;
    }

    private void doEvaluate() {
        lastResult = AlgebraEval.evaluate(expr.toString());
        resultWasNumber = lastResult.matches("-?\\d+(\\.\\d+)?");
        setDisplay(Component.text(lastResult, NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text(expr.toString(), NamedTextColor.DARK_GRAY));
        expr.setLength(0);
        if (resultWasNumber) expr.append(lastResult); // keep operating on the answer
        justEvaluated = true;
    }

    private void renderInput() {
        String text = expr.length() == 0 ? "0" : expr.toString();
        setDisplay(Component.text(text, NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text("read the title above ↑ — press ENTER to solve", NamedTextColor.DARK_GRAY));
    }

    private void setDisplay(Component line, Component sub) {
        ItemStack it = named(Material.PAPER, line, 1);
        ItemMeta m = it.getItemMeta();
        m.lore(List.of(sub.decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(m);
        inv.setItem(DISPLAY, it);
    }

    private static ItemStack buttonItem(String tok) {
        if (tok.length() == 1 && Character.isDigit(tok.charAt(0))) {
            int d = tok.charAt(0) - '0';
            // Stack count = digit, so 2-9 show their number as the slot badge.
            return named(Material.LIGHT_BLUE_CONCRETE, Component.text(tok, NamedTextColor.WHITE, TextDecoration.BOLD), Math.max(1, d));
        }
        return switch (tok) {
            case "ENTER" -> named(Material.LIME_CONCRETE, Component.text("ENTER  =", NamedTextColor.GREEN, TextDecoration.BOLD), 1);
            case "C" -> named(Material.RED_CONCRETE, Component.text("C   clear", NamedTextColor.RED, TextDecoration.BOLD), 1);
            case "BS" -> named(Material.GRAY_CONCRETE, Component.text("⌫   back", NamedTextColor.WHITE, TextDecoration.BOLD), 1);
            case "+", "-", "*", "/" -> named(Material.ORANGE_CONCRETE, Component.text(symbol(tok), NamedTextColor.GOLD, TextDecoration.BOLD), 1);
            case "=" -> named(Material.YELLOW_CONCRETE, Component.text("=  (for equations)", NamedTextColor.YELLOW, TextDecoration.BOLD), 1);
            case "(", ")" -> named(Material.LIGHT_GRAY_CONCRETE, Component.text(tok, NamedTextColor.WHITE, TextDecoration.BOLD), 1);
            case "x" -> named(Material.PURPLE_CONCRETE, Component.text("x  (the unknown)", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD), 1);
            default -> named(Material.WHITE_CONCRETE, Component.text(tok, NamedTextColor.WHITE, TextDecoration.BOLD), 1);
        };
    }

    private static String symbol(String tok) {
        return switch (tok) {
            case "*" -> "×";
            case "/" -> "÷";
            case "-" -> "−";
            default -> tok;
        };
    }

    private static ItemStack named(Material m, Component name, int amount) {
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }
}
