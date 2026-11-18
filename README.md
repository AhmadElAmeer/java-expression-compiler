# java-expression-compiler

[![CI](https://github.com/AhmadElAmeer/java-expression-compiler/actions/workflows/ci.yml/badge.svg)](https://github.com/AhmadElAmeer/java-expression-compiler/actions/workflows/ci.yml)

A compiler for a small typed language, written from scratch in Java with no libraries. It has a hand-written lexer, a recursive-descent parser, a type checker, a bytecode generator with constant folding, and a stack-based virtual machine to run the result. A second, independent tree-walking interpreter serves as the reference: thousands of randomly generated programs must print the same output, and fail with the same error on the same line, on the interpreter, the VM and the optimized VM.

```
$ java -cp build/classes/java/main compiler.Main run examples/typo.mini
error at 6:19: argument 2 of 'area' must be int, not bool
  let bad = area(3, true);
                    ^
```

## Problem

"Write a compiler" sounds like one task, but it's a chain of them, and each hides its own mistakes:
- the parser has to get precedence and associativity right (`1 - 2 - 3`, `-a * b`, `a || b && c`);
- the type checker has to reject every ill-typed program with an error a person can act on;
- the code generator has to get jumps, short-circuit evaluation and stack frames exactly right;
- the optimizer must not change what a program does, including *which error* it stops with.

A compiler can pass hand-written tests and still be wrong on inputs nobody thought of. This project makes the whole chain checkable: one language, two independent ways to execute it, and a generator of random programs that compares them.

## Overview

The language has 64-bit integers, booleans, variables, `if`/`else`, `while`, `print` and functions with typed parameters, including recursion:

```
fn fib(n: int): int {
    if (n < 2) {
        return n;
    }
    return fib(n - 1) + fib(n - 2);
}

print fib(27);
```

```
source ──► Lexer ──► Parser ──► Checker ──┬──► Compiler (-O: fold constants) ──► Bytecode ──► Vm
           tokens    syntax tree  types,   │
                                  slots    └──► Interpreter (walks the tree; the reference)
```

| file | what it does |
|---|---|
| `Lexer` | Source text to tokens, each with line and column. `//` comments. |
| `Parser` | Tokens to an immutable syntax tree (Java records and sealed interfaces). One method per grammar rule. |
| `Checker` | Name resolution, type checking, "every path returns", and numbering each variable's slot in its function's frame. |
| `Compiler` | Tree to bytecode, with jump backpatching and exact operand-stack depth per function. `-O` adds constant folding and removes dead branches. |
| `Vm` | A `switch`-based interpreter loop over an `int[]` of code and a single `long[]` stack. |
| `Interpreter` | Executes the checked tree directly. Slower, much simpler, and written separately so it can serve as a reference. |
| `ProgramGenerator`, `Fuzz` | Random well-typed programs, and the three-way comparison. |

## Key features

- **Error messages that point at the problem**, with line, column and a caret, for lexing, parsing and type errors. Some examples from the test suite:
  - `'f' takes 2 arguments, but 1 was given`
  - `comparisons can't be chained; write 'a < b && b < c'`
  - `function 'f' can reach its end without returning an int`
  - `undefined variable 'f' ('f' is a function; call it with ())`
- **Static types** (`int`, `bool`), with inference for `let` and checked signatures for functions. Functions can be called before they are declared, and can be mutually recursive.
- **Defined runtime behaviour.** Integer overflow is an error, not a silent wrap, and so is division by zero. Both report the line of the operator, even inside a multi-line expression. `Long.MIN_VALUE / -1` counts as an overflow too. More than 10,000 nested calls is a stack overflow, and the VM has an optional instruction limit for runaway loops.
- **Constant folding and dead-code removal** that never hide an error. `60 * 60 * 24` becomes `86400`, and `if (1 > 2) {…}` disappears, but `1 / 0` is left alone so it still fails at runtime, on its line.
- **A disassembler** (`dis`), to see exactly what the compiler produced.
- **Differential fuzzing.** Random programs with functions, loops, short-circuit logic, overflow-sized literals and possible division by zero run on all three executors and must agree.

## Tech stack

- Java 21: records, sealed interfaces, and pattern-matching `switch` for the syntax tree; no libraries
- Gradle 8.14 (wrapper included), JUnit 4.13
- GitHub Actions: build, tests, a 3,000-program fuzz run, and the examples on every push

## Architecture

**Grammar** (lowest precedence first; each rule is one parser method):

```
program    → ( function | statement )*
function   → "fn" IDENT "(" params? ")" ( ":" type )? block
statement  → "let" IDENT ( ":" type )? "=" expression ";"
           | IDENT "=" expression ";"
           | "print" expression ";"
           | "if" "(" expression ")" statement ( "else" statement )?
           | "while" "(" expression ")" statement
           | "return" expression? ";"
           | block | expression ";"
expression → or
or         → and ( "||" and )*
and        → equality ( "&&" equality )*
equality   → comparison ( ( "==" | "!=" ) comparison )*
comparison → term ( ( "<" | "<=" | ">" | ">=" ) term )?
term       → factor ( ( "+" | "-" ) factor )*
factor     → unary ( ( "*" | "/" | "%" ) unary )*
unary      → ( "-" | "!" ) unary | primary
primary    → INT | "true" | "false" | IDENT | IDENT "(" args? ")" | "(" expression ")"
```

The loops (`( "+" factor )*`) build left-associative trees, so `1 - 2 - 3` is `(1 - 2) - 3`. A comparison is allowed at most once per level, so `a < b < c` is a syntax error rather than a type error about comparing a bool with an int.

**Frames.** The checker gives every parameter and local variable a slot number. The VM keeps everything on one `long[]` stack: a call's arguments are already on the stack, so they simply become slots 0..n-1 of the new frame. The locals follow, then that function's operand stack. Booleans are 0 and 1, so the VM needs no type tags; the checker has already proven that types are never mixed.

**Bytecode** for `fib` (`./gradlew run --args="dis examples/fib.mini"`):

```
fn fib  params=1 locals=1 max_stack=3
     6  LOAD           0          ; line 3
     8  CONST          2
    10  LT
    11  JUMP_IF_FALSE  -> 16
    13  LOAD           0          ; line 4
    15  RET
    16  LOAD           0          ; line 6
    18  CONST          1
    20  SUB
    21  CALL           fib
    23  LOAD           0
    25  CONST          2
    27  SUB
    28  CALL           fib
    30  ADD
    31  RET
```

`max_stack` is computed at compile time by tracking each instruction's effect on stack height. On every call the VM checks that the frame fits, and grows the stack array if it doesn't, so there is no per-push bounds check.

**Short-circuit evaluation** is compiled to jumps. `a && b` becomes `a; JUMP_IF_FALSE f; b; JUMP end; f: CONST 0; end:`, so `b` never runs when `a` is false.

## Installation

Requires JDK 21.

```bash
git clone https://github.com/AhmadElAmeer/java-expression-compiler.git
cd java-expression-compiler
./gradlew build          # downloads Gradle 8.14.3 and JUnit on first run, compiles, runs the tests
```

## Usage

```bash
./gradlew run --args="run examples/fib.mini"            # compile and run on the VM
./gradlew run --args="run examples/fib.mini -O"         # with constant folding
./gradlew run --args="run examples/fib.mini --interp"   # on the tree-walking interpreter
./gradlew run --args="run loop.mini --max-steps 1000000"
./gradlew run --args="dis examples/constants.mini -O"   # show the bytecode
./gradlew run --args="fuzz 5000 1"                      # 5,000 random programs, seeds 1 to 5000
./gradlew run --args="bench"                            # timings below
```

After `./gradlew build`, `java -cp build/classes/java/main compiler.Main …` does the same without Gradle's start-up time. Exit codes: 0 success, 1 compile error, 2 runtime error.

A runtime error stops the program after whatever it already printed:

```
$ java -cp build/classes/java/main compiler.Main run examples/errors.mini
5
runtime error at line 2: division by zero
```

## Results

From [docs/bench.txt](docs/bench.txt): two full runs of `bench 15`, each cell the median of 15 timed runs after 2 warm-up runs, on a **2-core** Intel Xeon @ 2.10 GHz with Java 21. Times below are from the first run. The second run's times differed by up to 25% (e.g. `fib` on the VM: 37 then 46 ms), and the speed ratios stayed in the same range.

| program | what it stresses | tree walker | VM | VM `-O` | VM vs tree | instructions run (VM → `-O`) |
|---|---|---:|---:|---:|---:|---|
| `fib` (27) | 635,621 recursive calls | 173 ms | 37 ms | 37 ms | **4.7×** | 6,356,210 → same |
| `primes` (< 60,000) | nested loops, `%` | 234 ms | 140 ms | 134 ms | 1.7× | 24,089,841 → same |
| `collatz` (< 30,000) | long-running loops | 467 ms | 334 ms | 337 ms | 1.4× | 58,952,353 → same |
| `gcd` (300 × 300) | calls inside loops | 70 ms | 48 ms | 61 ms* | 1.4× | 7,564,399 → same |
| `constants` | written to be foldable | 70 ms | 48 ms | 30 ms | 1.5× | 8,200,017 → 5,400,013 (**−34%**) |

\* Identical bytecode to the column before it, so this difference is noise; the second run measured 42.5 and 42.8 ms.

All three executors print the same answers, and the answers were checked in Python: 196418, 6057, 26623 (307 steps), 331884 and −2800750.

What the numbers show:

1. **The VM wins most on calls.** On `fib` it's 4–5× faster. The tree walker allocates a frame array per call and uses an exception to return, while the VM's call is a few array writes on a shared stack.
2. **On plain loops the gap is small, 1.4–1.8×.** The JVM's JIT compiles the tree walker's recursive `eval` well, and the VM pays for a `switch` dispatch on every instruction. Counting instructions for the step limit also costs a lot. As an experiment, I removed that check from a copy of the VM: `collatz` dropped from 334 to 263 ms, `primes` from 140 to 107 ms and `gcd` from 48 to 34 ms, a 22–29% saving. The check is still in the published VM because the limit is what stops runaway programs. Checking only on backward jumps and calls would keep that protection for less cost; that's listed below as an improvement.
3. **Constant folding helps only when there are constants to fold.** On `constants`, which was written for it, it removes 34% of the instructions executed and about a third of the time. On the four ordinary programs it changes nothing, which is the honest result: their expressions involve variables. Folding through variables needs constant propagation.
4. **The instruction counts are exact and repeatable;** the times varied by up to 25% between runs. Treat time differences smaller than that as noise.

## Testing

```bash
./gradlew test            # 36 tests, about 10 seconds
```

- **Front end:** token kinds and exact positions; precedence and associativity of every operator, checked by printing the tree fully parenthesized; nine syntax errors, each with its message and position; the nesting limit; and the caret rendering.
- **Checker:** undefined names, scope rules (shadowing, block-local variables, `let x = x`), operator types, assignment and declaration types, call arity and argument types, `return` rules, and the "every path returns" analysis.
- **Execution:** every test program runs on the interpreter, the VM and the optimized VM, and all three must agree. The tests cover:
  - the examples' answers, checked independently in Python;
  - Java-compatible `/` and `%` with negative numbers;
  - each runtime error and its line, including inside a multi-line expression and inside a function;
  - short-circuit evaluation, and left-to-right evaluation of operands and arguments;
  - recursion and mutual recursion;
  - the exact 10,000-call depth limit and the step limit;
  - stack growth past its initial size (a 300-argument call);
  - a 100,000-term expression.
- **Compiler:** the exact bytecode for `fib`; folding (instruction counts before and after); dead branches removed; folding never hides runtime errors and never drops calls that would run; constants are shared in the pool.
- **Fuzzing:** 1,500 random programs must behave identically in all three executors. A separate test checks that the generator really produces every feature (calls, loops, `else`, each operator, overflow-sized literals), so the fuzzer can't quietly become weaker.

**Planted bugs:** 20 bugs were planted one at a time:
- **parser:** unary minus binding looser than `*`, and `-` made right-associative;
- **checker:** an `if` without `else` counting as returning, and name lookup searching the outermost scope first;
- **compiler:** `||` pushing the wrong constant, folding that swallows errors or drops `false &&`, the `while (true)` body lost by the optimizer, and stack depth miscounted after calls;
- **VM:** frame base off by one, call depth limit off by one, the stack never growing, errors reported at the next instruction's line, and `Long.MIN_VALUE / -1` not treated as overflow;
- **interpreter:** right operand evaluated first, and the wrong line on errors;
- **lexer:** column not reset after a newline;
- **fuzzer:** never generating calls, never generating `else`, and collapsed seeds.

All 20 are caught by the final suite. Three got through at first (the multi-line error line, the dropped `while (true)` body, and a generator that stopped calling functions), and a test was added for each. A 21st change, letting `term()` also accept `*`, survived because it's equivalent: `factor()` has always consumed every `*` first, so no input parses differently.

## Design decisions and challenges

- **Why two executors.** A VM's bugs (a wrong jump target, an off-by-one frame base) don't crash. They make it print a wrong number, and a hand-written test only catches the cases someone wrote down. The tree-walking interpreter shares only the checker and the arithmetic helpers with the VM, and is short enough to check by reading. Agreement on thousands of random programs, outputs *and* errors, is much stronger evidence than any single test.
- **The fuzzer had a bug of its own.** `java.util.Random`'s first `nextInt(4)` is almost the same for consecutive seeds, so every generated program had exactly two functions. The test that checks the generator's coverage caught it, and `SplittableRandom` fixed it. Without that test the fuzzer would have looked fine while never testing programs with zero, one or three functions.
- **Errors are part of the behaviour.** The optimizer could have folded `1 / 0` into something, or dropped a call in `false && f()` that would never run anyway. The rule is that a folded program must do exactly what the unfolded one does, and the differential tests compare errors and their line numbers, not just output.
- **Typed language, untagged VM.** Because the checker proves every value's type, the VM stores plain `long`s with no tags and no runtime type checks. Most of a dynamically typed VM's work goes away.
- **Stack depth.** Both the parser and the checker recurse, so a deeply nested program could crash them with a Java `StackOverflowError`. Parentheses, unary operators and statements are limited to 256 levels of nesting, which is safe on any thread. Long operator chains such as `1 + 1 + … + 1` aren't nested in the source, so they aren't limited. The command-line tool runs on a thread with a 1 GB stack, which handles a 100,000-term expression. The first version allowed 1,000 levels and crashed on a normal thread, which a test caught.
- **Conservative return analysis.** `while (true) { return 1; }` is reported as possibly not returning, because loops never count. That's the same choice Java makes for non-constant conditions, and it keeps the analysis simple. A `return` after the loop satisfies it.

## Future improvements

- Recover after a syntax error and report several errors at once.
- Arrays and strings, which need heap objects and garbage collection, or at least bounds checks.
- Check the step limit only on backward jumps and calls. Every runaway program must pass one of those, and the measurement above shows the per-instruction check costs 22–29% on loops.
- A register-based VM or superinstructions (`LOAD; LOAD; ADD`) to compare against the stack design.
- More optimizations, each checked by the same differential fuzzer: constant propagation through variables, and jump threading.
- Compile to JVM bytecode with the class-file API, and let HotSpot JIT-compile the result.

## What this project teaches

- How a compiler is split into phases, and what each phase guarantees to the next.
- Writing a recursive-descent parser, and how the grammar's structure decides precedence and associativity.
- Scopes, symbol tables and type checking, with error messages that point at the problem.
- Code generation for a stack machine: expressions, jumps and backpatching, short-circuit logic, call frames.
- How a bytecode VM executes, and why it beats walking a tree.
- Testing a compiler: differential testing, random program generation, and checking that the test generator itself is good enough.

## License

MIT — see [LICENSE](LICENSE).
