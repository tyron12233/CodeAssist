package swingfixture;

import javax.swing.JFrame;
import javax.swing.JTable;

/** Uses a widget the toolkit does not implement yet, so the missing-widget report has something to find. */
public class UnsupportedWidgetFixture {
    public static void main(String[] args) {
        JFrame frame = new JFrame("unsupported");
        frame.getContentPane().add(new JTable());
        frame.setVisible(true);
    }
}
