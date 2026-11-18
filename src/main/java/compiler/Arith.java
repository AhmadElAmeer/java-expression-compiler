package compiler;

import compiler.Token.Kind;

/** Integer semantics shared by the VM, the interpreter and the constant folder. */
final class Arith {
    private Arith() {}

    static final String DIV_ZERO = "division by zero";
    static final String OVERFLOW = "integer overflow";

    static long apply(Kind op, long a, long b) {
        return switch (op) {
            case PLUS -> Math.addExact(a, b);
            case MINUS -> Math.subtractExact(a, b);
            case STAR -> Math.multiplyExact(a, b);
            case SLASH -> div(a, b);
            case PERCENT -> mod(a, b);
            case LT -> a < b ? 1 : 0;
            case LE -> a <= b ? 1 : 0;
            case GT -> a > b ? 1 : 0;
            case GE -> a >= b ? 1 : 0;
            case EQ -> a == b ? 1 : 0;
            case NE -> a != b ? 1 : 0;
            default -> throw new IllegalArgumentException("not an arithmetic operator: " + op);
        };
    }

    /** Truncating division, like Java and C. Long.MIN_VALUE / -1 doesn't fit, so it's an overflow. */
    static long div(long a, long b) {
        if (b == 0) throw new ArithmeticException(DIV_ZERO);
        if (a == Long.MIN_VALUE && b == -1) throw new ArithmeticException(OVERFLOW);
        return a / b;
    }

    /** The result has the sign of the dividend: -7 % 2 == -1. */
    static long mod(long a, long b) {
        if (b == 0) throw new ArithmeticException(DIV_ZERO);
        return a % b;
    }

    /** Math.*Exact throws "long overflow"; report every overflow the same way. */
    static String message(ArithmeticException e) {
        return DIV_ZERO.equals(e.getMessage()) ? DIV_ZERO : OVERFLOW;
    }
}
