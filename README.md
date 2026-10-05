# Aegis Decompiler

Aegis is a native Java/JVM decompiler, static deobfuscator and semantic renaming workbench built from scratch.

It reads JVM classfiles directly, reconstructs Java source, analyzes and simplifies obfuscated control flow, infers readable names from surviving program semantics, and can export conservatively cleaned JARs. The runtime has no third-party bytecode or decompiler dependencies.

## Highlights

- Native `.class` parser and JVM bytecode decoder
- Control-flow graph, dominator and loop analysis
- Static constant propagation and opaque-predicate resolution
- Constant switch/dispatcher resolution
- Sandboxed constant/string helper evaluation without loading target classes
- Whole-program semantic renaming across inheritance and interface namespaces
- Confidence-scored rename decisions with explanations
- Generic-signature assisted field, method and parameter recovery
- Java 16+ record reconstruction
- Sealed type and `permits` reconstruction
- `BootstrapMethods`, `StringConcatFactory` and common `LambdaMetafactory` reconstruction
- Exact original comment recovery when matching Java source is available
- Explicitly labelled inferred comments when original source is unavailable
- Clean JAR export with verifier-conscious, length-preserving rewrites
- Swing desktop GUI
- Zero Maven requirement

## Native architecture

```text
JAR / CLASS / WAR / JMOD / ZIP
            |
            v
    Aegis ClassFile Parser
            |
            v
    JVM Bytecode Decoder
            |
            +--------------------+
            |                    |
            v                    v
     Control-Flow Graph    Metadata / Signatures
            |                    |
            v                    v
 Abstract Interpretation   Semantic Analysis
            |                    |
            v                    v
      Deobfuscation       Semantic Renamer
            |                    |
            +----------+---------+
                       |
                       v
                Java Structurer
                       |
                       v
                 Java Source
```

## Decompiler

Aegis currently reconstructs many common JVM patterns, including:

- constructors and static initializers;
- field access and assignments;
- method calls and object construction;
- arrays and array stores;
- arithmetic, casts and comparisons;
- `if` / `else`;
- boolean short-circuit expressions;
- ternary expressions;
- common loops;
- `tableswitch` and `lookupswitch`;
- common `try/catch` regions;
- continuation after exception handlers;
- modern string concatenation through `StringConcatFactory`;
- method references and captured lambdas through common `LambdaMetafactory` bootstraps;
- generic class, field and method signatures;
- records, record components and implicit record-member suppression;
- sealed classes/interfaces and `permits` lists.

Unknown attributes are preserved by the classfile model even when Aegis does not yet interpret them for source reconstruction.

## Advanced deobfuscation

The deobfuscator works on Aegis' own decoded instructions and CFG. It does not execute arbitrary code from the analyzed archive.

Implemented analyses include:

- basic-block construction;
- normal and exceptional CFG edges;
- dominator analysis;
- natural-loop discovery;
- forward abstract interpretation of locals and operand stack;
- constant propagation across blocks;
- arithmetic and bitwise constant folding;
- `static final ConstantValue` propagation;
- pure same-class helper evaluation with execution budgets;
- selected pure JDK call evaluation;
- newly allocated object/array non-null tracking;
- opaque conditional resolution;
- constant switch and dispatcher resolution;
- unreachable-instruction discovery;
- jump-chain threading;
- local liveness analysis;
- dead local-store detection;
- NOP/junk accounting;
- constrained static string-decryptor interpretation.

Selected constant JDK evaluation includes common operations from `String`, `Objects`, `Integer`, `Long` and `Math`, including parsing, comparisons, substring/concat operations, rotations, bit counts and simple numeric min/max/abs operations.

## Semantic Renamer

The renamer is whole-program and hierarchy-aware. It does not rename methods independently when they participate in the same override/interface namespace.

Signals used by the current renamer include:

