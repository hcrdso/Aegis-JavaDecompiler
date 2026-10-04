package dev.aegis.cfg;

import dev.aegis.classfile.CodeAttribute;
import dev.aegis.classfile.Instruction;
import java.util.*;

/** Native JVM control-flow graph used by the decompiler and deobfuscator. */
public final class ControlFlowGraph {
    public static final class BasicBlock {
        private final int id;
        private final int startOffset;
        private final int endOffset;
        private final List<Instruction> instructions;
        private final LinkedHashSet<BasicBlock> successors = new LinkedHashSet<>();
        private final LinkedHashSet<BasicBlock> predecessors = new LinkedHashSet<>();
        private final LinkedHashSet<BasicBlock> exceptionalSuccessors = new LinkedHashSet<>();

        BasicBlock(int id, int startOffset, int endOffset, List<Instruction> instructions) {
            this.id = id; this.startOffset = startOffset; this.endOffset = endOffset;
            this.instructions = List.copyOf(instructions);
        }
        public int id() { return id; }
        public int startOffset() { return startOffset; }
        public int endOffset() { return endOffset; }
        public List<Instruction> instructions() { return instructions; }
        public Set<BasicBlock> successors() { return Collections.unmodifiableSet(successors); }
        public Set<BasicBlock> predecessors() { return Collections.unmodifiableSet(predecessors); }
        public Set<BasicBlock> exceptionalSuccessors() { return Collections.unmodifiableSet(exceptionalSuccessors); }
        public Instruction last() { return instructions.isEmpty() ? null : instructions.get(instructions.size() - 1); }
        @Override public String toString() { return "B" + id + "[" + startOffset + "," + endOffset + ")"; }
    }

    private final List<BasicBlock> blocks;
    private final Map<Integer, BasicBlock> byStart;
    private final Map<Integer, BasicBlock> byOffset;
    private final BasicBlock entry;

    private ControlFlowGraph(List<BasicBlock> blocks, Map<Integer, BasicBlock> byStart,
                             Map<Integer, BasicBlock> byOffset, BasicBlock entry) {
        this.blocks = List.copyOf(blocks); this.byStart = Map.copyOf(byStart);
        this.byOffset = Map.copyOf(byOffset); this.entry = entry;
    }

    public static ControlFlowGraph build(List<Instruction> insns, CodeAttribute code) {
        if (insns.isEmpty()) return new ControlFlowGraph(List.of(), Map.of(), Map.of(), null);
        TreeSet<Integer> leaders = new TreeSet<>();
        leaders.add(insns.get(0).offset());
        Map<Integer, Integer> next = new HashMap<>();
        for (int i = 0; i < insns.size(); i++) {
            Instruction in = insns.get(i);
            int nextOffset = i + 1 < insns.size() ? insns.get(i + 1).offset() : code.code().length;
            next.put(in.offset(), nextOffset);
            for (int target : in.branchTargets()) if (target >= 0 && target < code.code().length) leaders.add(target);
            if (endsBlock(in.opcode()) && nextOffset < code.code().length) leaders.add(nextOffset);
        }
        for (CodeAttribute.ExceptionHandler h : code.exceptionTable()) {
            if (h.startPc() >= 0 && h.startPc() < code.code().length) leaders.add(h.startPc());
            if (h.endPc() >= 0 && h.endPc() < code.code().length) leaders.add(h.endPc());
            if (h.handlerPc() >= 0 && h.handlerPc() < code.code().length) leaders.add(h.handlerPc());
        }

        ArrayList<Integer> starts = new ArrayList<>(leaders);
        Map<Integer, Instruction> insnByOffset = new HashMap<>();
        for (Instruction i : insns) insnByOffset.put(i.offset(), i);
        ArrayList<BasicBlock> blocks = new ArrayList<>();
        LinkedHashMap<Integer, BasicBlock> byStart = new LinkedHashMap<>();
        HashMap<Integer, BasicBlock> byOffset = new HashMap<>();
        for (int n = 0; n < starts.size(); n++) {
            int start = starts.get(n);
            int end = n + 1 < starts.size() ? starts.get(n + 1) : code.code().length;
            ArrayList<Instruction> part = new ArrayList<>();
            for (Instruction i : insns) if (i.offset() >= start && i.offset() < end) part.add(i);
            if (part.isEmpty()) continue;
            BasicBlock b = new BasicBlock(blocks.size(), start, end, part);
            blocks.add(b); byStart.put(start, b);
            for (Instruction i : part) byOffset.put(i.offset(), b);
        }

        for (int bi = 0; bi < blocks.size(); bi++) {
            BasicBlock b = blocks.get(bi); Instruction last = b.last(); if (last == null) continue;
            int op = last.opcode();
            if (isConditional(op)) {
                addNormalEdge(b, byStart.get(last.branchTargets()[0]));
                if (bi + 1 < blocks.size()) addNormalEdge(b, blocks.get(bi + 1));
            } else if (isGoto(op) || op == 168 || op == 201) {
                if (last.branchTargets().length > 0) addNormalEdge(b, byStart.get(last.branchTargets()[0]));
            } else if (op == 170 || op == 171) {
                for (int t : last.branchTargets()) addNormalEdge(b, byStart.get(t));
            } else if (!isTerminal(op) && op != 169) {
                if (bi + 1 < blocks.size()) addNormalEdge(b, blocks.get(bi + 1));
            }
        }

        for (CodeAttribute.ExceptionHandler h : code.exceptionTable()) {
            BasicBlock handler = byStart.get(h.handlerPc()); if (handler == null) continue;
            for (BasicBlock b : blocks) {
                if (b.startOffset() < h.endPc() && b.endOffset() > h.startPc()) {
                    b.exceptionalSuccessors.add(handler);
                    handler.predecessors.add(b);
                }
            }
        }
        return new ControlFlowGraph(blocks, byStart, byOffset, blocks.isEmpty() ? null : blocks.get(0));
    }

