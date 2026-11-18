package compiler;

/** A runtime error: division by zero, integer overflow, stack overflow or the step limit. */
public final class RunError extends RuntimeException {
    public final int line;
    public final String detail;

    public RunError(String detail, int line) {
        super("line " + line + ": " + detail);
        this.detail = detail;
        this.line = line;
    }
}
