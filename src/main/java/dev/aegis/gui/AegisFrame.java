package dev.aegis.gui;

import dev.aegis.Aegis;
import dev.aegis.analysis.*;
import dev.aegis.decompile.SourceDecompiler;
import dev.aegis.deobfuscate.DeobfuscationInspector;
import dev.aegis.rename.*;
import dev.aegis.util.*;
import dev.aegis.workspace.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class AegisFrame extends JFrame {
    static { Theme.install(); }

    private final AnalyzerService analyzer = new AnalyzerService();
    private final SourceDecompiler decompiler = new SourceDecompiler();
    private final DeobfuscationInspector deobfuscationInspector = new DeobfuscationInspector();
    private final RenamerPlanner renamer = new RenamerPlanner();
    private Workspace workspace;
    private MappingSet mappings = new MappingSet();
    private String selectedEntry;

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("No workspace");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JTextArea source = Theme.codeArea();
    private final JTextArea bytecode = Theme.codeArea();
    private final JTextArea analysis = Theme.codeArea();
    private final JTextArea deobfuscation = Theme.codeArea();
    private final JTextArea mappingText = Theme.codeArea();
    private final JTextArea log = Theme.codeArea();
    private final JLabel status = new JLabel("Ready — Aegis Native / zero third-party runtime dependencies");
    private final JLabel fileLabel = new JLabel("No file open");
    private final JProgressBar progress = new JProgressBar();
    private final JCheckBox aggressive = new JCheckBox("Aggressive renamer");
    private final JButton analyzeBtn = new JButton("Analyze");
    private final JButton renameBtn = new JButton("Native Rename");
    private final JButton exportBtn = new JButton("Export Clean JAR");
    private final JButton saveMapBtn = new JButton("Save Mappings");

    public AegisFrame() {
        super(Aegis.NAME + " " + Aegis.VERSION);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 680));
        setSize(1440, 900);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(Theme.BG);
        setJMenuBar(menu());
        add(top(), BorderLayout.NORTH);
        add(center(), BorderLayout.CENTER);
        add(bottom(), BorderLayout.SOUTH);
        configureTree();
        updateButtons();
    }

    public void openFromCommandLine(String path) { openWorkspace(Path.of(path)); }

    private JMenuBar menu() {
        JMenuBar b = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open..."); open.addActionListener(e -> chooseOpen());
        JMenuItem attach = new JMenuItem("Attach source JAR/ZIP..."); attach.addActionListener(e -> chooseAttachSources());
        JMenuItem export = new JMenuItem("Export deobfuscated JAR..."); export.addActionListener(e -> chooseExport());
        JMenuItem exit = new JMenuItem("Exit"); exit.addActionListener(e -> dispose());
        file.add(open); file.add(attach); file.add(export); file.addSeparator(); file.add(exit);
        JMenu tools = new JMenu("Tools");
        JMenuItem a = new JMenuItem("Analyze obfuscation"); a.addActionListener(e -> runAnalysis());
        JMenuItem r = new JMenuItem("Generate native semantic mappings"); r.addActionListener(e -> runRenamer());
        tools.add(a); tools.add(r);
        b.add(file); b.add(tools); return b;
    }

    private JComponent top() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Theme.PANEL); outer.setBorder(BorderFactory.createMatteBorder(0,0,1,0,Theme.BORDER));
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8)); p.setOpaque(false);
        JButton open = new JButton("Open"); open.addActionListener(e -> chooseOpen());
        analyzeBtn.addActionListener(e -> runAnalysis()); renameBtn.addActionListener(e -> runRenamer());
        exportBtn.addActionListener(e -> chooseExport()); saveMapBtn.addActionListener(e -> saveMappings());
        p.add(open); p.add(analyzeBtn); p.add(renameBtn); p.add(exportBtn); p.add(saveMapBtn);
        p.add(new JSeparator(SwingConstants.VERTICAL));
        JLabel engine = new JLabel("Decompiler: Aegis Native"); engine.setForeground(Theme.ACCENT); p.add(engine);
        aggressive.setToolTipText("Renames more private members in the source/mapping view."); p.add(aggressive);
        fileLabel.setForeground(Theme.MUTED); fileLabel.setBorder(new EmptyBorder(0,8,0,12));
        outer.add(p, BorderLayout.CENTER); outer.add(fileLabel, BorderLayout.EAST); return outer;
    }

    private JComponent center() {
        JScrollPane left = new JScrollPane(tree); left.setPreferredSize(new Dimension(330, 600)); left.setBorder(BorderFactory.createEmptyBorder());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Source", scroll(source)); tabs.addTab("Bytecode", scroll(bytecode)); tabs.addTab("Deobfuscator", scroll(deobfuscation)); tabs.addTab("Analysis", scroll(analysis));
        tabs.addTab("Mappings", scroll(mappingText)); tabs.addTab("Log", scroll(log));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, tabs); split.setDividerLocation(330); split.setResizeWeight(0); split.setBorder(null);
        return split;
    }

    private JComponent bottom() {
        JPanel p = new JPanel(new BorderLayout()); p.setBackground(Theme.PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,Theme.BORDER), new EmptyBorder(6,10,6,10)));
        progress.setIndeterminate(true); progress.setVisible(false); progress.setPreferredSize(new Dimension(150, 12));
        status.setForeground(Theme.MUTED); p.add(status, BorderLayout.CENTER); p.add(progress, BorderLayout.EAST); return p;
    }

    private JScrollPane scroll(JTextArea a) { JScrollPane s = new JScrollPane(a); s.setBorder(null); return s; }

    private void configureTree() {
        tree.setRootVisible(true); tree.setShowsRootHandles(true); tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t,Object v,boolean sel,boolean exp,boolean leaf,int row,boolean focus) {
                super.getTreeCellRendererComponent(t,v,sel,exp,leaf,row,focus); setOpaque(true); setBackground(sel?Theme.SELECTION:Theme.PANEL); setForeground(sel?Color.WHITE:Theme.TEXT);
                if (v instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof NodeData d) setText(d.label()); return this;
            }
        });
        tree.addTreeSelectionListener(e -> selection());
    }

    private void chooseOpen() {
        JFileChooser c = new JFileChooser(); c.setFileFilter(new FileNameExtensionFilter("JVM bytecode (*.jar, *.zip, *.jmod, *.war, *.class)", "jar","zip","jmod","war","class"));
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openWorkspace(c.getSelectedFile().toPath());
    }

    private void chooseAttachSources() {
        if(workspace==null)return;JFileChooser c=new JFileChooser();c.setFileFilter(new FileNameExtensionFilter("Java source archives (*.jar, *.zip)","jar","zip"));
        if(c.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path p=c.getSelectedFile().toPath();
        task("Attaching source archive...",()->workspace.attachSources(p),count->{status.setText("Attached "+count+" Java source file(s) — exact comments available when line metadata matches");log.append((log.getText().isEmpty()?"":"\n")+"Attached sources: "+p+" ("+count+" .java files)");selection();});
    }

    private void openWorkspace(Path path) {
        task("Opening " + path.getFileName() + "...", () -> Workspace.load(path), w -> {
            workspace = w; mappings = new MappingSet(); selectedEntry = null; source.setText(""); bytecode.setText(""); deobfuscation.setText(""); mappingText.setText("");
            log.setText(w.diagnostics().isEmpty()?"":String.join("\n",w.diagnostics())); fileLabel.setText(path.getFileName().toString()); rebuildTree(); updateButtons();
            status.setText("Loaded " + w.classes().size() + " classes — parsed by Aegis native classfile core"); runAnalysis();
        });
    }

    private void rebuildTree() {
        root.removeAllChildren(); root.setUserObject(workspace == null ? "No workspace" : workspace.source().getFileName().toString());
        DefaultMutableTreeNode classes = new DefaultMutableTreeNode("Classes"); DefaultMutableTreeNode resources = new DefaultMutableTreeNode("Resources");
        root.add(classes); root.add(resources);
        if (workspace != null) {
            for (ClassUnit u : workspace.classes()) classes.add(new DefaultMutableTreeNode(new NodeData(u.binaryName(),u.entryName(),true)));
            for (String e : workspace.entryNames()) if (!Workspace.isClassEntry(e)) resources.add(new DefaultMutableTreeNode(new NodeData(e,e,false)));
        }
        treeModel.reload(); for (int i=0;i<Math.min(3,tree.getRowCount());i++) tree.expandRow(i);
    }

    private void selection() {
        Object x = tree.getLastSelectedPathComponent();
        if (!(x instanceof DefaultMutableTreeNode n) || !(n.getUserObject() instanceof NodeData d) || workspace == null) return;
        if (!d.isClass()) { selectedEntry=null; byte[] b=workspace.entryBytes(d.entry()); source.setText(ResourcePreview.preview(d.entry(),b)); bytecode.setText(""); deobfuscation.setText(""); return; }
        selectedEntry=d.entry(); showClass(d.entry());
    }

    private void showClass(String entry) {
        if (workspace == null) return;
        Optional<ClassUnit> o=workspace.classByEntry(entry); if (o.isEmpty()) return; ClassUnit u=o.get();
        task("Decompiling " + u.simpleName() + " with Aegis Native...", () -> {
            String sourceName=null; try { sourceName=dev.aegis.classfile.DebugMetadata.sourceFile(dev.aegis.classfile.ClassFileParser.parse(u.bytes())); } catch(RuntimeException ignored) {}
            String bundled=workspace.bundledSource(u.internalName(),sourceName).orElse(null);
            String s=decompiler.decompile(u.bytes(),mappings,bundled); String b=BytecodePrinter.print(u.bytes()); String d=deobfuscationInspector.inspect(u.bytes()); return new String[]{s,b,d};
        }, pair -> { if (Objects.equals(selectedEntry,entry)) { source.setText(pair[0]); source.setCaretPosition(0); bytecode.setText(pair[1]); bytecode.setCaretPosition(0); deobfuscation.setText(pair[2]); deobfuscation.setCaretPosition(0); status.setText("Aegis Native: " + u.binaryName() + " — Java " + u.javaVersion()); }});
    }

    private void runAnalysis() {
        if (workspace==null) return; task("Analyzing bytecode...", () -> analyzer.analyze(workspace), r -> { analysis.setText(r.format()); analysis.setCaretPosition(0); status.setText("Obfuscation score: " + r.score() + "/100"); });
    }

    private void runRenamer() {
        if (workspace==null) return; task("Building semantic mappings...", () -> renamer.plan(workspace,aggressive.isSelected()), m -> {
            mappings=m; mappingText.setText(m.summary()); mappingText.setCaretPosition(0); status.setText("Mappings: " + m.classes().size()+" classes, "+m.fields().size()+" fields, "+m.methods().size()+" methods");
            if (selectedEntry!=null) showClass(selectedEntry); updateButtons();
        });
    }

    private void chooseExport() {
        if (workspace==null) return; JFileChooser c=new JFileChooser(); c.setSelectedFile(new java.io.File("aegis-deobfuscated.jar"));
        if (c.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return; Path p=c.getSelectedFile().toPath();
        task("Running native bytecode cleaner...", () -> { var r = workspace.exportDeobfuscated(p); return new Object[]{p,r}; }, x -> { var r=(dev.aegis.deobfuscate.NativeBytecodeCleaner.CleanResult)x[1]; status.setText("Exported clean JAR: "+r.methodsChanged()+" methods, "+r.instructionsRewritten()+" rewrites"); log.append((log.getText().isEmpty()?"":"\n")+String.join("\n",r.notes())+"\n"); });
    }

    private void saveMappings() {
        if (mappings.isEmpty()) return; JFileChooser c=new JFileChooser(); c.setSelectedFile(new java.io.File("aegis-mappings.tiny"));
        if (c.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return; try { Files.writeString(c.getSelectedFile().toPath(),mappings.toTinyV2(), StandardCharsets.UTF_8); status.setText("Mappings saved"); }
        catch(Exception ex){ error(ex); }
    }

    private <T> void task(String message, Callable<T> work, Consumer<T> done) {
        status.setText(message); progress.setVisible(true); setBusy(true);
        new SwingWorker<T,Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }
            @Override protected void done() { try { done.accept(get()); } catch(Exception ex){ error(ex.getCause()==null?ex:ex.getCause()); } finally { progress.setVisible(false); setBusy(false); updateButtons(); } }
        }.execute();
    }

    private void setBusy(boolean b) { analyzeBtn.setEnabled(!b && workspace!=null); renameBtn.setEnabled(!b && workspace!=null); exportBtn.setEnabled(!b && workspace!=null); saveMapBtn.setEnabled(!b && !mappings.isEmpty()); }
    private void updateButtons(){ setBusy(false); }
    private void error(Throwable ex){ log.append((log.getText().isEmpty()?"":"\n") + ex + "\n"); status.setText("Error: "+ex.getMessage()); JOptionPane.showMessageDialog(this,ex.toString(),"Aegis",JOptionPane.ERROR_MESSAGE); }

    private record NodeData(String label,String entry,boolean isClass){ @Override public String toString(){return label;} }
}
