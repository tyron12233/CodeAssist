package swingfixture;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

/**
 * An ordinary Swing program, compiled against the REAL java.awt/javax.swing exactly as a user's module is,
 * and never told about the owned toolkit. Everything it needs from the interpreter is here: a subclass of a
 * framework class with an overridden protected method, a capturing lambda listener, a string concatenation
 * (invokedynamic through StringConcatFactory), and static field reads off AWT constants.
 */
public class SwingFixture {

    public static class DrawPanel extends JPanel {
        public int clicks = 0;

        public DrawPanel() {
            setPreferredSize(new Dimension(200, 100));
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0x2D, 0x6C, 0xDF));
            g2.fillRect(10, 10, 50, 20);
            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g2.drawString("clicks: " + clicks, 10, 60);
        }
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("fixture");
        DrawPanel panel = new DrawPanel();
        JButton button = new JButton("Go");
        button.addActionListener(e -> {
            panel.clicks++;
            panel.repaint();
        });
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(panel, BorderLayout.CENTER);
        frame.getContentPane().add(button, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(240, 160);
        frame.setVisible(true);
    }
}
