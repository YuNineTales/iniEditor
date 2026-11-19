import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 여러 모듈 폴더의 INI 파일을 표로 보고 일괄/개별 수정하는 도구. (Java 8 호환)
 *
 *  - 루트폴더\모듈폴더\setup.ini 를 찾아 모듈별로 한 줄씩 표시
 *  - 컬럼은 ini 안에 실제로 있는 "[섹션] 키" 를 전부 읽어서 자동 생성
 *  - 셀을 직접 고치거나(모듈별 개별 값), 일괄 입력으로 체크한 모듈에 한 번에 채움
 *  - 변경된 셀만 저장. 나머지 줄/주석/순서/줄바꿈/BOM 유지, 저장 전 자동 백업
 *
 * 컴파일: javac -encoding UTF-8 IniEditor.java
 * 실행:   java IniEditor [루트폴더]      (jar: java -jar IniEditor.jar D:\exce)
 */
public class IniEditor {

    static final String DEFAULT_ROOT = "D:\\exce";
    static final String DEFAULT_FILE = "setup.ini";

    public static void main(String[] args) {
        final String initial = args.length > 0 ? args[0] : DEFAULT_ROOT;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() { new EditorFrame(initial).setVisible(true); }
        });
    }

    static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    static boolean isPortKey(String key) { return key.trim().toLowerCase().contains("port"); }
    static String colId(String section, String key) { return section.trim().toLowerCase() + "\u0000" + key.trim().toLowerCase(); }

    static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    // ---------------- INI 문서 (줄 단위 보존) ----------------
    static class IniDoc {
        private static final Pattern SECTION = Pattern.compile("^\\s*\\[([^\\]]+)]\\s*$");
        private static final Pattern ENTRY = Pattern.compile("^(\\s*)([^=;#\\[][^=]*?)(\\s*=[ \\t]*)(.*)$");

        final List<String> lines = new ArrayList<String>();
        String lineSep = "\r\n";
        boolean endsWithNewline = true;
        boolean utf8Bom = false;
        Charset charset;

        static IniDoc load(Path path, Charset cs) throws IOException {
            IniDoc doc = new IniDoc();
            doc.charset = cs;
            byte[] bytes = Files.readAllBytes(path);
            int offset = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                doc.utf8Bom = true;
                doc.charset = StandardCharsets.UTF_8;
                offset = 3;
            }
            String text = new String(bytes, offset, bytes.length - offset, doc.charset);
            doc.lineSep = text.contains("\r\n") ? "\r\n" : (text.contains("\n") ? "\n" : "\r\n");
            doc.endsWithNewline = text.isEmpty() || text.endsWith("\n");
            String[] arr = text.split("\r?\n", -1);
            int n = doc.endsWithNewline && arr.length > 0 ? arr.length - 1 : arr.length;
            doc.lines.addAll(Arrays.asList(arr).subList(0, n));
            return doc;
        }

        void save(Path path) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append(lineSep);
                sb.append(lines.get(i));
            }
            if (endsWithNewline && !lines.isEmpty()) sb.append(lineSep);
            byte[] content = sb.toString().getBytes(charset);
            if (utf8Bom) {
                byte[] withBom = new byte[content.length + 3];
                withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
                System.arraycopy(content, 0, withBom, 3, content.length);
                content = withBom;
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }

        /** 파일 안의 모든 (섹션, 키) 를 등장 순서대로. 섹션 밖(맨 위) 키는 섹션 "" */
        List<String[]> entries() {
            List<String[]> out = new ArrayList<String[]>();
            String cur = "";
            for (String line : lines) {
                Matcher s = SECTION.matcher(line);
                if (s.matches()) { cur = s.group(1).trim(); continue; }
                Matcher e = ENTRY.matcher(line);
                if (e.matches()) out.add(new String[]{cur, e.group(2).trim()});
            }
            return out;
        }

        /** 섹션 범위 [헤더줄, 다음섹션 직전). 섹션 ""(전역)은 헤더가 -1. 없으면 null */
        private int[] sectionRange(String section) {
            if (section.isEmpty()) {
                for (int i = 0; i < lines.size(); i++) {
                    if (SECTION.matcher(lines.get(i)).matches()) return new int[]{-1, i};
                }
                return new int[]{-1, lines.size()};
            }
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = SECTION.matcher(lines.get(i));
                if (m.matches()) {
                    if (start >= 0) return new int[]{start, i};
                    if (m.group(1).trim().equalsIgnoreCase(section)) start = i;
                }
            }
            return start >= 0 ? new int[]{start, lines.size()} : null;
        }

        private int findKey(int[] range, String key) {
            for (int i = range[0] + 1; i < range[1]; i++) {
                Matcher m = ENTRY.matcher(lines.get(i));
                if (m.matches() && m.group(2).trim().equalsIgnoreCase(key)) return i;
            }
            return -1;
        }

        String get(String section, String key) {
            int[] r = sectionRange(section);
            if (r == null) return null;
            int i = findKey(r, key);
            if (i < 0) return null;
            Matcher m = ENTRY.matcher(lines.get(i));
            return m.matches() ? m.group(4) : null;
        }

        void set(String section, String key, String value) {
            int[] r = sectionRange(section);
            if (r == null) {
                if (!lines.isEmpty() && !isBlank(lines.get(lines.size() - 1))) lines.add("");
                lines.add("[" + section + "]");
                lines.add(key + "=" + value);
                return;
            }
            int i = findKey(r, key);
            if (i >= 0) {
                Matcher m = ENTRY.matcher(lines.get(i));
                if (m.matches()) lines.set(i, m.group(1) + m.group(2) + m.group(3) + value);
                return;
            }
            int insertAt = r[0] + 1;
            for (int j = r[1] - 1; j > r[0]; j--) {
                if (!isBlank(lines.get(j))) { insertAt = j + 1; break; }
            }
            lines.add(insertAt, key + "=" + value);
        }
    }

    // ---------------- 데이터 ----------------
    /** 키 하나 (섹션 + 키) */
    static class Col {
        final String id, section, key, label;
        Col(String section, String key) {
            this.id = colId(section, key);
            this.section = section;
            this.key = key;
            this.label = secLabel(section) + " " + key;
        }
        @Override public String toString() { return key; }
    }

    /** 섹션 하나 = 위 표의 컬럼 하나. 안에 키 목록 */
    static class Sec {
        final String name, label;
        final List<Col> cols = new ArrayList<Col>();
        Sec(String name) { this.name = name; this.label = secLabel(name); }
        @Override public String toString() { return label; }
    }

    static String secLabel(String section) { return section.isEmpty() ? "(전역)" : "[" + section + "]"; }

    static class Module {
        final String name;
        final Path ini;
        boolean selected = true;
        String error;                                                      // 읽기 실패 메시지
        final Map<String, String> orig = new HashMap<String, String>();   // colId -> 파일의 현재 값
        final Map<String, String> edits = new HashMap<String, String>();  // colId -> 바꿀 값
        Module(String name, Path ini) { this.name = name; this.ini = ini; }

        void reload(Charset cs) {
            orig.clear();
            error = null;
            try {
                IniDoc doc = IniDoc.load(ini, cs);
                for (String[] e : doc.entries()) {
                    String id = colId(e[0], e[1]);
                    if (!orig.containsKey(id)) orig.put(id, doc.get(e[0], e[1]));  // 중복 키는 첫 번째 값
                }
            } catch (Exception ex) {
                error = ex.toString();
            }
        }

        String value(String id) { return edits.containsKey(id) ? edits.get(id) : orig.get(id); }

        void setCell(String id, String v) {
            String o = orig.get(id);
            boolean same = (o == null) ? v.isEmpty() : v.equals(o);
            if (same) edits.remove(id); else edits.put(id, v);
        }

        boolean hasEditIn(Sec s) {
            for (Col c : s.cols) if (edits.containsKey(c.id)) return true;
            return false;
        }
    }

    /** module2 < module10 이 되게 숫자 인식 정렬 */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (c != 0) return c;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    // ---------------- 위 표: 모듈 x 섹션 (셀 = 그 섹션의 키=값 묶음) ----------------
    static class ModuleTableModel extends AbstractTableModel {
        final List<Module> modules = new ArrayList<Module>();
        final List<Sec> secs = new ArrayList<Sec>();

        void setData(List<Module> ms, List<Sec> ss) {
            modules.clear(); modules.addAll(ms);
            secs.clear(); secs.addAll(ss);
            fireTableStructureChanged();
        }

        List<Col> allCols() {
            List<Col> out = new ArrayList<Col>();
            for (Sec s : secs) out.addAll(s.cols);
            return out;
        }

        @Override public int getRowCount() { return modules.size(); }
        @Override public int getColumnCount() { return 2 + secs.size(); }
        @Override public String getColumnName(int c) { return c == 0 ? "적용" : c == 1 ? "모듈" : secs.get(c - 2).label; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }

        @Override public Object getValueAt(int r, int c) {
            Module m = modules.get(r);
            if (c == 0) return m.selected;
            if (c == 1) return m.name;
            return summary(m, secs.get(c - 2), ", ");
        }

        @Override public void setValueAt(Object value, int r, int c) {
            if (c != 0) return;
            modules.get(r).selected = Boolean.TRUE.equals(value);
            fireTableCellUpdated(r, c);
        }

        /** "KEY=값, KEY2=값2" (이 모듈에 없는 키는 생략) */
        static String summary(Module m, Sec s, String sep) {
            StringBuilder sb = new StringBuilder();
            for (Col c : s.cols) {
                String v = m.value(c.id);
                if (v == null) continue;
                if (sb.length() > 0) sb.append(sep);
                sb.append(c.key).append('=');
                sb.append(v);
            }
            return sb.toString();
        }

        boolean isEdited(int r, int c) { return c >= 2 && modules.get(r).hasEditIn(secs.get(c - 2)); }

        int pendingCount() {
            int n = 0;
            for (Module m : modules) n += m.edits.size();
            return n;
        }
    }

    // ---------------- GUI ----------------
    static class EditorFrame extends JFrame {
        private static final Color EDITED = new Color(255, 240, 150);
        private static final Color PICKED = new Color(200, 220, 255);
        private static final Color PICKED_BORDER = new Color(40, 100, 220);

        private final JTextField rootField = new JTextField(26);
        private final JTextField fileField = new JTextField(DEFAULT_FILE, 9);
        private final JComboBox<String> charsetBox = new JComboBox<String>(new String[]{"MS949", "UTF-8"});
        private final JTextField filterField = new JTextField(14);
        private final JLabel summary = new JLabel(" ");
        private final ModuleTableModel model = new ModuleTableModel();
        private final JTable table = new JTable(model);
        private final List<TableColumn> allColumns = new ArrayList<TableColumn>();

        // 아래 편집 폼 (선택한 모듈 + 섹션)
        private final JPanel formPanel = new JPanel(new GridBagLayout());
        private final JLabel formTitle = new JLabel("위 표에서 셀(모듈 x 섹션)을 클릭해");
        /** 선택한 셀: 섹션(열) -> 모듈 행. 열마다 하나만, 클릭한 순서 유지 */
        private final LinkedHashMap<Sec, Integer> picks = new LinkedHashMap<Sec, Integer>();
        /** 폼에서 '적용' 체크된 키(colId). 값을 고치면 자동 체크, 저장 후에도 유지 → 다른 모듈에 다시 퍼뜨릴 수 있음 */
        private final Set<String> applyKeys = new HashSet<String>();
        private final JTextArea log = new JTextArea(6, 80);

        EditorFrame(String initialRoot) {
            super("INI 일괄 편집기");
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setLayout(new BorderLayout(6, 6));
            installErrorHandler();

            // ---- 상단 ----
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton browse = new JButton("찾아보기");
            JButton scan = new JButton("모듈 검색");
            top.add(new JLabel("루트 폴더")); top.add(rootField); top.add(browse);
            top.add(new JLabel("파일명")); top.add(fileField);
            top.add(new JLabel("인코딩")); top.add(charsetBox);
            top.add(scan);
            add(top, BorderLayout.NORTH);

            // ---- 위 표: 섹션 요약 ----
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.setRowHeight(22);
            table.getTableHeader().setReorderingAllowed(false);
            table.setDefaultRenderer(String.class, new SummaryRenderer());
            // JTable 기본 선택은 끄고 picks 로 직접 관리 (열마다 한 셀만 허용하려고)
            table.setRowSelectionAllowed(false);
            table.setColumnSelectionAllowed(false);

            JPanel tableBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton selAll = new JButton("전체 선택");
            JButton selNone = new JButton("전체 해제");
            tableBar.add(selAll); tableBar.add(selNone);
            tableBar.add(new JLabel("   섹션 필터")); tableBar.add(filterField);
            tableBar.add(summary);

            JPanel tablePanel = new JPanel(new BorderLayout());
            tablePanel.setBorder(BorderFactory.createTitledBorder("모듈 목록  (셀 클릭 → 아래에서 그 모듈의 섹션 수정 · 노란색 = 저장 대기)"));
            JScrollPane sp = new JScrollPane(table);
            sp.setPreferredSize(new Dimension(1100, 320));
            tablePanel.add(tableBar, BorderLayout.NORTH);
            tablePanel.add(sp, BorderLayout.CENTER);

            // ---- 아래: 선택한 모듈의 섹션 편집 폼 (KEY = [값]) ----
            formTitle.setFont(formTitle.getFont().deriveFont(Font.BOLD, 13f));
            formTitle.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
            JPanel formWrap = new JPanel(new BorderLayout());
            formWrap.add(formPanel, BorderLayout.NORTH);   // 위로 붙이기
            JScrollPane fsp = new JScrollPane(formWrap);
            fsp.setPreferredSize(new Dimension(1100, 260));
            fsp.getVerticalScrollBar().setUnitIncrement(16);

            JPanel secPanel = new JPanel(new BorderLayout());
            secPanel.setBorder(BorderFactory.createTitledBorder(
                    "섹션 편집  (노란 칸 = 고친 값 · 왼쪽 체크 = 일괄 적용할 키, 값을 고치면 자동 체크)"));
            JButton applyAll = new JButton("체크한 키 → 체크한 모듈에 적용");
            applyAll.setToolTipText("아래 폼에서 왼쪽 체크된 키의 값만, 위 표에서 '적용' 체크된 모듈 전부에 똑같이 넣음. 체크 안 된 키는 그대로");
            applyAll.addActionListener(e -> applyEditsToChecked());
            JPanel formHead = new JPanel(new BorderLayout());
            formHead.add(formTitle, BorderLayout.CENTER);
            JPanel headBtn = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
            headBtn.add(applyAll);
            formHead.add(headBtn, BorderLayout.EAST);
            secPanel.add(formHead, BorderLayout.NORTH);
            secPanel.add(fsp, BorderLayout.CENTER);

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePanel, secPanel);
            split.setResizeWeight(0.5);
            add(split, BorderLayout.CENTER);

            // ---- 하단 ----
            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton revert = new JButton("변경 취소");
            JButton save = new JButton("저장");
            btnRow.add(revert); btnRow.add(save);
            log.setEditable(false);
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JPanel bottom = new JPanel(new BorderLayout(6, 6));
            bottom.add(btnRow, BorderLayout.NORTH);
            bottom.add(new JScrollPane(log), BorderLayout.CENTER);
            bottom.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
            add(bottom, BorderLayout.SOUTH);

            // ---- 이벤트 ----
            browse.addActionListener(e -> {
                JFileChooser fc = new JFileChooser(isBlank(rootField.getText()) ? null : new File(rootField.getText()));
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    rootField.setText(fc.getSelectedFile().getAbsolutePath());
                    scanModules();
                }
            });
            scan.addActionListener(e -> scanModules());
            selAll.addActionListener(e -> setAllSelected(true));
            selNone.addActionListener(e -> setAllSelected(false));
            revert.addActionListener(e -> revertAll());
            save.addActionListener(e -> saveAll());
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    boolean add = e.isControlDown() || e.isMetaDown();   // 윈도우 Ctrl, 맥 ⌘
                    onCellClicked(table.rowAtPoint(e.getPoint()), table.columnAtPoint(e.getPoint()), add);
                }
            });
            filterField.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { applyFilter(); }
                public void removeUpdate(DocumentEvent e) { applyFilter(); }
                public void changedUpdate(DocumentEvent e) { applyFilter(); }
            });
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    if (confirmDiscard("저장 안 한 변경이 있어. 그래도 닫을까?")) dispose();
                }
            });

            pack();
            setLocationRelativeTo(null);

            rootField.setText(initialRoot);
            if (Files.isDirectory(Paths.get(initialRoot))) scanModules();
        }

        // ---- 예외는 전부 로그 + 파일로 남김 ----
        private void installErrorHandler() {
            Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
                String st = stackTrace(ex);
                try {
                    Files.write(Paths.get("IniEditor_error.log"),
                            ("[" + new Date() + "]\n" + st + "\n").getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignore) { }
                SwingUtilities.invokeLater(() -> {
                    logLine("[오류] " + st);
                    JOptionPane.showMessageDialog(this, ex.toString() + "\n\n자세한 내용은 로그 / IniEditor_error.log 확인",
                            "오류", JOptionPane.ERROR_MESSAGE);
                });
            });
        }

        private Charset charset() { return Charset.forName((String) charsetBox.getSelectedItem()); }

        private void stopEditing() {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
        }

        private boolean confirmDiscard(String msg) {
            stopEditing();
            return model.pendingCount() == 0
                    || JOptionPane.showConfirmDialog(this, msg, "확인", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
        }

        private void scanModules() {
            if (!confirmDiscard("저장 안 한 변경이 있어. 다시 검색하면 사라지는데 할까?")) return;
            Path root = Paths.get(rootField.getText().trim());
            String fileName = fileField.getText().trim();
            if (!Files.isDirectory(root)) { logLine("루트 폴더가 없음: " + root); return; }

            List<Path> dirs = new ArrayList<Path>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path d : ds) if (Files.isDirectory(d)) dirs.add(d);
            } catch (IOException ex) {
                logLine("폴더 읽기 실패: " + ex);
                return;
            }
            Collections.sort(dirs, (a, b) -> naturalCompare(a.getFileName().toString(), b.getFileName().toString()));

            Charset cs = charset();
            List<Module> ms = new ArrayList<Module>();
            Map<String, Sec> secs = new LinkedHashMap<String, Sec>();   // 섹션명(소문자) -> 섹션
            Set<String> seen = new HashSet<String>();
            int skipped = 0, keyCount = 0;
            for (Path d : dirs) {
                Path ini = d.resolve(fileName);
                if (!Files.isRegularFile(ini)) { skipped++; continue; }
                Module m = new Module(d.getFileName().toString(), ini);
                m.reload(cs);
                if (m.error != null) {
                    logLine("[" + m.name + "] 읽기 실패: " + m.error);
                } else {
                    try {
                        for (String[] e : IniDoc.load(ini, cs).entries()) {
                            String sk = e[0].toLowerCase();
                            Sec s = secs.get(sk);
                            if (s == null) { s = new Sec(e[0]); secs.put(sk, s); }
                            Col c = new Col(e[0], e[1]);
                            if (seen.add(c.id)) { s.cols.add(c); keyCount++; }
                        }
                    } catch (IOException ex) {
                        m.error = ex.toString();
                    }
                }
                ms.add(m);
            }
            model.setData(ms, new ArrayList<Sec>(secs.values()));
            setupColumns();

            picks.clear();
            applyKeys.clear();
            rebuildForms();

            logLine("검색 완료: 모듈 " + ms.size() + "개, 섹션 " + secs.size() + "개, 키 " + keyCount + "개"
                    + (skipped > 0 ? " (" + fileName + " 없는 폴더 " + skipped + "개 제외)" : ""));
            updateSummary();
        }

        private void setupColumns() {
            allColumns.clear();
            Enumeration<TableColumn> en = table.getColumnModel().getColumns();
            while (en.hasMoreElements()) allColumns.add(en.nextElement());
            for (TableColumn tc : allColumns) {
                int mi = tc.getModelIndex();
                if (mi == 0) tc.setPreferredWidth(40);
                else if (mi == 1) tc.setPreferredWidth(120);
                else tc.setPreferredWidth(Math.min(420, Math.max(160, model.secs.get(mi - 2).cols.size() * 85)));
            }
            applyFilter();
        }

        /** 섹션명이나 그 안의 키 이름에 필터 문자열이 들어간 섹션만 표시 */
        private void applyFilter() {
            stopEditing();
            String f = filterField.getText().trim().toLowerCase();
            javax.swing.table.TableColumnModel cm = table.getColumnModel();
            while (cm.getColumnCount() > 0) cm.removeColumn(cm.getColumn(0));
            for (TableColumn tc : allColumns) {
                int mi = tc.getModelIndex();
                if (mi < 2 || f.isEmpty() || matches(model.secs.get(mi - 2), f)) cm.addColumn(tc);
            }
        }

        private static boolean matches(Sec s, String f) {
            if (s.label.toLowerCase().contains(f)) return true;
            for (Col c : s.cols) if (c.key.toLowerCase().contains(f)) return true;
            return false;
        }

        /**
         * 셀 클릭. 그냥 클릭 = 그 셀만 선택, Ctrl/⌘ 클릭 = 추가/해제.
         * 같은 열(섹션)은 하나만: 이미 선택된 열의 다른 행을 누르면 그 열의 선택이 옮겨감.
         */
        private void onCellClicked(int row, int viewCol, boolean add) {
            if (row < 0 || viewCol < 0) return;
            int mc = table.convertColumnIndexToModel(viewCol);
            if (mc < 2) return;
            Sec s = model.secs.get(mc - 2);
            Integer cur = picks.get(s);
            Map<Sec, Integer> before = new HashMap<Sec, Integer>(picks);
            if (!add) {
                picks.clear();
                picks.put(s, row);
            } else if (cur != null && cur == row) {
                picks.remove(s);                       // 같은 셀 다시 → 해제
            } else {
                picks.remove(s);                       // 같은 열이면 교체 (순서는 맨 뒤로)
                picks.put(s, row);
            }
            // 선택이 바뀌거나 빠진 섹션은 '적용' 체크 초기화 (다른 모듈 폼이니까)
            for (Map.Entry<Sec, Integer> e : before.entrySet()) {
                if (!e.getValue().equals(picks.get(e.getKey()))) clearApplyKeys(e.getKey());
            }
            table.repaint();
            rebuildForms();
        }

        boolean isPicked(int row, int mc) {
            if (mc < 2) return false;
            Integer r = picks.get(model.secs.get(mc - 2));
            return r != null && r == row;
        }

        /** 선택한 셀마다 KEY = [값] 폼을 위에서부터 쌓음 */
        private void rebuildForms() {
            formPanel.removeAll();
            // 검색으로 행이 줄었으면 범위 밖 선택 제거
            Iterator<Map.Entry<Sec, Integer>> it = picks.entrySet().iterator();
            while (it.hasNext()) if (it.next().getValue() >= model.modules.size()) it.remove();

            formTitle.setText(picks.isEmpty()
                    ? "위 표에서 셀(모듈 x 섹션)을 클릭해  ·  Ctrl(맥은 ⌘)+클릭으로 여러 개 (열마다 하나씩)"
                    : "선택 " + picks.size() + "개  ·  Ctrl(맥은 ⌘)+클릭으로 추가/해제, 같은 열은 하나만");
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, 6, 0);
            int y = 0;
            for (Map.Entry<Sec, Integer> e : new ArrayList<Map.Entry<Sec, Integer>>(picks.entrySet())) {
                c.gridy = y++;
                formPanel.add(buildForm(model.modules.get(e.getValue()), e.getKey()), c);
            }
            formPanel.revalidate();
            formPanel.repaint();
        }

        private JPanel buildForm(final Module m, final Sec s) {
            JPanel box = new JPanel(new GridBagLayout());
            box.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(120, 150, 200)),
                    BorderFactory.createEmptyBorder(2, 4, 4, 4)));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3, 6, 3, 6);
            c.anchor = GridBagConstraints.WEST;

            // 헤더: 모듈 x 섹션  [닫기]
            JLabel head = new JLabel(m.name + "   " + s.label);
            head.setFont(head.getFont().deriveFont(Font.BOLD, 13f));
            JButton close = new JButton("닫기");
            close.setMargin(new Insets(1, 6, 1, 6));
            close.addActionListener(e -> { picks.remove(s); clearApplyKeys(s); table.repaint(); rebuildForms(); });
            c.gridy = 0; c.gridx = 0; c.gridwidth = 5; c.weightx = 1; box.add(head, c);
            c.gridx = 5; c.gridwidth = 1; c.weightx = 0; c.anchor = GridBagConstraints.EAST; box.add(close, c);
            c.anchor = GridBagConstraints.WEST;

            if (m.error != null) {
                JLabel err = new JLabel("읽기 실패: " + m.error);
                err.setForeground(Color.RED);
                c.gridy = 1; c.gridx = 0; c.gridwidth = 6; box.add(err, c);
                return box;
            }
            int y = 1;
            for (final Col col : s.cols) {
                final String orig = m.orig.get(col.id);
                final JTextField tf = new JTextField(28);
                String v = m.value(col.id);
                tf.setText(v == null ? "" : v);
                paintField(tf, m, col);
                final JCheckBox use = new JCheckBox();
                use.setToolTipText("체크하면 [체크한 모듈에 적용] 때 이 키 값을 넣음 (값을 고치면 자동 체크)");
                use.setSelected(applyKeys.contains(col.id));
                use.addActionListener(e -> {
                    if (use.isSelected()) applyKeys.add(col.id); else applyKeys.remove(col.id);
                });
                tf.getDocument().addDocumentListener(new DocumentListener() {
                    void changed() {
                        m.setCell(col.id, tf.getText());
                        paintField(tf, m, col);
                        if (m.edits.containsKey(col.id)) { applyKeys.add(col.id); use.setSelected(true); }
                        int r = model.modules.indexOf(m);
                        model.fireTableRowsUpdated(r, r);
                        updateSummary();
                    }
                    public void insertUpdate(DocumentEvent e) { changed(); }
                    public void removeUpdate(DocumentEvent e) { changed(); }
                    public void changedUpdate(DocumentEvent e) { changed(); }
                });

                JLabel key = new JLabel(col.key);
                key.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
                JLabel note = new JLabel(orig == null ? "(이 모듈엔 없는 키 · 입력하면 추가)" : " ");
                note.setForeground(Color.GRAY);
                JButton undo = new JButton("되돌리기");
                undo.setMargin(new Insets(1, 6, 1, 6));
                undo.addActionListener(e -> {
                    tf.setText(orig == null ? "" : orig);
                    applyKeys.remove(col.id);
                    use.setSelected(false);
                });

                c.gridy = y++; c.gridwidth = 1;
                c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; box.add(use, c);
                c.gridx = 1; box.add(key, c);
                c.gridx = 2; box.add(new JLabel("="), c);
                c.gridx = 3; c.fill = GridBagConstraints.HORIZONTAL; box.add(tf, c);
                c.gridx = 4; c.fill = GridBagConstraints.NONE; box.add(undo, c);
                c.gridx = 5; c.weightx = 1; box.add(note, c);
            }
            return box;
        }

        /** 파일 값과 다르면 노란색, 툴팁에 원래 값 */
        private void paintField(JTextField tf, Module m, Col col) {
            boolean edited = m.edits.containsKey(col.id);
            tf.setBackground(edited ? EDITED : Color.WHITE);
            String o = m.orig.get(col.id);
            tf.setToolTipText(edited ? "파일 원래 값: " + (o == null ? "(없음)" : o) : null);
        }

        /** 다른 데서 값이 바뀌었을 때(저장/취소/일괄적용) 표 + 폼 다시 그림. 선택은 유지 */
        private void refreshAll() {
            if (model.getRowCount() > 0) model.fireTableRowsUpdated(0, model.getRowCount() - 1);
            rebuildForms();
            updateSummary();
        }

        private void setAllSelected(boolean on) {
            for (Module m : model.modules) m.selected = on;
            refreshAll();
        }

        /**
         * 선택한 셀(폼)들에서 '적용' 체크된 키(applyKeys)의 현재 값을, 위 표에서 '적용' 체크된 모든 모듈에 넣음.
         * 저장 후에도 체크가 남아 있어서 1,2번 저장 → 3~6번 체크 → 다시 적용 가능.
         * 체크 안 된 키는 손대지 않음 (모듈마다 다른 내부 포트 등 보호). 저장은 따로.
         */
        private void applyEditsToChecked() {
            stopEditing();
            List<Col> cols = new ArrayList<Col>();
            List<String> vals = new ArrayList<String>();
            for (Map.Entry<Sec, Integer> e : picks.entrySet()) {
                Module src = model.modules.get(e.getValue());
                for (Col c : e.getKey().cols) {
                    String v = src.value(c.id);
                    if (applyKeys.contains(c.id) && v != null) { cols.add(c); vals.add(v); }
                }
            }
            if (cols.isEmpty()) {
                JOptionPane.showMessageDialog(this, "적용할 키가 없어.\n아래 폼에서 값을 고치거나, 퍼뜨릴 키 줄 왼쪽 체크박스를 체크해.");
                return;
            }
            int n = 0;
            for (Module m : model.modules) {
                if (!m.selected || m.error != null) continue;
                for (int i = 0; i < cols.size(); i++) m.setCell(cols.get(i).id, vals.get(i));
                n++;
            }
            refreshAll();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                Col c = cols.get(i);
                sb.append(' ').append(c.label).append('=').append(vals.get(i)).append(';');
            }
            logLine("일괄 적용 → 체크한 " + n + "개 모듈:" + sb + " (아직 저장 전)");
        }

        private void clearApplyKeys(Sec s) {
            for (Col c : s.cols) applyKeys.remove(c.id);
        }

        private void revertAll() {
            stopEditing();
            for (Module m : model.modules) m.edits.clear();
            refreshAll();
            logLine("변경 취소됨");
        }

        private void saveAll() {
            stopEditing();
            Map<String, Col> colMap = new HashMap<String, Col>();
            for (Col c : model.allCols()) colMap.put(c.id, c);

            List<Module> targets = new ArrayList<Module>();
            for (Module m : model.modules) {
                if (m.edits.isEmpty()) continue;
                for (Map.Entry<String, String> e : m.edits.entrySet()) {
                    Col c = colMap.get(e.getKey());
                    if (isPortKey(c.key) && !e.getValue().trim().matches("\\d{1,5}")) {
                        JOptionPane.showMessageDialog(this, "[" + m.name + "] " + c.label + " 값이 숫자가 아님: " + e.getValue());
                        return;
                    }
                }
                targets.add(m);
            }
            if (targets.isEmpty()) { JOptionPane.showMessageDialog(this, "변경된 값이 없어."); return; }

            if (JOptionPane.showConfirmDialog(this,
                    targets.size() + "개 모듈, " + model.pendingCount() + "개 값을 저장할까?\n각 폴더에 백업이 먼저 만들어져.",
                    "저장 확인", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;

            String ts = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            Charset cs = charset();
            int ok = 0, fail = 0;
            logLine("---- 저장 시작 (" + ts + ") ----");
            for (Module m : targets) {
                try {
                    IniDoc doc = IniDoc.load(m.ini, cs);
                    StringBuilder detail = new StringBuilder();
                    for (Map.Entry<String, String> e : m.edits.entrySet()) {
                        Col c = colMap.get(e.getKey());
                        doc.set(c.section, c.key, e.getValue());
                        detail.append(' ').append(c.label).append('=').append(e.getValue()).append(';');
                    }
                    Files.copy(m.ini, m.ini.resolveSibling(m.ini.getFileName() + ".bak_" + ts), StandardCopyOption.COPY_ATTRIBUTES);
                    doc.save(m.ini);
                    m.edits.clear();
                    m.reload(cs);
                    ok++;
                    logLine("[" + m.name + "] 저장:" + detail);
                } catch (Exception ex) {
                    fail++;
                    logLine("[" + m.name + "] 실패: " + ex);
                }
            }
            refreshAll();
            logLine("---- 완료: 성공 " + ok + " / 실패 " + fail + " ----");
            if (fail > 0) {
                JOptionPane.showMessageDialog(this, fail + "개 모듈 실패. 실패한 모듈은 변경값이 남아있으니 원인 해결 후 다시 저장해.\n(실행 중인 모듈이 파일을 잡고 있을 수 있어)",
                        "일부 실패", JOptionPane.WARNING_MESSAGE);
            }
        }

        private void updateSummary() {
            summary.setText("   모듈 " + model.modules.size() + "개 · 섹션 " + model.secs.size() + "개 · 저장 대기 " + model.pendingCount() + "건");
        }

        private void logLine(String s) {
            log.append(s + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        }

        private static String html(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        // ---- 위 표 셀: 섹션 요약 (pw 가림, 수정 있으면 노란색, 툴팁에 줄바꿈 목록) ----
        class SummaryRenderer extends DefaultTableCellRenderer {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
                int mc = t.convertColumnIndexToModel(col);
                Module m = model.modules.get(row);
                String text; Color fg = null; String tip = null;
                if (mc >= 2) {
                    Sec s = model.secs.get(mc - 2);
                    if (m.error != null) { text = "읽기실패"; fg = Color.RED; }
                    else {
                        text = ModuleTableModel.summary(m, s, ", ");
                        if (text.isEmpty()) { text = "-"; fg = Color.GRAY; }
                        tip = "<html><b>" + html(m.name + " " + s.label) + "</b><br>"
                                + html(ModuleTableModel.summary(m, s, "\n")).replace("\n", "<br>") + "</html>";
                    }
                } else text = value == null ? "" : value.toString();
                boolean picked = isPicked(row, mc);
                super.getTableCellRendererComponent(t, text, false, false, row, col);
                setBackground(model.isEdited(row, mc) ? EDITED : picked ? PICKED : t.getBackground());
                setForeground(fg != null ? fg : t.getForeground());
                setBorder(picked ? BorderFactory.createLineBorder(PICKED_BORDER, 2) : BorderFactory.createEmptyBorder(1, 2, 1, 2));
                setToolTipText(tip);
                return this;
            }
        }

    }

}
