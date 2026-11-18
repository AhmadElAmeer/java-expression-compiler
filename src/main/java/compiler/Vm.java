package compiler;

import java.util.Arrays;

/**
 * Executes bytecode. All values live on one {@code long[]} stack; a function's frame is a window
 * of it starting at {@code bp}: its parameters and locals, then its operand stack.
 */
public final class Vm {
    /** Maximum nesting of active calls (the main program doesn't count). */
    public static final int MAX_DEPTH = 10_000;

    private final Bytecode bc;
    private final StringBuilder out;
    private final long maxSteps;
    private long steps;

    public Vm(Bytecode bc, StringBuilder out, long maxSteps) {
        this.bc = bc;
        this.out = out;
        this.maxSteps = maxSteps;
    }

    /** Instructions executed by the last {@link #run()}, including one that failed. */
    public long steps() {
        return steps;
    }

    public void run() {
        final int[] code = bc.code;
        final long[] k = bc.constants;
        final Bytecode.FnEntry[] fns = bc.functions;
        Bytecode.FnEntry main = fns[bc.mainIndex];

        long[] st = new long[Math.max(256, main.locals() + main.maxStack())];
        int[] retPc = new int[MAX_DEPTH];
        int[] retBp = new int[MAX_DEPTH];
        int fp = 0;                // active calls
        int bp = 0;                // frame base
        int sp = main.locals();    // next free stack slot
        int pc = main.entry();
        int at = pc;               // start of the current instruction, for error lines
        long n = 0;
        try {
            while (true) {
                at = pc;
                if (++n > maxSteps) {
                    throw new RunError("step limit exceeded (" + maxSteps + " instructions)", bc.lines[at]);
                }
                switch (code[pc++]) {
                    case Op.CONST -> st[sp++] = k[code[pc++]];
                    case Op.LOAD -> st[sp++] = st[bp + code[pc++]];
                    case Op.STORE -> st[bp + code[pc++]] = st[--sp];
                    case Op.POP -> sp--;
                    case Op.ADD -> { long b = st[--sp]; st[sp - 1] = Math.addExact(st[sp - 1], b); }
                    case Op.SUB -> { long b = st[--sp]; st[sp - 1] = Math.subtractExact(st[sp - 1], b); }
                    case Op.MUL -> { long b = st[--sp]; st[sp - 1] = Math.multiplyExact(st[sp - 1], b); }
                    case Op.DIV -> { long b = st[--sp]; st[sp - 1] = Arith.div(st[sp - 1], b); }
                    case Op.MOD -> { long b = st[--sp]; st[sp - 1] = Arith.mod(st[sp - 1], b); }
                    case Op.NEG -> st[sp - 1] = Math.negateExact(st[sp - 1]);
                    case Op.NOT -> st[sp - 1] ^= 1;
                    case Op.EQ -> { long b = st[--sp]; st[sp - 1] = st[sp - 1] == b ? 1 : 0; }
                    case Op.NE -> { long b = st[--sp]; st[sp - 1] = st[sp - 1] != b ? 1 : 0; }
                    case Op.LT -> { long b = st[--sp]; st[sp - 1] = st[sp - 1] < b ? 1 : 0; }
                    case Op.LE -> { long b = st[--sp]; st[sp - 1] = st[sp - 1] <= b ? 1 : 0; }
                    case Op.GT -> { long b = st[--sp]; st[sp - 1] = st[sp - 1] > b ? 1 : 0; }
                    case Op.GE -> { long b = st[--sp]; st[sp - 1] = st[sp - 1] >= b ? 1 : 0; }
                    case Op.JUMP -> pc = code[pc];
                    case Op.JUMP_IF_FALSE -> {
                        int target = code[pc++];
                        if (st[--sp] == 0) pc = target;
                    }
                    case Op.CALL -> {
                        Bytecode.FnEntry f = fns[code[pc++]];
                        if (fp == MAX_DEPTH) {
                            throw new RunError("stack overflow (more than " + MAX_DEPTH + " nested calls)", bc.lines[at]);
                        }
                        retPc[fp] = pc;
                        retBp[fp] = bp;
                        fp++;
                        bp = sp - f.params();       // the arguments become the first slots
                        sp = bp + f.locals();
                        int need = sp + f.maxStack();
                        if (need > st.length) st = Arrays.copyOf(st, Math.max(need, st.length * 2));
                        pc = f.entry();
                    }
                    case Op.RET -> {
                        long v = st[--sp];
                        sp = bp;
                        fp--;
                        pc = retPc[fp];
                        bp = retBp[fp];
                        st[sp++] = v;
                    }
                    case Op.RET_VOID -> {
                        sp = bp;
                        fp--;
                        pc = retPc[fp];
                        bp = retBp[fp];
                    }
                    case Op.PRINT_INT -> out.append(st[--sp]).append('\n');
                    case Op.PRINT_BOOL -> out.append(st[--sp] != 0 ? "true" : "false").append('\n');
                    case Op.HALT -> {
                        return;
                    }
                    default -> throw new IllegalStateException("bad opcode " + code[at] + " at " + at);
                }
            }
        } catch (ArithmeticException e) {
            throw new RunError(Arith.message(e), bc.lines[at]);
        } finally {
            steps = n;
        }
    }
}