- direct getters and setters;
- field types and generic signatures;
- collection element types;
- map key/value types;
- delegation and wrapper shapes;
- diagnostic and semantic string literals;
- method return types;
- factory, predicate, validation and state-update shapes;
- dominant call clusters;
- whole-class semantic noun/role profiles;
- compiler-generated bridges and lambda helpers;
- inheritance and interface groups;
- surviving `MethodParameters` and `LocalVariableTable` data;
- parameter-to-field assignment patterns;
- parameter use at meaningful call sites.

Aegis combines multiple independent signals instead of accepting the first heuristic match. Corroborating evidence raises confidence; conflicting evidence keeps the decision conservative.

Every inferred mapping contains:

- target name;
- confidence score;
- explanation/reason.

Mappings can be exported as Tiny v2, Aegis JSON or ProGuard-style text.

### Safe and Aggressive modes

Safe mode reserves names observed through common name-sensitive APIs. Aggressive mode is intended for readability-oriented analysis and may rename more private or suspicious symbols.

Reflection analysis covers common patterns such as:

- `Class.forName`;
- `ClassLoader.loadClass`;
- `Class.getMethod` / `getDeclaredMethod`;
- `Class.getField` / `getDeclaredField`;
- `MethodHandles.Lookup.find*`;
- atomic field updater factories.

The reflection scanner uses a small abstract operand-stack interpreter so the literal reserved is the actual call argument rather than simply the nearest string constant in bytecode.

Dynamic reflection remains fundamentally uncertain and is reported instead of guessed.

## Comment recovery

Normal Java comments are not stored in ordinary JVM classfiles. Aegis therefore separates three different sources of information.

### Surviving metadata

When available, Aegis reports metadata such as:

- `SourceFile`;
- `SourceDebugExtension` / SMAP source names;
- `LineNumberTable` ranges;
- `LocalVariableTable` names;
- `MethodParameters`;
- generic `Signature` data.

These are marked as recovered metadata.

### Inferred comments

When source is unavailable, Aegis can emit semantic notes inferred from bytecode behavior. These comments are always labelled as Aegis-inferred and include a confidence value. They are never presented as original developer comments.

## Clean JAR export

`Export Clean JAR` applies only conservative rewrites that are designed to preserve instruction layout and verifier-sensitive structures.

Current rewrites include:

- proven always-false conditional cleanup;
- dead local stores to `pop` / `pop2` where safe;
- redundant goto cleanup;
- representable goto threading;
- constant switch edge redirection while preserving switch layout and stack behavior.

Modified archives have invalidated signature files under `META-INF` removed. Cleaner changes are intentionally more conservative than source-level simplification.

## Supported inputs

Aegis can open:

- `.class`
- `.jar`
- `.zip`
- `.war`
- `.jmod`

The classfile parser is not tied to a single Java source version. Aegis recognizes the standard constant-pool forms used by modern JVM classfiles and preserves unknown attributes, while source reconstruction coverage depends on the language/JVM feature involved.

## Requirements

- Windows 11, Linux or another desktop OS with a compatible JDK
- JDK 21 or newer to build Aegis

## Build

### Windows

```bat
build.bat
run.bat
```

The build output is:

```text
build/Aegis.jar
```

## Typical workflow

1. Open a JAR, classfile or supported JVM archive.
2. Review the `Analysis` tab for obfuscation and metadata information.
3. Review `Deobfuscator` for CFG and simplification findings.
4. Run `Native Rename` in Safe mode first.
5. Inspect confidence scores and reasons in `Mappings`.
6. Enable Aggressive renaming when readability is more important than conservative name preservation.
7. Attach a source archive when original source/comment recovery is available.
8. Export mappings or a conservatively cleaned JAR.


## Current limitations

A universal perfect decompiler or original-name oracle is not possible when information has been intentionally removed. Current difficult cases include:

- arbitrary irreducible control-flow graphs;
- overlapping or highly transformed exception regions;
- complete `finally` synthesis in every bytecode shape;
- all custom `invokedynamic` bootstraps;
- old `jsr` / `ret` subroutines;
- complete monitor-to-`synchronized` restructuring;
- runtime-generated reflection names;
- virtualization/VM-based obfuscators;
