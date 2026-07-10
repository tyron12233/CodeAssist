package calculator;

/**
 * A small recursive-descent calculator: it parses and evaluates arithmetic expressions made of numbers,
 * the operators {@code + - * /}, parentheses, and a leading minus (negation), over floating-point values.
 *
 * <p>The grammar it implements (each rule calls the next, which gives {@code *} and {@code /} a higher
 * precedence than {@code +} and {@code -}):
 * <pre>
 *   expr   = term (('+' | '-') term)*
 *   term   = factor (('*' | '/') factor)*
 *   factor = number | '(' expr ')' | '-' factor
 * </pre>
 *
 * <p>Usage: {@code Calculator.evaluate("2 + 3 * 4")} returns {@code 14.0}.
 */
public final class Calculator {

    private final String input;
    private int pos;

    private Calculator(String input) {
        this.input = input;
        this.pos = 0;
    }

    /**
     * Parse and evaluate {@code expression}, returning its numeric value.
     *
     * @throws IllegalArgumentException if the expression is malformed
     */
    public static double evaluate(String expression) {
        Calculator parser = new Calculator(expression);
        double value = parser.expr();
        parser.skipSpaces();
        if (parser.pos < parser.input.length()) {
            throw new IllegalArgumentException("Unexpected character at position " + parser.pos);
        }
        return value;
    }

    // expr = term (('+' | '-') term)*
    private double expr() {
        double value = term();
        while (true) {
            skipSpaces();
            char op = peek();
            if (op == '+') { pos++; value += term(); }
            else if (op == '-') { pos++; value -= term(); }
            else break;
        }
        return value;
    }

    // term = factor (('*' | '/') factor)*
    private double term() {
        double value = factor();
        while (true) {
            skipSpaces();
            char op = peek();
            if (op == '*') { pos++; value *= factor(); }
            else if (op == '/') { pos++; value /= factor(); }
            else break;
        }
        return value;
    }

    // factor = number | '(' expr ')' | '-' factor
    private double factor() {
        skipSpaces();
        char c = peek();
        if (c == '(') {
            pos++;
            double value = expr();
            skipSpaces();
            if (peek() != ')') throw new IllegalArgumentException("Expected ')' at position " + pos);
            pos++;
            return value;
        }
        if (c == '-') {
            pos++;
            return -factor();
        }
        return number();
    }

    private double number() {
        skipSpaces();
        int start = pos;
        while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos++;
        }
        if (pos == start) throw new IllegalArgumentException("Expected a number at position " + pos);
        return Double.parseDouble(input.substring(start, pos));
    }

    private void skipSpaces() {
        while (pos < input.length() && input.charAt(pos) == ' ') pos++;
    }

    private char peek() {
        return pos < input.length() ? input.charAt(pos) : '\0';
    }
}
