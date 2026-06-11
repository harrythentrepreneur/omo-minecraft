package com.agentcraft.classroom;

/**
 * The tiny algebra engine behind the classroom calculator. It parses a single
 * expression or equation in one variable ({@code x}) and returns a
 * human-readable answer:
 *
 * <pre>
 *   "2 + 3 * 4"    -> "14"
 *   "2*x + 3 + x"  -> "3x + 3"     (simplify)
 *   "2x + 3 = 11"  -> "x = 4"      (solve)
 * </pre>
 *
 * Supports {@code + - * / ( )}, decimals, unary minus, and implicit
 * multiplication ({@code 2x}, {@code 3(4+1)}). Anything non-linear (x*x),
 * divide-by-x, or unparseable returns {@code "ERR"}. Everything is computed on
 * a {@link Lin} value {@code a*x + b}, so a plain number is just {@code a=0}.
 */
public final class AlgebraEval {

    private AlgebraEval() {}

    /** a·x + b */
    private record Lin(double a, double b) {}

    public static String evaluate(String input) {
        if (input == null) return "ERR";
        String s = input.trim();
        if (s.isEmpty()) return "0";
        try {
            int eq = s.indexOf('=');
            if (eq >= 0) {
                if (s.indexOf('=', eq + 1) >= 0) return "ERR"; // only one '='
                Lin lhs = parse(s.substring(0, eq));
                Lin rhs = parse(s.substring(eq + 1));
                double a = lhs.a - rhs.a;
                double b = rhs.b - lhs.b;
                if (Math.abs(a) < 1e-9) return Math.abs(b) < 1e-9 ? "all numbers" : "no solution";
                return "x = " + num(b / a);
            }
            Lin lin = parse(s);
            if (Math.abs(lin.a) < 1e-9) return num(lin.b);
            return linToString(lin);
        } catch (RuntimeException ex) {
            return "ERR";
        }
    }

    private static Lin parse(String s) {
        Parser p = new Parser(s);
        Lin v = p.expr();
        if (!p.atEnd()) throw new IllegalStateException("trailing input");
        return v;
    }

    /** Recursive-descent parser producing a {@link Lin}. */
    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return peek() == '\0'; }

        private char peek() {
            while (i < s.length() && s.charAt(i) == ' ') i++;
            return i < s.length() ? s.charAt(i) : '\0';
        }

        Lin expr() {
            Lin v = term();
            while (true) {
                char c = peek();
                if (c == '+') { i++; v = add(v, term()); }
                else if (c == '-') { i++; v = sub(v, term()); }
                else break;
            }
            return v;
        }

        Lin term() {
            Lin v = factor();
            while (true) {
                char c = peek();
                if (c == '*') { i++; v = mul(v, factor()); }
                else if (c == '/') { i++; v = div(v, factor()); }
                else if (c == '(' || c == 'x' || c == '.' || Character.isDigit(c)) {
                    v = mul(v, factor()); // implicit multiply: 2x, 3(4+1), x(2)
                } else break;
            }
            return v;
        }

        Lin factor() {
            char c = peek();
            if (c == '-') { i++; return neg(factor()); }
            if (c == '+') { i++; return factor(); }
            return primary();
        }

        Lin primary() {
            char c = peek();
            if (c == '(') {
                i++;
                Lin v = expr();
                if (peek() != ')') throw new IllegalStateException("expected )");
                i++;
                return v;
            }
            if (c == 'x') { i++; return new Lin(1, 0); }
            if (c == '.' || Character.isDigit(c)) {
                int start = i;
                while (i < s.length() && (s.charAt(i) == '.' || Character.isDigit(s.charAt(i)))) i++;
                return new Lin(0, Double.parseDouble(s.substring(start, i)));
            }
            throw new IllegalStateException("unexpected: " + c);
        }
    }

    private static Lin add(Lin x, Lin y) { return new Lin(x.a + y.a, x.b + y.b); }
    private static Lin sub(Lin x, Lin y) { return new Lin(x.a - y.a, x.b - y.b); }
    private static Lin neg(Lin x) { return new Lin(-x.a, -x.b); }

    private static Lin mul(Lin x, Lin y) {
        if (x.a == 0) return new Lin(y.a * x.b, y.b * x.b);
        if (y.a == 0) return new Lin(x.a * y.b, x.b * y.b);
        throw new IllegalStateException("nonlinear (x*x)");
    }

    private static Lin div(Lin x, Lin y) {
        if (y.a != 0) throw new IllegalStateException("divide by x");
        if (Math.abs(y.b) < 1e-12) throw new IllegalStateException("divide by zero");
        return new Lin(x.a / y.b, x.b / y.b);
    }

    private static String linToString(Lin l) {
        StringBuilder sb = new StringBuilder();
        if (Math.abs(l.a - 1) < 1e-9) sb.append("x");
        else if (Math.abs(l.a + 1) < 1e-9) sb.append("-x");
        else sb.append(num(l.a)).append("x");
        if (Math.abs(l.b) > 1e-9) sb.append(l.b > 0 ? " + " : " - ").append(num(Math.abs(l.b)));
        return sb.toString();
    }

    private static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        String s = String.format("%.6f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
