package dev.aegis;

import dev.aegis.gui.AegisFrame;
import javax.swing.SwingUtilities;

public final class Aegis {
    public static final String NAME = "Aegis";
    public static final String VERSION = "0.4.0-retro-intelligence";

    private Aegis() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                AegisFrame frame = new AegisFrame();
                frame.setVisible(true);
                if (args.length > 0) frame.openFromCommandLine(args[0]);
            } catch (Throwable t) {
                t.printStackTrace();
                javax.swing.JOptionPane.showMessageDialog(null,
                        "Aegis failed to start:\n" + t,
                        "Aegis", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
