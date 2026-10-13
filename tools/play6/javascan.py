#!/usr/bin/env python3
"""javascan.py: a small Java source scanner for the Play 6 checks (check_handshake.py, check_play6_hal.py).

Not a parser: it masks comments, strings and char literals, then splits the body of the file's first
top-level class into members by brace and semicolon depth. That is enough for the hand-written HAL
client files it reads (one class each, plain Java 17). Library only, no main.
"""
import re


def mask(src):
    """Same length as src: comment and literal contents become spaces (newlines kept)."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == "/" and src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if src[k] != "\n":
                    out[k] = " "
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i + 3, j - 3):
                if src[k] != "\n":
                    out[k] = " "
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            for k in range(i + 1, min(j, n)):
                out[k] = " "
            i = j + 1
        else:
            i += 1
    return "".join(out)


def matching(m, i, open_ch="{", close_ch="}"):
    """Index of the bracket closing the one at m[i] (m is masked text)."""
    d = 0
    for j in range(i, len(m)):
        if m[j] == open_ch:
            d += 1
        elif m[j] == close_ch:
            d -= 1
            if d == 0:
                return j
    raise ValueError("unbalanced %s at offset %d" % (open_ch, i))


def _depth_step(s, i, d, da):
    """Bracket depth after s[i]: d for (), [] and {}, da for generic <> (not << >> <= >= -> or a spaced
    comparison)."""
    ch = s[i]
    prev = s[i - 1] if i > 0 else " "
    nxt = s[i + 1] if i + 1 < len(s) else " "
    if ch in "([{":
        d += 1
    elif ch in ")]}":
        d -= 1
    elif ch == "<" and (prev.isalnum() or prev in "_$>?") and nxt not in "<=" and prev != "<":
        da += 1
    elif ch == ">" and da > 0 and prev not in "-=" and nxt != "=":
        da -= 1
    return d, da


def split_top(s, sep=","):
    """Split s at sep outside (), [], {} and generic <> (s is masked text)."""
    parts, cur, d, da = [], [], 0, 0
    for i, ch in enumerate(s):
        d, da = _depth_step(s, i, d, da)
        if ch == sep and d == 0 and da == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return parts


def _top_index(s, ch):
    """First ch outside brackets; for '=' only a plain assignment (not == <= >= !=)."""
    d, da = 0, 0
    for i, c in enumerate(s):
        if c == ch and d == 0 and da == 0:
            if ch == "=" and ((i + 1 < len(s) and s[i + 1] == "=") or (i > 0 and s[i - 1] in "=<>!")):
                continue
            return i
        d, da = _depth_step(s, i, d, da)
    return -1


ANNOTATION = re.compile(r"@[\w.]+(\s*\([^)]*\))?")


def _blank(rx, s):
    return rx.sub(lambda x: " " * len(x.group(0)), s)


MODIFIERS = {"public", "protected", "private", "static", "final", "abstract", "synchronized",
             "native", "transient", "volatile", "default", "strictfp", "sealed", "non-sealed"}
IDENT = re.compile(r"[A-Za-z_$][\w$]*")


class Member:
    """One member of the class body. kind: field | method | ctor | type | init."""

    def __init__(self, src, m, start, end, lead_start):
        self.start, self.end = start, end              # code text [start, end)
        self.lead_start = lead_start                   # start of the attached comment block
        self.code = src[start:end]
        self.text = src[lead_start:end]
        self.trailing_comment = ""
        nl = src.find("\n", end)
        rest = src[end:nl if nl >= 0 else len(src)]
        if rest.strip().startswith("//") and m[end:nl if nl >= 0 else len(src)].strip() == "":
            self.trailing_comment = rest.strip()
            self.text = src[lead_start:end] + rest.rstrip()
        mc = m[start:end]
        self.mcode = mc
        self.body = None                               # (start, end) offsets of { ... } for methods
        self.params = []                               # [(type, name)]
        self.names = []
        self.modifiers = set()
        self.type = ""
        self.declarators = []                          # fields: [(name, initializer source)]
        brace = mc.find("{")
        semi = mc.find(";")
        head_end = min(x for x in (brace, semi, len(mc)) if x >= 0)
        head = _blank(ANNOTATION, mc[:head_end])      # same length, so offsets stay valid
        eq = _top_index(head, "=")
        paren = head.find("(")
        t = re.match(r"\s*((?:[\w-]+\s+)*?)(class|interface|enum|record)\s+(\w+)", head)
        if t and all(w in MODIFIERS for w in t.group(1).split()):
            self.kind = "type"
            self.names = [t.group(3)]
            self.key = "type " + t.group(3)
        elif paren >= 0 and (eq < 0 or paren < eq):
            pre = head[:paren].split()
            name = pre[-1] if pre else "?"
            rest = [w for w in pre[:-1] if w not in MODIFIERS]
            if rest and rest[0].startswith("<"):       # generic method type parameters
                rest = rest[1:]
            self.modifiers = {w for w in pre[:-1] if w in MODIFIERS}
            self.kind = "method" if rest else "ctor"
            self.type = " ".join(rest)
            close = matching(mc, paren, "(", ")")
            for p in split_top(_blank(ANNOTATION, mc[paren + 1:close])):
                p = re.sub(r"\bfinal\s+", "", p).strip()
                if not p:
                    continue
                pw = p.rsplit(None, 1)
                self.params.append((" ".join(pw[0].split()) if len(pw) == 2 else "", pw[-1]))
            self.names = [name]
            self.key = "%s(%s)" % (name, ",".join(t.replace(" ", "") for t, _ in self.params))
            if brace > close and (semi < 0 or brace < semi):
                self.body = (start + brace, start + matching(mc, brace))
        elif brace >= 0 and (semi < 0 or brace < semi) and eq < 0:
            self.kind = "init"
            self.key = "init@%d" % start
        else:
            self.kind = "field"
            decl = mc[:mc.rfind(";")] if ";" in mc else mc
            parts = split_top(_blank(ANNOTATION, decl))
            first = parts[0]
            feq = _top_index(first, "=")
            fw = IDENT.findall(first[:feq] if feq >= 0 else first)
            self.modifiers = {w for w in fw[:-1] if w in MODIFIERS}
            self.type = " ".join(w for w in fw[:-1] if w not in MODIFIERS)
            off = start
            for p in parts:
                peq = _top_index(p, "=")
                nm = IDENT.findall(p[:peq] if peq >= 0 else p)
                init_s = src[off + peq + 1:off + len(p)].strip() if peq >= 0 else ""
                self.declarators.append((nm[-1] if nm else "?", init_s))
                off += len(p) + 1
            self.names = [d[0] for d in self.declarators]
            self.key = "field " + ",".join(self.names)

    def line(self, src):
        return src.count("\n", 0, self.start) + 1


class JavaFile:
    def __init__(self, path, src=None):
        self.path = path
        self.src = open(path, encoding="utf-8").read() if src is None else src
        self.m = mask(self.src)
        mm = re.search(r"^[ \t]*(?:[\w@]+[ \t]+)*(class|interface|enum)\s+(\w+)[^{]*\{", self.m, re.M)
        if not mm:
            raise ValueError("%s: no top-level class" % path)
        self.class_name = mm.group(2)
        self.header = self.src[mm.start():mm.end()]
        ob = mm.end() - 1
        cb = matching(self.m, ob)
        self.package = (re.search(r"^\s*package\s+([\w.]+)\s*;", self.m, re.M) or [None, ""])[1]
        self.imports = re.findall(r"^\s*import\s+(static\s+)?([\w.]+(?:\.\*)?)\s*;", self.m, re.M)
        self.members = []
        i = ob + 1
        prev_end = i
        while i < cb:
            while i < cb and self.m[i].isspace():
                i += 1
            if i >= cb:
                break
            if self.m[i] == ";":                       # stray semicolon
                i += 1
                prev_end = i
                continue
            start = i
            j = i
            depth_p = 0
            end = None
            seen_eq = False
            while j < cb:
                c = self.m[j]
                if c == "(":
                    depth_p += 1
                elif c == ")":
                    depth_p -= 1
                elif c == "=" and depth_p == 0:
                    seen_eq = True
                elif c == ";" and depth_p == 0:
                    end = j + 1
                    break
                elif c == "{" and depth_p == 0:
                    k = matching(self.m, j)
                    if seen_eq:                        # array initializer or anonymous class
                        j = k + 1
                        continue
                    end = k + 1
                    break
                j += 1
            if end is None:
                raise ValueError("%s: member at offset %d does not end" % (path, start))
            lead = self._lead_start(prev_end, start)
            mem = Member(self.src, self.m, start, end, lead)
            self.members.append(mem)
            if mem.trailing_comment:
                nl = self.src.find("\n", end)
                prev_end = len(self.src) if nl < 0 else nl
            else:
                prev_end = end
            i = prev_end
        self.body_end = cb

    def _lead_start(self, prev_end, start):
        """Start of the member's text: its line start, or the first line of the comment block right
        above it (comment-only lines, no blank line in between)."""
        line_start = self.src.rfind("\n", 0, start) + 1
        if line_start < prev_end:                      # shares a line with the previous member
            return start
        pos = line_start
        while pos > prev_end:
            ls = self.src.rfind("\n", 0, pos - 1) + 1  # previous line is [ls, pos - 1)
            if ls < prev_end:
                break
            if self.src[ls:pos - 1].strip() == "" or self.m[ls:pos - 1].strip() != "":
                break
            pos = ls
        return pos

    def member_text(self, mem):
        """Member text with trailing blanks per line removed and the indentation kept."""
        return "\n".join(l.rstrip() for l in mem.text.split("\n")).strip("\n")

    def methods(self):
        return [x for x in self.members if x.kind in ("method", "ctor")]

    def fields(self):
        return [x for x in self.members if x.kind == "field"]

    def line_of(self, offset):
        return self.src.count("\n", 0, offset) + 1
