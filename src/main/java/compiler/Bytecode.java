package compiler;

import java.util.Arrays;
import java.util.Comparator;

/** A compiled program: one code array for all functions, a constant pool and a line table. */
public final class Bytecode {

    /** {@code locals} includes the parameters; {@code maxStack} is the deepest operand stack. */
    public record FnEntry(String name, int entry, int params, int locals, int maxStack, boolean returnsValue) {}

    public final int[] code;
    /** Source line of every code word, used for runtime error messages. */
    public final int[] lines;
    public final long[] constants;
    public final FnEntry[] functions;
    public final int mainIndex;

    Bytecode(int[] code, int[] lines, long[] constants, FnEntry[] functions, int mainIndex) {
        this.code = code;
        this.lines = lines;
        this.constants = constants;
        this.functions = functions;
        this.mainIndex = mainIndex;
    }

    /** Number of instructions (not code words). */
    public int instructionCount() {
        int n = 0;
        for (int pc = 0; pc < code.length; pc += 1 + Op.operands(code[pc])) n++;
        return n;
    }

    public String disassemble() {
        FnEntry[] byEntry = functions.clone();
        Arrays.sort(byEntry, Comparator.comparingInt(FnEntry::entry));
        StringBuilder sb = new StringBuilder();
        for (int f = 0; f < byEntry.length; f++) {
            FnEntry fn = byEntry[f];
            int end = f + 1 < byEntry.length ? byEntry[f + 1].entry() : code.length;
            if (f > 0) sb.append('\n');
            sb.append("fn ").append(fn.name()).append("  params=").append(fn.params())
              .append(" locals=").append(fn.locals()).append(" max_stack=").append(fn.maxStack()).append('\n');
            int lastLine = -1;
            for (int pc = fn.entry(); pc < end; pc += 1 + Op.operands(code[pc])) {
                int op = code[pc];
                String operand = "";
                if (Op.operands(op) == 1) {
                    int arg = code[pc + 1];
                    operand = switch (op) {
                        case Op.CONST -> String.valueOf(constants[arg]);
                        case Op.CALL -> functions[arg].name();
                        case Op.JUMP, Op.JUMP_IF_FALSE -> "-> " + arg;
                        default -> String.valueOf(arg);
                    };
                }
                String lineNote = lines[pc] != lastLine ? "; line " + lines[pc] : "";
                lastLine = lines[pc];
                sb.append(String.format("  %4d  %-14s %-10s %s%n", pc, Op.NAMES[op], operand, lineNote).stripTrailing())
                  .append('\n');
            }
        }
        return sb.toString();
    }
}
