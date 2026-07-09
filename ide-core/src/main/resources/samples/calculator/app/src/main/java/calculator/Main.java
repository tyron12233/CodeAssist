package calculator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * A read-eval-print loop (REPL) for the {@link Calculator}: type an arithmetic expression and press Enter to
 * see its value; type {@code quit} (or send end-of-input) to stop.
 *
 * <p>It reads one line at a time from {@code System.in}. Each result is printed, and a bad expression prints
 * an error instead of crashing the loop.
 */
public class Main {

    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Calculator — type an expression (e.g. 2 + 3 * 4), or 'quit' to exit.");

        while (true) {
            System.out.print("> ");
            System.out.flush(); // show the prompt before we block on input
            String line = reader.readLine();
            if (line == null) break;            // end of input (Ctrl-D)
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.equals("quit") || line.equals("exit")) break;
            try {
                System.out.println(line + " = " + Calculator.evaluate(line));
            } catch (RuntimeException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }

        System.out.println("Bye!");
    }
}
