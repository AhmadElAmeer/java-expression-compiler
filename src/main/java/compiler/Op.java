package compiler;

/**
 * Instruction set of the stack VM. Each instruction is one int; some take one int operand
 * right after it. Booleans are stored as 0 and 1.
 */
public final class Op {
    private Op() {}

    public static final int
            CONST = 0,          // k        push constants[k]
            LOAD = 1,           // slot     push frame[slot]
            STORE = 2,          // slot     frame[slot] = pop
            POP = 3,            //          discard top
            ADD = 4, SUB = 5, MUL = 6, DIV = 7, MOD = 8,
            NEG = 9, NOT = 10,
            EQ = 11, NE = 12, LT = 13, LE = 14, GT = 15, GE = 16,
            JUMP = 17,          // target   pc = target
            JUMP_IF_FALSE = 18, // target   if pop == 0: pc = target
            CALL = 19,          // fn       new frame; the arguments on the stack become its first slots
            RET = 20,           //          return the top value to the caller
            RET_VOID = 21,      //          return without a value
            PRINT_INT = 22,
            PRINT_BOOL = 23,
            HALT = 24;

    static final String[] NAMES = {
            "CONST", "LOAD", "STORE", "POP", "ADD", "SUB", "MUL", "DIV", "MOD", "NEG", "NOT",
            "EQ", "NE", "LT", "LE", "GT", "GE", "JUMP", "JUMP_IF_FALSE", "CALL", "RET", "RET_VOID",
            "PRINT_INT", "PRINT_BOOL", "HALT"};

    /** Number of operands following each opcode. */
    static int operands(int op) {
        return switch (op) {
            case CONST, LOAD, STORE, JUMP, JUMP_IF_FALSE, CALL -> 1;
            default -> 0;
        };
    }

    /** Net change in stack height (CALL is handled by the compiler, which knows the signature). */
    static int stackEffect(int op) {
        return switch (op) {
            case CONST, LOAD -> 1;
            case STORE, POP, ADD, SUB, MUL, DIV, MOD, EQ, NE, LT, LE, GT, GE,
                 JUMP_IF_FALSE, RET, PRINT_INT, PRINT_BOOL -> -1;
            default -> 0;
        };
    }
}
