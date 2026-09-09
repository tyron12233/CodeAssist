package dev.ide.core.templates

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * Create-Project templates for Java desktop UI programs.
 *
 * Both generate ordinary Swing written against `java.awt`/`javax.swing`, with no reference to how the IDE runs
 * it: on desktop that is the host JDK's own Swing, and on device the owned `:awt-toolkit` the program's
 * references are remapped onto. The code is the same either way, which is the point.
 *
 * They stay inside the widgets and layout managers the owned toolkit implements (`JFrame`, `JPanel`, `JLabel`,
 * `JButton`, `BorderLayout`/`FlowLayout`/`GridLayout`, `paintComponent`, action and mouse listeners), so a
 * generated project behaves the same on both. Anything richer would run on the desktop and fail on the device,
 * which is worse than not offering it.
 */
internal object SwingTemplateSupport {
    /** Every Swing template writes one runnable class into a single `app` module. */
    fun swingApp(scaffold: ProjectScaffold, args: TemplateArgs, className: String, source: String) {
        JavaTemplateSupport.singleModule(scaffold, args.name, "app", "java-lib")
        scaffold.writeText(
            "app/src/main/java/${JavaTemplateSupport.pkgPath(args.packageName)}/$className.java",
            source,
        )
    }
}

/**
 * A window with a label and a button: the smallest Swing program that is still a program, and the one nearly
 * every tutorial opens with.
 */
object SwingAppTemplate : ProjectTemplate {
    override val id = TemplateId("swing-app")
    override val displayName = "Swing Desktop App"
    override val description = "A windowed Java application: a frame with a label and a button that responds to clicks."
    override val category = TemplateCategory.JAVA
    override val iconId = "java"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        SwingTemplateSupport.swingApp(
            scaffold, args, "MainWindow",
            """
            package $pkg;

            import java.awt.BorderLayout;
            import java.awt.Color;
            import java.awt.Font;
            import javax.swing.JButton;
            import javax.swing.JFrame;
            import javax.swing.JLabel;
            import javax.swing.JPanel;
            import javax.swing.WindowConstants;

            /** ${args.name}: a Swing window with a label and a button. */
            public class MainWindow {

                private int clicks = 0;

                private void show() {
                    JFrame frame = new JFrame("${args.name}");
                    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

                    JLabel label = new JLabel("Click the button below");
                    label.setFont(new Font("SansSerif", Font.PLAIN, 18));
                    label.setForeground(new Color(0x22, 0x22, 0x22));

                    JPanel content = new JPanel(new BorderLayout());
                    content.setBackground(Color.WHITE);
                    content.add(label, BorderLayout.CENTER);

                    JButton button = new JButton("Say hello");
                    button.addActionListener(event -> {
                        clicks++;
                        label.setText("Hello! You clicked " + clicks + (clicks == 1 ? " time" : " times"));
                    });
                    content.add(button, BorderLayout.SOUTH);

                    frame.getContentPane().add(content, BorderLayout.CENTER);
                    frame.setSize(360, 220);
                    frame.setVisible(true);
                }

                public static void main(String[] args) {
                    new MainWindow().show();
                }
            }
            """,
        )
    }
}

/**
 * A `JPanel` that draws itself, which is the other half of what people write Swing for: custom `paintComponent`
 * rendering, plus a button that changes what is drawn so the repaint cycle is visible.
 */
object SwingCanvasTemplate : ProjectTemplate {
    override val id = TemplateId("swing-canvas")
    override val displayName = "Swing Custom Painting"
    override val description = "A window that draws its own graphics: a JPanel with paintComponent and a repaint on click."
    override val category = TemplateCategory.JAVA
    override val iconId = "java"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        SwingTemplateSupport.swingApp(
            scaffold, args, "DrawingWindow",
            """
            package $pkg;

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

            /** ${args.name}: a Swing window whose panel draws its own graphics. */
            public class DrawingWindow {

                /** A panel that paints itself. Override paintComponent, never paint, and always call super first. */
                static class Canvas extends JPanel {

                    private int shapes = 3;

                    Canvas() {
                        setPreferredSize(new Dimension(400, 260));
                        setBackground(Color.WHITE);
                    }

                    void addShape() {
                        shapes++;
                        // repaint() asks for a new frame; the next paintComponent draws the new state.
                        repaint();
                    }

                    @Override
                    protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        Graphics2D g2 = (Graphics2D) g;
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                        g2.setColor(new Color(0x2D, 0x6C, 0xDF));
                        g2.fillRoundRect(20, 20, getWidth() - 40, 56, 16, 16);

                        g2.setColor(Color.WHITE);
                        g2.setFont(new Font("SansSerif", Font.BOLD, 18));
                        g2.drawString("${args.name}", 40, 56);

                        g2.setColor(new Color(0xDF, 0x6C, 0x2D));
                        for (int i = 0; i < shapes; i++) {
                            g2.fillOval(30 + i * 44, 110, 32, 32);
                        }

                        g2.setColor(Color.DARK_GRAY);
                        g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
                        g2.drawString(shapes + " shapes", 30, 180);
                    }
                }

                private void show() {
                    JFrame frame = new JFrame("${args.name}");
                    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

                    Canvas canvas = new Canvas();
                    JButton button = new JButton("Add a shape");
                    button.addActionListener(event -> canvas.addShape());

                    frame.getContentPane().add(canvas, BorderLayout.CENTER);
                    frame.getContentPane().add(button, BorderLayout.SOUTH);
                    frame.pack();
                    frame.setVisible(true);
                }

                public static void main(String[] args) {
                    new DrawingWindow().show();
                }
            }
            """,
        )
    }
}
