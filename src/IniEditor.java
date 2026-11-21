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
            lines.add(insertPoint(r), key + "=" + value);
        }

        /** 섹션 끝(뒤쪽 빈 줄 앞) 위치 */
        private int insertPoint(int[] r) {
            for (int j = r[1] - 1; j > r[0]; j--) {
                if (!isBlank(lines.get(j))) return j + 1;
            }
            return r[0] + 1;
        }

        IniDoc copy() {
            IniDoc d = new IniDoc();
            d.lines.addAll(lines);
            d.lineSep = lineSep; d.endsWithNewline = endsWithNewline; d.utf8Bom = utf8Bom; d.charset = charset;
            return d;
        }

        /** 섹션 헤더({섹션, null})와 키({섹션, 키})를 등장 순서대로. 키 없는 빈 섹션도 나옴 */
        List<String[]> structure() {
            List<String[]> out = new ArrayList<String[]>();
            String cur = "";
            for (String line : lines) {
                Matcher s = SECTION.matcher(line);
                if (s.matches()) { cur = s.group(1).trim(); out.add(new String[]{cur, null}); continue; }
                Matcher e = ENTRY.matcher(line);
                if (e.matches()) out.add(new String[]{cur, e.group(2).trim()});
            }
            return out;
        }

        boolean hasSection(String section) { return sectionRange(section) != null; }

        boolean hasKey(String section, String key) {
            int[] r = sectionRange(section);
            return r != null && findKey(r, key) >= 0;
        }

        /** 파일 끝에 [섹션] 추가. 이미 있으면 false. 섹션 사이에 빈 줄을 두는 파일이면 빈 줄도 하나 넣음 */
        boolean addSection(String section) {
            if (section.isEmpty() || sectionRange(section) != null) return false;
            boolean blankStyle = false;
            for (int i = 1; i < lines.size(); i++) {
                if (SECTION.matcher(lines.get(i)).matches() && isBlank(lines.get(i - 1))) { blankStyle = true; break; }
            }
            if (blankStyle && !lines.isEmpty() && !isBlank(lines.get(lines.size() - 1))) lines.add("");
            lines.add("[" + section + "]");
            return true;
        }

        /** 섹션 헤더부터 다음 섹션 직전까지 삭제. 없으면 false. 전역("")은 지울 수 없음 */
        boolean removeSection(String section) {
            if (section.isEmpty()) throw new IllegalArgumentException("전역 영역은 섹션 삭제 대상이 아님");
            int[] r = sectionRange(section);
            if (r == null) return false;
            lines.subList(r[0], r[1]).clear();
            return true;
        }

        /** 섹션 끝(빈 줄 앞)에 키 추가. 섹션이 없거나 키가 이미 있으면 false. 들여쓰기/= 공백은 같은 섹션 마지막 키를 따라감 */
        boolean addKey(String section, String key, String value) {
            int[] r = sectionRange(section);
            if (r == null || findKey(r, key) >= 0) return false;
            String indent = "", eq = "=";
            for (int i = r[1] - 1; i > r[0]; i--) {
                Matcher m = ENTRY.matcher(lines.get(i));
                if (m.matches()) { indent = m.group(1); eq = m.group(3); break; }
            }
            lines.add(insertPoint(r), indent + key + eq + value);
            return true;
        }

        /** 키 줄 하나만 삭제 (중복이면 첫 번째). 없으면 false */
        boolean removeKey(String section, String key) {
            int[] r = sectionRange(section);
            if (r == null) return false;
            int i = findKey(r, key);
            if (i < 0) return false;
            lines.remove(i);
            return true;
        }

        /**
         * 키 이름만 바꿈. 값, 들여쓰기, "key = value" 공백, 줄 위치는 그대로.
         * 없으면 false, 같은 섹션에 새 이름이 이미 있으면 IllegalStateException (대소문자만 바꾸는 건 허용)
         */
        boolean renameKey(String section, String oldKey, String newKey) {
            int[] r = sectionRange(section);
            if (r == null) return false;
            int i = findKey(r, oldKey);
            if (i < 0) return false;
            int j = findKey(r, newKey);
            if (j >= 0 && j != i) throw new IllegalStateException("같은 섹션에 이미 있는 키: " + newKey);
            Matcher m = ENTRY.matcher(lines.get(i));
            if (!m.matches()) return false;
            lines.set(i, m.group(1) + newKey + m.group(3) + m.group(4));
            return true;
        }
    }

    // ---------------- 구조 변경 (저장 대기) ----------------
    /** 섹션/키 구조 변경 하나. 저장 때 파일을 새로 읽어서 순서대로 다시 적용 */
    static class Op {
        static final int ADD_SEC = 1, DEL_SEC = 2, ADD_KEY = 3, DEL_KEY = 4, REN_KEY = 5;
        final int kind;
        final String section, key, arg;   // arg: ADD_KEY 의 값, REN_KEY 의 새 이름
        Op(int kind, String section, String key, String arg) {
            this.kind = kind; this.section = section; this.key = key; this.arg = arg;
        }
        boolean apply(IniDoc d) {
            switch (kind) {
                case ADD_SEC: return d.addSection(section);
                case DEL_SEC: return d.removeSection(section);
                case ADD_KEY: return d.addKey(section, key, arg);
                case DEL_KEY: return d.removeKey(section, key);
                case REN_KEY: return d.renameKey(section, key, arg);
                default: throw new IllegalStateException("알 수 없는 작업: " + kind);
            }
        }
        String describe() {
            String sec = secLabel(section);
            switch (kind) {
                case ADD_SEC: return "섹션 추가 " + sec;
                case DEL_SEC: return "섹션 삭제 " + sec;
                case ADD_KEY: return "키 추가 " + sec + " " + key + "=" + arg;
                case DEL_KEY: return "키 삭제 " + sec + " " + key;
                case REN_KEY: return "키 이름 변경 " + sec + " " + key + " → " + arg;
                default: return "?";
            }
        }
    }

    static String sectionNameError(String name) {
        if (isBlank(name)) return "섹션 이름이 비어 있어";
        if (name.contains("[") || name.contains("]") || name.contains("\n") || name.contains("\r")) return "섹션 이름에 [ ] 나 줄바꿈은 못 써";
        return null;
    }

    static String keyNameError(String key) {
        if (isBlank(key)) return "키 이름이 비어 있어";
        if (!key.equals(key.trim())) return "키 이름 앞뒤에 공백은 못 써";
        if (key.contains("=") || key.contains("\n") || key.contains("\r")) return "키 이름에 = 나 줄바꿈은 못 써";
        char f = key.charAt(0);
        if (f == ';' || f == '#' || f == '[') return "키 이름은 ; # [ 로 시작할 수 없어";
        return null;
    }

    // ---------------- 증가 값 (패턴 %d / %0Nd) ----------------
    private static final Pattern INC_SPEC = Pattern.compile("%(\\d*)d");
    private static final Pattern IP_LIKE = Pattern.compile("^-?\\d+\\.-?\\d+\\.-?\\d+\\.-?\\d+$");

    /** printf 비슷한 패턴. %d 또는 %0Nd 가 정확히 하나, 나머지는 고정 문자열 */
    static class IncPattern {
        final String prefix, suffix;
        final int width;   // 0 = 자리수 맞춤 없음
        private IncPattern(String prefix, String suffix, int width) { this.prefix = prefix; this.suffix = suffix; this.width = width; }

        static IncPattern parse(String p) {
            if (p == null) p = "";
            Matcher m = INC_SPEC.matcher(p);
            int count = 0, start = -1, end = -1;
            String spec = null;
            while (m.find()) {
                count++;
                if (count == 1) { start = m.start(); end = m.end(); spec = m.group(1); }
            }
            if (count == 0) throw new IllegalArgumentException("패턴에 %d 가 없어");
            if (count > 1) throw new IllegalArgumentException("%d 는 하나만 쓸 수 있어");
            int width = 0;
            if (!spec.isEmpty()) {
                if (!spec.matches("0[1-9]\\d?")) throw new IllegalArgumentException("%d 또는 %0Nd (예: %03d) 형식만 돼");
                width = Integer.parseInt(spec.substring(1));
            }
            return new IncPattern(p.substring(0, start), p.substring(end), width);
        }

        String format(long n) {
            String num = width > 0 ? String.format(Locale.ROOT, "%0" + width + "d", n) : Long.toString(n);
            return prefix + num + suffix;
        }
    }

    /** 현재 값에서 기본 패턴 뽑기: 마지막 숫자를 %d(앞자리 0 이면 %0Nd)로. {패턴, 시작값} */
    static String[] defaultIncPattern(String value) {
        if (value == null) value = "";
        Matcher m = Pattern.compile("\\d+").matcher(value);
        int s = -1, e = -1;
        while (m.find()) { s = m.start(); e = m.end(); }
        if (s >= 0) {
            String digits = value.substring(s, e);
            try {
                long n = Long.parseLong(digits);
                String spec = digits.length() > 1 && digits.charAt(0) == '0' && digits.length() <= 99 ? "%0" + digits.length() + "d" : "%d";
                return new String[]{value.substring(0, s) + spec + value.substring(e), Long.toString(n)};
            } catch (NumberFormatException ignore) { /* 너무 긴 숫자는 아래로 */ }
        }
        return new String[]{value + "%d", "1"};
    }

    /** 증가 설정 입력 오류. 문제 없으면 null */
    static String incSettingError(String pattern, String start, String step) {
        try { IncPattern.parse(pattern); } catch (IllegalArgumentException ex) { return ex.getMessage(); }
        try { Long.parseLong(start.trim()); } catch (Exception ex) { return "시작값은 정수여야 해"; }
        try { Long.parseLong(step.trim()); } catch (Exception ex) { return "증가폭은 정수여야 해 (음수 가능)"; }
        return null;
    }

    /** 패턴에 시작값, 시작값+증가폭 … 을 넣은 count 개 */
    static List<String> incSequence(String pattern, long start, long step, int count) {
        IncPattern p = IncPattern.parse(pattern);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < count; i++) out.add(p.format(start + step * i));
        return out;
    }

    /** a.b.c.d 형식인데 0~255 를 벗어난 자리가 있으면 오류 메시지, 아니면 null */
    static String ipRangeError(String s) {
        if (s == null || !IP_LIKE.matcher(s.trim()).matches()) return null;
        for (String part : s.trim().split("\\.")) {
            long v;
            try { v = Long.parseLong(part); } catch (NumberFormatException ex) { return "IP 자리값이 너무 큼: " + s; }
            if (v < 0 || v > 255) return "IP 자리값이 0~255 를 벗어남: " + s;
        }
        return null;
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
        IniDoc work;                                                       // 파일 + 구조 변경(ops)을 적용한 메모리 사본
        final Map<String, String> orig = new HashMap<String, String>();   // colId -> 파일의 현재 값
        final Map<String, String> base = new HashMap<String, String>();   // colId -> 구조 변경 적용 후 값 (편집 기준)
        final Map<String, String> edits = new HashMap<String, String>();  // colId -> 바꿀 값
        final List<Op> ops = new ArrayList<Op>();                          // 저장 대기 구조 변경 (순서대로)
        final Set<String> touchedSecs = new HashSet<String>();            // 구조 변경이 있었던 섹션 (소문자)
        Module(String name, Path ini) { this.name = name; this.ini = ini; }

        void reload(Charset cs) {
            orig.clear();
            base.clear();
            error = null;
            work = null;
            try {
                IniDoc doc = IniDoc.load(ini, cs);
                fill(orig, doc);
                IniDoc w = doc.copy();
                for (Op op : ops) op.apply(w);
                work = w;
                fill(base, work);
            } catch (Exception ex) {
                error = ex.toString();
            }
        }

        private static void fill(Map<String, String> map, IniDoc doc) {
            for (String[] e : doc.entries()) {
                String id = colId(e[0], e[1]);
                if (!map.containsKey(id)) map.put(id, doc.get(e[0], e[1]));  // 중복 키는 첫 번째 값
            }
        }

        /** 구조 변경을 메모리 사본에 적용하고 저장 대기에 쌓음. 적용 안 되면(이미 있음/없음) false */
        boolean applyOp(Op op) {
            if (work == null || !op.apply(work)) return false;
            ops.add(op);
            touchedSecs.add(op.section.toLowerCase());
            String secPrefix = op.section.trim().toLowerCase() + "\u0000";
            switch (op.kind) {
                case Op.DEL_SEC:
                    for (Iterator<String> it = edits.keySet().iterator(); it.hasNext(); ) if (it.next().startsWith(secPrefix)) it.remove();
                    break;
                case Op.DEL_KEY:
                case Op.ADD_KEY:
                    edits.remove(colId(op.section, op.key));
                    break;
                case Op.REN_KEY:
                    String from = colId(op.section, op.key), to = colId(op.section, op.arg);
                    if (!from.equals(to) && edits.containsKey(from)) edits.put(to, edits.remove(from));
                    break;
                default:
            }
            base.clear();
            fill(base, work);
            for (Iterator<Map.Entry<String, String>> it = edits.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, String> e = it.next();
                if (same(base.get(e.getKey()), e.getValue())) it.remove();
            }
            return true;
        }

        void discardPending(Charset cs) {
            ops.clear();
            edits.clear();
            touchedSecs.clear();
            reload(cs);
        }

        String value(String id) { return edits.containsKey(id) ? edits.get(id) : base.get(id); }

        private static boolean same(String o, String v) { return (o == null) ? v.isEmpty() : v.equals(o); }

        void setCell(String id, String v) {
            if (same(base.get(id), v)) edits.remove(id); else edits.put(id, v);
        }

        /** 파일 값과 다름 (값 수정 또는 구조 변경 때문에) */
        boolean isChanged(String id) {
            if (edits.containsKey(id)) return true;
            String b = base.get(id), o = orig.get(id);
            return b == null ? o != null : !b.equals(o);
        }

        boolean hasEditIn(Sec s) {
            if (touchedSecs.contains(s.name.toLowerCase())) return true;
            for (Col c : s.cols) if (isChanged(c.id)) return true;
            return false;
        }

        int pendingCount() { return edits.size() + ops.size(); }
    }

    /**
     * 모듈들의 (구조 변경 적용 후) 섹션/키 합집합. 섹션·키 모두 처음 나온 순서, 키 없는 빈 섹션도 포함.
     * prevCols: 이전 컬럼 (어느 모듈에도 없지만 값 입력이 대기 중인 키를 살려 두려고)
     */
    static List<Sec> buildSecs(List<Module> ms, Map<String, Col> prevCols) {
        Map<String, Sec> secs = new LinkedHashMap<String, Sec>();
        Set<String> seen = new HashSet<String>();
        for (Module m : ms) {
            if (m.work == null) continue;
            for (String[] e : m.work.structure()) {
                String sk = e[0].toLowerCase();
                Sec s = secs.get(sk);
                if (s == null) { s = new Sec(e[0]); secs.put(sk, s); }
                if (e[1] != null) {
                    Col c = new Col(e[0], e[1]);
                    if (seen.add(c.id)) s.cols.add(c);
                }
            }
        }
        for (Module m : ms) {
            for (String id : m.edits.keySet()) {
                Col c = prevCols.get(id);
                if (c == null || !seen.add(id)) continue;
                String sk = c.section.toLowerCase();
                Sec s = secs.get(sk);
                if (s == null) { s = new Sec(c.section); secs.put(sk, s); }
                s.cols.add(c);
            }
        }
        return new ArrayList<Sec>(secs.values());
    }

    /** 구조 변경 뒤에도 기존 컬럼(섹션) 순서는 유지, 새 섹션은 뒤에 */
    static List<Sec> keepOrder(List<Sec> fresh, List<Sec> prevOrder) {
        final Map<String, Integer> idx = new HashMap<String, Integer>();
        for (int i = 0; i < prevOrder.size(); i++) idx.put(prevOrder.get(i).name.toLowerCase(), i);
        final Map<Sec, Integer> appear = new HashMap<Sec, Integer>();
        for (int i = 0; i < fresh.size(); i++) appear.put(fresh.get(i), i);
        List<Sec> out = new ArrayList<Sec>(fresh);
        Collections.sort(out, new Comparator<Sec>() {
            public int compare(Sec a, Sec b) {
                Integer ia = idx.get(a.name.toLowerCase()), ib = idx.get(b.name.toLowerCase());
                if (ia != null && ib != null) return ia - ib;
                if (ia != null) return -1;
                if (ib != null) return 1;
                return appear.get(a) - appear.get(b);
            }
        });
        return out;
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
            for (Module m : modules) n += m.pendingCount();
            return n;
        }
    }

    // ---------------- GUI ----------------
    static class EditorFrame extends JFrame {
        private static final Color EDITED = new Color(255, 240, 150);
        private static final Color PICKED = new Color(200, 220, 255);
        private static final Color PICKED_BORDER = new Color(40, 100, 220);
        private static final Color ERROR_BG = new Color(255, 200, 200);

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
        /** 키(colId)별 증가 설정. applyKeys 처럼 저장 후에도 유지 */
        private final Map<String, IncSetting> incs = new HashMap<String, IncSetting>();
        private final JTextArea log = new JTextArea(6, 80);

        static class IncSetting {
            boolean on;
            String pattern = "", start = "", step = "1";
        }

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
            JButton addSec = new JButton("섹션 추가");
            addSec.setToolTipText("'적용' 체크된 모듈들의 파일 끝에 [섹션] 추가 (저장 대기)");
            addSec.addActionListener(e -> addSection());
            tableBar.add(selAll); tableBar.add(selNone); tableBar.add(addSec);
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
            int skipped = 0;
            for (Path d : dirs) {
                Path ini = d.resolve(fileName);
                if (!Files.isRegularFile(ini)) { skipped++; continue; }
                Module m = new Module(d.getFileName().toString(), ini);
                m.reload(cs);
                if (m.error != null) logLine("[" + m.name + "] 읽기 실패: " + m.error);
                ms.add(m);
            }
            List<Sec> secs = buildSecs(ms, new HashMap<String, Col>());
            int keyCount = 0;
            for (Sec s : secs) keyCount += s.cols.size();
            model.setData(ms, secs);
            setupColumns();

            picks.clear();
            applyKeys.clear();
            incs.clear();
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

        private static JButton small(String text) {
            JButton b = new JButton(text);
            b.setMargin(new Insets(1, 6, 1, 6));
            return b;
        }

        private JPanel buildForm(final Module m, final Sec s) {
            final JPanel box = new JPanel(new GridBagLayout());
            box.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(120, 150, 200)),
                    BorderFactory.createEmptyBorder(2, 4, 4, 4)));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3, 6, 3, 6);
            c.anchor = GridBagConstraints.WEST;

            // 헤더: 모듈 x 섹션  [섹션 삭제] [닫기]
            JLabel head = new JLabel(m.name + "   " + s.label);
            head.setFont(head.getFont().deriveFont(Font.BOLD, 13f));
            JButton delSec = small("섹션 삭제");
            if (s.name.isEmpty()) { delSec.setEnabled(false); delSec.setToolTipText("(전역)은 섹션이 아니라서 삭제할 수 없어"); }
            else delSec.setToolTipText("'적용' 체크된 모듈들에서 이 섹션(헤더부터 다음 섹션 직전까지)을 삭제 (저장 대기)");
            delSec.addActionListener(e -> deleteSection(s));
            JButton close = small("닫기");
            close.addActionListener(e -> { picks.remove(s); clearApplyKeys(s); table.repaint(); rebuildForms(); });
            JPanel headRow = new JPanel(new BorderLayout());
            headRow.setOpaque(false);
            headRow.add(head, BorderLayout.CENTER);
            JPanel headBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            headBtns.setOpaque(false);
            headBtns.add(delSec); headBtns.add(close);
            headRow.add(headBtns, BorderLayout.EAST);
            c.gridy = 0; c.gridx = 0; c.gridwidth = GridBagConstraints.REMAINDER; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            box.add(headRow, c);
            c.gridwidth = 1; c.fill = GridBagConstraints.NONE;

            if (m.error != null) {
                JLabel err = new JLabel("읽기 실패: " + m.error);
                err.setForeground(Color.RED);
                c.gridy = 1; c.gridx = 0; c.gridwidth = GridBagConstraints.REMAINDER; box.add(err, c);
                return box;
            }
            int y = 1;
            for (final Col col : s.cols) {
                final String base = m.base.get(col.id);
                final JTextField tf = new JTextField(24);
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
                JLabel note = new JLabel(base == null ? "(이 모듈엔 없는 키 · 입력하면 추가)" : " ");
                note.setForeground(Color.GRAY);
                JButton undo = small("되돌리기");
                undo.addActionListener(e -> {
                    tf.setText(base == null ? "" : base);
                    applyKeys.remove(col.id);
                    use.setSelected(false);
                });
                JButton ren = small("이름 변경");
                ren.setToolTipText("'적용' 체크된 모듈들에서 이 키 이름만 변경 (값, 공백 형식, 위치 유지 · 저장 대기)");
                ren.addActionListener(e -> renameKey(s, col));
                JButton del = small("삭제");
                del.setToolTipText("'적용' 체크된 모듈들에서 이 키 줄만 삭제 (저장 대기)");
                del.addActionListener(e -> deleteKey(s, col));

                // 증가: 체크하면 패턴/시작값/증가폭 입력칸이 나타남
                final IncSetting st = incs.containsKey(col.id) ? incs.get(col.id) : new IncSetting();
                final JCheckBox incOn = new JCheckBox("증가", st.on);
                incOn.setToolTipText("체크한 모듈마다 패턴의 %d 자리에 시작값, 시작값+증가폭 … 을 넣음");
                final JTextField pf = new JTextField(st.pattern, 14);
                final JTextField sf = new JTextField(st.start, 5);
                final JTextField stepf = new JTextField(st.step, 3);
                final JLabel incMsg = new JLabel(" ");
                pf.setToolTipText("%d 또는 %0Nd 하나 (예: 10.150.12.%d, temp_value%03d)");
                final JPanel incBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
                incBox.setOpaque(false);
                incBox.add(new JLabel("패턴")); incBox.add(pf);
                incBox.add(new JLabel("시작")); incBox.add(sf);
                incBox.add(new JLabel("증가폭")); incBox.add(stepf);
                incBox.add(incMsg);
                incBox.setVisible(st.on);
                final Runnable syncInc = () -> {
                    st.pattern = pf.getText(); st.start = sf.getText(); st.step = stepf.getText();
                    String err = incSettingError(st.pattern, st.start, st.step);
                    pf.setBackground(Color.WHITE); sf.setBackground(Color.WHITE); stepf.setBackground(Color.WHITE);
                    if (err != null) {
                        JTextField bad = err.startsWith("시작") ? sf : err.startsWith("증가폭") ? stepf : pf;
                        bad.setBackground(ERROR_BG);
                        incMsg.setForeground(Color.RED);
                        incMsg.setText("입력 오류: " + err);
                    } else {
                        List<String> ex = incSequence(st.pattern, Long.parseLong(st.start.trim()), Long.parseLong(st.step.trim()), 2);
                        incMsg.setForeground(Color.GRAY);
                        incMsg.setText("→ " + ex.get(0) + ", " + ex.get(1) + " …");
                    }
                };
                DocumentListener incListener = new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) { syncInc.run(); }
                    public void removeUpdate(DocumentEvent e) { syncInc.run(); }
                    public void changedUpdate(DocumentEvent e) { syncInc.run(); }
                };
                if (st.on) syncInc.run();
                pf.getDocument().addDocumentListener(incListener);
                sf.getDocument().addDocumentListener(incListener);
                stepf.getDocument().addDocumentListener(incListener);
                incOn.addActionListener(e -> {
                    st.on = incOn.isSelected();
                    incs.put(col.id, st);
                    if (st.on) {
                        if (isBlank(st.pattern)) {
                            String[] d = defaultIncPattern(tf.getText());
                            st.step = isBlank(st.step) ? "1" : st.step;
                            pf.setText(d[0]); sf.setText(d[1]); stepf.setText(st.step);
                        }
                        syncInc.run();
                        applyKeys.add(col.id);
                        use.setSelected(true);
                    }
                    incBox.setVisible(st.on);
                    box.revalidate(); box.repaint();
                });

                c.gridy = y++; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
                c.gridx = 0; box.add(use, c);
                c.gridx = 1; box.add(key, c);
                c.gridx = 2; box.add(new JLabel("="), c);
                c.gridx = 3; c.fill = GridBagConstraints.HORIZONTAL; box.add(tf, c);
                c.fill = GridBagConstraints.NONE;
                c.gridx = 4; box.add(undo, c);
                c.gridx = 5; box.add(ren, c);
                c.gridx = 6; box.add(del, c);
                c.gridx = 7; box.add(incOn, c);
                c.gridx = 8; box.add(incBox, c);
                c.gridx = 9; c.weightx = 1; box.add(note, c);
            }

            // 하단: 키 추가
            final JTextField newKey = new JTextField(12);
            final JTextField newVal = new JTextField(18);
            JButton addKey = small("키 추가");
            addKey.setToolTipText("'적용' 체크된 모듈들의 이 섹션 끝(빈 줄 앞)에 추가 (저장 대기)");
            addKey.addActionListener(e -> addKeyTo(s, newKey.getText(), newVal.getText()));
            JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            addRow.setOpaque(false);
            addRow.add(new JLabel("새 키")); addRow.add(newKey);
            addRow.add(new JLabel("=")); addRow.add(newVal);
            addRow.add(addKey);
            c.gridy = y; c.gridx = 0; c.gridwidth = GridBagConstraints.REMAINDER; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(8, 6, 3, 6);
            box.add(addRow, c);
            return box;
        }

        /** 파일 값과 다르면 노란색, 툴팁에 원래 값 */
        private void paintField(JTextField tf, Module m, Col col) {
            boolean edited = m.isChanged(col.id);
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
         * 선택한 셀(폼)들에서 '적용' 체크된 키(applyKeys)의 값을, 위 표에서 '적용' 체크된 모든 모듈에 넣음.
         * 증가가 켜진 키는 모듈마다 위 표 순서대로 시작값, 시작값+증가폭 … 을 패턴에 넣은 값 (미리보기 확인 후).
         * 저장 후에도 체크/증가 설정이 남아 있어서 1,2번 저장 → 3~6번 체크 → 다시 적용 가능.
         * 체크 안 된 키는 손대지 않음 (모듈마다 다른 내부 포트 등 보호). 저장은 따로.
         */
        private void applyEditsToChecked() {
            stopEditing();
            List<Col> cols = new ArrayList<Col>();
            List<String> fixed = new ArrayList<String>();          // 증가 아닌 키의 값 (증가 키는 null)
            List<IncPattern> pats = new ArrayList<IncPattern>();   // 증가 키의 패턴 (아니면 null)
            List<long[]> startStep = new ArrayList<long[]>();
            List<String> errs = new ArrayList<String>();
            for (Map.Entry<Sec, Integer> e : picks.entrySet()) {
                Module src = model.modules.get(e.getValue());
                for (Col c : e.getKey().cols) {
                    if (!applyKeys.contains(c.id)) continue;
                    IncSetting st = incs.get(c.id);
                    if (st != null && st.on) {
                        String err = incSettingError(st.pattern, st.start, st.step);
                        if (err != null) { errs.add(c.label + " : " + err); continue; }
                        cols.add(c); fixed.add(null); pats.add(IncPattern.parse(st.pattern));
                        startStep.add(new long[]{Long.parseLong(st.start.trim()), Long.parseLong(st.step.trim())});
                    } else {
                        String v = src.value(c.id);
                        if (v == null) continue;
                        cols.add(c); fixed.add(v); pats.add(null); startStep.add(null);
                    }
                }
            }
            if (!errs.isEmpty()) {
                JOptionPane.showMessageDialog(this, "증가 설정에 입력 오류가 있어.\n\n" + join(errs, 15), "입력 오류", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (cols.isEmpty()) {
                JOptionPane.showMessageDialog(this, "적용할 키가 없어.\n아래 폼에서 값을 고치거나, 퍼뜨릴 키 줄 왼쪽 체크박스를 체크해.");
                return;
            }
            List<Module> targets = checkedTargets();
            if (targets.isEmpty()) { JOptionPane.showMessageDialog(this, "위 표에서 '적용' 체크된 모듈이 없어."); return; }

            String[][] nv = new String[targets.size()][cols.size()];
            boolean anyInc = false;
            List<String> ipErrs = new ArrayList<String>();
            for (int i = 0; i < targets.size(); i++) {
                for (int j = 0; j < cols.size(); j++) {
                    if (pats.get(j) == null) { nv[i][j] = fixed.get(j); continue; }
                    anyInc = true;
                    long[] ss = startStep.get(j);
                    nv[i][j] = pats.get(j).format(ss[0] + ss[1] * i);
                    String ipErr = ipRangeError(nv[i][j]);
                    if (ipErr != null) ipErrs.add(targets.get(i).name + " " + cols.get(j).label + " : " + ipErr);
                }
            }
            if (!ipErrs.isEmpty()) {
                JOptionPane.showMessageDialog(this, "IP 형식 값이 범위를 벗어나서 적용하지 않았어.\n시작값/증가폭을 확인해.\n\n" + join(ipErrs, 15),
                        "IP 범위 오류", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (anyInc && !confirmPreview(targets, cols, nv)) return;

            for (int i = 0; i < targets.size(); i++) {
                for (int j = 0; j < cols.size(); j++) targets.get(i).setCell(cols.get(j).id, nv[i][j]);
            }
            refreshAll();
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < cols.size(); j++) {
                Col c = cols.get(j);
                sb.append(' ').append(c.label).append('=');
                if (pats.get(j) == null) sb.append(fixed.get(j));
                else sb.append(nv[0][j]).append(" ~ ").append(nv[targets.size() - 1][j]).append("(증가)");
                sb.append(';');
            }
            logLine("일괄 적용 → 체크한 " + targets.size() + "개 모듈:" + sb + " (아직 저장 전)");
        }

        /** 모듈 / 키 / 기존 값 / 새 값 미리보기 → 확인 */
        private boolean confirmPreview(List<Module> targets, List<Col> cols, String[][] nv) {
            List<Object[]> rows = new ArrayList<Object[]>();
            for (int i = 0; i < targets.size(); i++) {
                Module m = targets.get(i);
                for (int j = 0; j < cols.size(); j++) {
                    String old = m.value(cols.get(j).id);
                    rows.add(new Object[]{m.name, cols.get(j).label, old == null ? "(없음)" : old, nv[i][j]});
                }
            }
            JTable t = new JTable(rows.toArray(new Object[0][]), new Object[]{"모듈", "키", "기존 값", "새 값"}) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            t.setRowHeight(20);
            JScrollPane sp = new JScrollPane(t);
            sp.setPreferredSize(new Dimension(680, Math.min(360, 40 + rows.size() * 20)));
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.add(new JLabel(targets.size() + "개 모듈에 아래처럼 넣을까? (저장 전까지는 [변경 취소]로 되돌릴 수 있어)"), BorderLayout.NORTH);
            p.add(sp, BorderLayout.CENTER);
            return JOptionPane.showConfirmDialog(this, p, "적용 미리보기", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    == JOptionPane.OK_OPTION;
        }

        private static String join(List<String> items, int max) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size() && i < max; i++) sb.append(items.get(i)).append('\n');
            if (items.size() > max) sb.append("… 외 ").append(items.size() - max).append("건");
            return sb.toString();
        }

        private static String names(List<Module> ms) {
            List<String> out = new ArrayList<String>();
            for (Module m : ms) out.add(m.name);
            String s = out.toString();
            return s.length() > 300 ? s.substring(0, 300) + " …]" : s;
        }

        private void clearApplyKeys(Sec s) {
            for (Col c : s.cols) { applyKeys.remove(c.id); incs.remove(c.id); }
        }

        /** 위 표에서 '적용' 체크된, 읽기 성공한 모듈 (표 순서) */
        private List<Module> checkedTargets() {
            List<Module> out = new ArrayList<Module>();
            for (Module m : model.modules) if (m.selected && m.error == null) out.add(m);
            return out;
        }

        /** 구조가 바뀐 뒤: 섹션/키 컬럼을 다시 만들고, 선택(picks)은 섹션 이름으로 이어 붙임 */
        private void refreshStructure() {
            Map<String, Col> prev = new HashMap<String, Col>();
            for (Col c : model.allCols()) prev.put(c.id, c);
            List<Sec> secs = keepOrder(buildSecs(model.modules, prev), model.secs);
            Map<String, Sec> byName = new HashMap<String, Sec>();
            for (Sec s : secs) byName.put(s.name.toLowerCase(), s);
            LinkedHashMap<Sec, Integer> np = new LinkedHashMap<Sec, Integer>();
            for (Map.Entry<Sec, Integer> e : picks.entrySet()) {
                Sec ns = byName.get(e.getKey().name.toLowerCase());
                if (ns != null) np.put(ns, e.getValue()); else clearApplyKeys(e.getKey());
            }
            picks.clear();
            picks.putAll(np);
            model.setData(new ArrayList<Module>(model.modules), secs);
            setupColumns();
            rebuildForms();
            updateSummary();
        }

        private boolean noTargets(List<Module> targets) {
            if (!targets.isEmpty()) return false;
            JOptionPane.showMessageDialog(this, "위 표에서 '적용' 체크된 모듈이 없어.");
            return true;
        }

        private boolean confirmOp(String what, List<Module> hit) {
            return JOptionPane.showConfirmDialog(this,
                    what + "\n\n" + hit.size() + "개 모듈에 적용됨: " + names(hit)
                            + "\n\n바로 저장되지 않고 저장 대기로 쌓여. [저장] 때 반영, [변경 취소]로 되돌릴 수 있어.",
                    "확인", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
        }

        private void logOp(String what, int done, List<String> skipped) {
            logLine(what + " → " + done + "개 모듈" + (skipped.isEmpty() ? "" : " (건너뜀: " + skipped + ")") + " (아직 저장 전)");
        }

        private void addSection() {
            stopEditing();
            List<Module> targets = checkedTargets();
            if (noTargets(targets)) return;
            String name = JOptionPane.showInputDialog(this, "추가할 섹션 이름\n('적용' 체크된 " + targets.size() + "개 모듈의 파일 끝에 추가)", "섹션 추가", JOptionPane.PLAIN_MESSAGE);
            if (name == null) return;
            name = name.trim();
            String err = sectionNameError(name);
            if (err != null) { JOptionPane.showMessageDialog(this, err); return; }
            int done = 0;
            List<String> skipped = new ArrayList<String>();
            for (Module m : targets) {
                if (m.applyOp(new Op(Op.ADD_SEC, name, null, null))) done++; else skipped.add(m.name + " 이미 있음");
            }
            logOp("섹션 추가 [" + name + "]", done, skipped);
            refreshStructure();
        }

        private void deleteSection(Sec s) {
            stopEditing();
            if (s.name.isEmpty()) return;
            List<Module> targets = checkedTargets();
            if (noTargets(targets)) return;
            List<Module> hit = new ArrayList<Module>();
            for (Module m : targets) if (m.work.hasSection(s.name)) hit.add(m);
            if (hit.isEmpty()) { JOptionPane.showMessageDialog(this, "체크된 모듈 중에 " + s.label + " 섹션이 있는 모듈이 없어."); return; }
            if (!confirmOp(s.label + " 섹션을 삭제할까? (헤더부터 다음 섹션 직전까지)", hit)) return;
            int done = 0;
            for (Module m : hit) if (m.applyOp(new Op(Op.DEL_SEC, s.name, null, null))) done++;
            logOp("섹션 삭제 " + s.label, done, Collections.<String>emptyList());
            refreshStructure();
        }

        private void addKeyTo(Sec s, String key, String value) {
            stopEditing();
            key = key == null ? "" : key.trim();
            value = value == null ? "" : value;
            String err = keyNameError(key);
            if (err == null && (value.contains("\n") || value.contains("\r"))) err = "값에 줄바꿈은 못 써";
            if (err == null && isPortKey(key) && !value.trim().matches("\\d{1,5}")) err = "port 가 들어간 키는 숫자만 넣을 수 있어";
            if (err != null) { JOptionPane.showMessageDialog(this, err); return; }
            List<Module> targets = checkedTargets();
            if (noTargets(targets)) return;
            int done = 0;
            List<String> skipped = new ArrayList<String>();
            for (Module m : targets) {
                if (!m.work.hasSection(s.name)) skipped.add(m.name + " 섹션 없음");
                else if (m.work.hasKey(s.name, key)) skipped.add(m.name + " 이미 있음");
                else if (m.applyOp(new Op(Op.ADD_KEY, s.name, key, value))) done++;
            }
            logOp("키 추가 " + s.label + " " + key + "=" + value, done, skipped);
            refreshStructure();
        }

        private void deleteKey(Sec s, Col col) {
            stopEditing();
            List<Module> targets = checkedTargets();
            if (noTargets(targets)) return;
            List<Module> hit = new ArrayList<Module>();
            for (Module m : targets) if (m.work.hasKey(s.name, col.key)) hit.add(m);
            if (hit.isEmpty()) { JOptionPane.showMessageDialog(this, "체크된 모듈 중에 " + col.label + " 키가 있는 모듈이 없어."); return; }
            if (!confirmOp(col.label + " 키 줄을 삭제할까?", hit)) return;
            int done = 0;
            for (Module m : hit) if (m.applyOp(new Op(Op.DEL_KEY, s.name, col.key, null))) done++;
            // 같은 키를 다른 모듈에서 '추가 대기'로 입력해 둔 값도 체크 모듈에선 버림
            for (Module m : targets) m.edits.remove(col.id);
            logOp("키 삭제 " + col.label, done, Collections.<String>emptyList());
            refreshStructure();
        }

        private void renameKey(Sec s, Col col) {
            stopEditing();
            List<Module> targets = checkedTargets();
            if (noTargets(targets)) return;
            List<Module> hit = new ArrayList<Module>();
            for (Module m : targets) if (m.work.hasKey(s.name, col.key)) hit.add(m);
            if (hit.isEmpty()) { JOptionPane.showMessageDialog(this, "체크된 모듈 중에 " + col.label + " 키가 있는 모듈이 없어."); return; }
            Object in = JOptionPane.showInputDialog(this, col.label + " 의 새 이름 (값과 형식은 그대로)", "키 이름 변경",
                    JOptionPane.PLAIN_MESSAGE, null, null, col.key);
            if (in == null) return;
            String nk = in.toString().trim();
            String err = keyNameError(nk);
            if (err != null) { JOptionPane.showMessageDialog(this, err); return; }
            if (nk.equals(col.key)) return;
            List<String> conflict = new ArrayList<String>();
            if (!nk.equalsIgnoreCase(col.key)) {
                for (Module m : hit) if (m.work.hasKey(s.name, nk)) conflict.add(m.name);
            }
            if (!conflict.isEmpty()) {
                JOptionPane.showMessageDialog(this, s.label + " 섹션에 이미 '" + nk + "' 키가 있는 모듈이 있어서 변경하지 않았어.\n\n" + join(conflict, 20),
                        "이름 중복", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!confirmOp(col.label + " → " + nk + " 로 이름을 바꿀까?", hit)) return;
            int done = 0;
            for (Module m : hit) if (m.applyOp(new Op(Op.REN_KEY, s.name, col.key, nk))) done++;
            String nid = colId(s.name, nk);
            if (!nid.equals(col.id)) {
                if (applyKeys.remove(col.id)) applyKeys.add(nid);
                IncSetting st = incs.remove(col.id);
                if (st != null) incs.put(nid, st);
            }
            logOp("키 이름 변경 " + col.label + " → " + nk, done, Collections.<String>emptyList());
            refreshStructure();
        }

        private void revertAll() {
            stopEditing();
            Charset cs = charset();
            for (Module m : model.modules) m.discardPending(cs);
            refreshStructure();
            logLine("변경 취소됨");
        }

        private void saveAll() {
            stopEditing();
            Map<String, Col> colMap = new HashMap<String, Col>();
            for (Col c : model.allCols()) colMap.put(c.id, c);

            List<Module> targets = new ArrayList<Module>();
            int valueCount = 0, opCount = 0;
            for (Module m : model.modules) {
                if (m.pendingCount() == 0) continue;
                for (Map.Entry<String, String> e : m.edits.entrySet()) {
                    Col c = colMap.get(e.getKey());
                    if (c != null && isPortKey(c.key) && !e.getValue().trim().matches("\\d{1,5}")) {
                        JOptionPane.showMessageDialog(this, "[" + m.name + "] " + c.label + " 값이 숫자가 아님: " + e.getValue());
                        return;
                    }
                }
                valueCount += m.edits.size();
                opCount += m.ops.size();
                targets.add(m);
            }
            if (targets.isEmpty()) { JOptionPane.showMessageDialog(this, "변경된 값이 없어."); return; }

            if (JOptionPane.showConfirmDialog(this,
                    targets.size() + "개 모듈을 저장할까? (값 " + valueCount + "개 · 섹션/키 구조 변경 " + opCount + "개)\n각 폴더에 백업이 먼저 만들어져.",
                    "저장 확인", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;

            String ts = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            Charset cs = charset();
            int ok = 0, fail = 0;
            logLine("---- 저장 시작 (" + ts + ") ----");
            for (Module m : targets) {
                try {
                    // 파일을 새로 읽어서 구조 변경 → 값 변경 순서로 다시 적용
                    IniDoc doc = IniDoc.load(m.ini, cs);
                    StringBuilder detail = new StringBuilder();
                    for (Op op : m.ops) {
                        if (!op.apply(doc)) throw new IOException("파일 내용이 바뀌어서 적용 못함: " + op.describe());
                        detail.append(' ').append(op.describe()).append(';');
                    }
                    for (Map.Entry<String, String> e : m.edits.entrySet()) {
                        Col c = colMap.get(e.getKey());
                        if (c == null) throw new IOException("알 수 없는 키 (변경 취소 후 다시 해줘): " + e.getKey().replace('\u0000', ' '));
                        doc.set(c.section, c.key, e.getValue());
                        detail.append(' ').append(c.label).append('=').append(e.getValue()).append(';');
                    }
                    Files.copy(m.ini, m.ini.resolveSibling(m.ini.getFileName() + ".bak_" + ts), StandardCopyOption.COPY_ATTRIBUTES);
                    doc.save(m.ini);
                    m.discardPending(cs);
                    ok++;
                    logLine("[" + m.name + "] 저장:" + detail);
                } catch (Exception ex) {
                    fail++;
                    logLine("[" + m.name + "] 실패: " + ex);
                }
            }
            refreshStructure();
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
