package dev.aegis.gui;

import javax.swing.*;
import java.awt.*;

public final class Theme {
    public static final Color BG = new Color(18, 20, 24);
    public static final Color PANEL = new Color(25, 28, 34);
    public static final Color PANEL2 = new Color(31, 35, 42);
    public static final Color TEXT = new Color(224, 228, 235);
    public static final Color MUTED = new Color(150, 158, 172);
    public static final Color BORDER = new Color(48, 54, 65);
    public static final Color SELECTION = new Color(47, 78, 120);
    public static final Color ACCENT = new Color(89, 157, 255);

    private Theme() {}

    public static void install() {
        UIManager.put("Panel.background", PANEL);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Tree.background", PANEL);
        UIManager.put("Tree.foreground", TEXT);
        UIManager.put("TabbedPane.background", PANEL);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TextArea.background", BG);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("TextArea.caretForeground", TEXT);
        UIManager.put("Button.background", PANEL2);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("CheckBox.background", PANEL);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("MenuBar.background", PANEL);
        UIManager.put("Menu.foreground", TEXT);
        UIManager.put("MenuItem.foreground", TEXT);
    }

    public static JTextArea codeArea() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        a.setBackground(BG); a.setForeground(TEXT); a.setCaretColor(TEXT);
        a.setTabSize(4); a.setMargin(new Insets(10, 12, 10, 12));
        return a;
    }
}