    private static void addNormalEdge(BasicBlock from, BasicBlock to) {
        if (to == null) return;
        from.successors.add(to); to.predecessors.add(from);
    }

    public List<BasicBlock> blocks() { return blocks; }
    public BasicBlock entry() { return entry; }
    public BasicBlock blockAtStart(int offset) { return byStart.get(offset); }
    public BasicBlock blockContaining(int offset) { return byOffset.get(offset); }

    public Set<BasicBlock> reachableBlocks() {
        if (entry == null) return Set.of();
        LinkedHashSet<BasicBlock> seen = new LinkedHashSet<>();
        ArrayDeque<BasicBlock> q = new ArrayDeque<>(); q.add(entry);
        while (!q.isEmpty()) {
            BasicBlock b = q.removeFirst(); if (!seen.add(b)) continue;
            q.addAll(b.successors); q.addAll(b.exceptionalSuccessors);
        }
        return Collections.unmodifiableSet(seen);
    }

    public Map<BasicBlock, Set<BasicBlock>> dominators() {
        LinkedHashSet<BasicBlock> all = new LinkedHashSet<>(blocks);
        LinkedHashMap<BasicBlock, Set<BasicBlock>> dom = new LinkedHashMap<>();
        for (BasicBlock b : blocks) dom.put(b, b == entry ? new LinkedHashSet<>(List.of(b)) : new LinkedHashSet<>(all));
        boolean changed;
        do {
            changed = false;
            for (BasicBlock b : blocks) {
                if (b == entry) continue;
                LinkedHashSet<BasicBlock> next = new LinkedHashSet<>(all);
                if (b.predecessors.isEmpty()) next.clear();
                else for (BasicBlock p : b.predecessors) next.retainAll(dom.get(p));
                next.add(b);
                if (!next.equals(dom.get(b))) { dom.put(b, next); changed = true; }
            }
        } while (changed);
        LinkedHashMap<BasicBlock, Set<BasicBlock>> frozen = new LinkedHashMap<>();
        dom.forEach((k,v) -> frozen.put(k, Collections.unmodifiableSet(v)));
        return Collections.unmodifiableMap(frozen);
    }

    /** Natural loops found from edges whose target dominates the source. */
    public List<Set<BasicBlock>> naturalLoops() {
        Map<BasicBlock, Set<BasicBlock>> dom = dominators();
        ArrayList<Set<BasicBlock>> result = new ArrayList<>();
        for (BasicBlock tail : blocks) for (BasicBlock head : tail.successors) {
            if (!dom.getOrDefault(tail, Set.of()).contains(head)) continue;
            LinkedHashSet<BasicBlock> loop = new LinkedHashSet<>(); loop.add(head); loop.add(tail);
            ArrayDeque<BasicBlock> work = new ArrayDeque<>(); work.add(tail);
            while (!work.isEmpty()) {
                BasicBlock x = work.removeFirst();
                for (BasicBlock p : x.predecessors) if (loop.add(p) && p != head) work.add(p);
            }
            result.add(Collections.unmodifiableSet(loop));
        }
        return List.copyOf(result);
    }

    public static boolean isConditional(int op) {
        return (op >= 153 && op <= 166) || op == 198 || op == 199;
    }
    public static boolean isGoto(int op) { return op == 167 || op == 200; }
    public static boolean isTerminal(int op) { return (op >= 172 && op <= 177) || op == 191; }
    private static boolean endsBlock(int op) {
        return isConditional(op) || isGoto(op) || isTerminal(op) || op == 168 || op == 169 || op == 170 || op == 171 || op == 201;
    }
}
