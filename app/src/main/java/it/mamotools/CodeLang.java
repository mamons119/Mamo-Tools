package it.mamotools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Linguaggi riconosciuti dal lettore di codice: colorazione e tipo di indentazione. Java puro. */
final class CodeLang {

    static final int GENERIC = 0, XML = 1, HTML = 2, JSON = 3, CSS = 4, MARKDOWN = 5, YAML = 6, INI = 7;
    static final int FMT_NONE = 0, FMT_BRACE = 1, FMT_JSON = 2, FMT_XML = 3, FMT_HTML = 4, FMT_SHELL = 5, FMT_INDENT = 6;

    final String name;
    final String[] exts;
    int kind = GENERIC, fmt = FMT_NONE;
    String[] lineC = {};
    String blockStart = null, blockEnd = null;
    String quotes = "\"'";
    boolean triple = false, backtick = false, charLit = false, dollarVar = false, ci = false, hashPre = false,
            atAnno = false, capsType = false, pyPrefix = false, hashCommentWs = false;
    final Set<String> kw = new HashSet<>(), types = new HashSet<>();

    private CodeLang(String name, String exts) {
        this.name = name;
        this.exts = exts.split(" ");
    }

    private CodeLang kw(String s) {
        for (String w : s.split(" ")) {
            kw.add(ci ? w.toLowerCase(Locale.ROOT) : w);
        }
        return this;
    }

    private CodeLang ty(String s) {
        for (String w : s.split(" ")) {
            types.add(ci ? w.toLowerCase(Locale.ROOT) : w);
        }
        return this;
    }

    static final List<CodeLang> ALL = new ArrayList<>();

    private static CodeLang add(CodeLang l) {
        ALL.add(l);
        return l;
    }

    static {
        String cKw = "auto break case const continue default do else enum extern for goto if inline register restrict "
                + "return signed sizeof static struct switch typedef union unsigned volatile while true false NULL";
        String cTy = "void char short int long float double bool size_t ssize_t uint8_t uint16_t uint32_t uint64_t "
                + "int8_t int16_t int32_t int64_t FILE wchar_t _Bool";
        CodeLang c = add(new CodeLang("C", "c h"));
        c.lineC = new String[]{"//"};
        c.blockStart = "/*";
        c.blockEnd = "*/";
        c.fmt = FMT_BRACE;
        c.charLit = true;
        c.hashPre = true;
        c.kw(cKw).ty(cTy);
        CodeLang cpp = add(new CodeLang("C++", "cpp cc cxx hpp hh hxx ino"));
        cpp.lineC = new String[]{"//"};
        cpp.blockStart = "/*";
        cpp.blockEnd = "*/";
        cpp.fmt = FMT_BRACE;
        cpp.charLit = true;
        cpp.hashPre = true;
        cpp.kw(cKw + " class namespace template typename using new delete public private protected virtual override final "
                + "constexpr nullptr this try catch throw operator friend explicit mutable decltype noexcept "
                + "static_cast dynamic_cast reinterpret_cast const_cast and or not export concept requires").ty(cTy
                + " string vector map set list array unique_ptr shared_ptr std");
        CodeLang java = add(new CodeLang("Java", "java"));
        java.lineC = new String[]{"//"};
        java.blockStart = "/*";
        java.blockEnd = "*/";
        java.fmt = FMT_BRACE;
        java.charLit = true;
        java.atAnno = true;
        java.capsType = true;
        java.kw("abstract assert break case catch class const continue default do else enum extends final finally for "
                + "goto if implements import instanceof interface native new package private protected public return "
                + "static strictfp super switch synchronized this throw throws transient try volatile while var record "
                + "sealed permits yield true false null").ty("boolean byte char double float int long short void String");
        CodeLang kt = add(new CodeLang("Kotlin", "kt kts"));
        kt.lineC = new String[]{"//"};
        kt.blockStart = "/*";
        kt.blockEnd = "*/";
        kt.fmt = FMT_BRACE;
        kt.charLit = true;
        kt.atAnno = true;
        kt.capsType = true;
        kt.triple = true;
        kt.kw("fun val var class object interface when is in as by init constructor companion data sealed open override "
                + "abstract private protected public internal suspend inline lateinit return if else for while do try "
                + "catch finally throw import package typealias true false null this super it enum annotation "
                + "reified crossinline noinline tailrec operator infix vararg where out").ty("Int Long Short Byte Float "
                + "Double Boolean Char String Unit Any Nothing");
        CodeLang cs = add(new CodeLang("C#", "cs"));
        cs.lineC = new String[]{"//"};
        cs.blockStart = "/*";
        cs.blockEnd = "*/";
        cs.fmt = FMT_BRACE;
        cs.charLit = true;
        cs.hashPre = true;
        cs.capsType = true;
        cs.kw("abstract as base break case catch checked class const continue default delegate do else enum event "
                + "explicit extern false finally fixed for foreach goto if implicit in interface internal is lock "
                + "namespace new null operator out override params private protected public readonly ref return sealed "
                + "sizeof stackalloc static struct switch this throw true try typeof unchecked unsafe using virtual "
                + "volatile while var async await record init get set yield nameof when where partial").ty("bool byte "
                + "char decimal double float int long object sbyte short string uint ulong ushort void dynamic");
        CodeLang js = add(new CodeLang("JavaScript", "js mjs cjs jsx"));
        js.lineC = new String[]{"//"};
        js.blockStart = "/*";
        js.blockEnd = "*/";
        js.fmt = FMT_BRACE;
        js.backtick = true;
        js.capsType = true;
        js.kw("break case catch class const continue debugger default delete do else export extends finally for "
                + "function if import in instanceof let new return super switch this throw try typeof var void while "
                + "with yield async await of static get set from as true false null undefined NaN Infinity");
        CodeLang ts = add(new CodeLang("TypeScript", "ts tsx"));
        ts.lineC = js.lineC;
        ts.blockStart = "/*";
        ts.blockEnd = "*/";
        ts.fmt = FMT_BRACE;
        ts.backtick = true;
        ts.capsType = true;
        ts.atAnno = true;
        ts.kw("break case catch class const continue debugger default delete do else export extends finally for "
                + "function if import in instanceof let new return super switch this throw try typeof var void while "
                + "with yield async await of static get set from as true false null undefined interface type enum "
                + "implements namespace declare abstract readonly private public protected is keyof infer never "
                + "unknown override satisfies").ty("any string number boolean object symbol bigint void");
        CodeLang py = add(new CodeLang("Python", "py pyw pyi"));
        py.lineC = new String[]{"#"};
        py.triple = true;
        py.atAnno = true;
        py.pyPrefix = true;
        py.fmt = FMT_INDENT;
        py.kw("and as assert async await break class continue def del elif else except finally for from global if "
                + "import in is lambda nonlocal not or pass raise return try while with yield True False None match "
                + "case self cls").ty("int str float list dict set tuple bool bytes type object range print len open "
                + "super isinstance enumerate zip map filter sorted sum min max abs input");
        CodeLang go = add(new CodeLang("Go", "go"));
        go.lineC = new String[]{"//"};
        go.blockStart = "/*";
        go.blockEnd = "*/";
        go.fmt = FMT_BRACE;
        go.backtick = true;
        go.charLit = true;
        go.kw("break case chan const continue default defer else fallthrough for func go goto if import interface map "
                + "package range return select struct switch type var true false nil iota").ty("string int int8 int16 "
                + "int32 int64 uint uint8 uint16 uint32 uint64 float32 float64 bool byte rune error any uintptr");
        CodeLang rs = add(new CodeLang("Rust", "rs"));
        rs.lineC = new String[]{"//"};
        rs.blockStart = "/*";
        rs.blockEnd = "*/";
        rs.fmt = FMT_BRACE;
        rs.charLit = true;
        rs.capsType = true;
        rs.kw("as async await break const continue crate dyn else enum extern false fn for if impl in let loop match "
                + "mod move mut pub ref return self Self static struct super trait true type unsafe use where while")
                .ty("i8 i16 i32 i64 i128 u8 u16 u32 u64 u128 f32 f64 bool char str usize isize");
        CodeLang sw = add(new CodeLang("Swift", "swift"));
        sw.lineC = new String[]{"//"};
        sw.blockStart = "/*";
        sw.blockEnd = "*/";
        sw.fmt = FMT_BRACE;
        sw.atAnno = true;
        sw.capsType = true;
        sw.kw("class struct enum protocol extension func var let if else guard switch case default for in while "
                + "repeat return break continue import init deinit self super nil true false try catch throw throws "
                + "public private internal fileprivate open static final override lazy weak async await where as is");
        CodeLang php = add(new CodeLang("PHP", "php phtml"));
        php.lineC = new String[]{"//", "#"};
        php.blockStart = "/*";
        php.blockEnd = "*/";
        php.fmt = FMT_BRACE;
        php.dollarVar = true;
        php.capsType = true;
        php.ci = true;
        php.kw("abstract and array as break callable case catch class clone const continue declare default do echo "
                + "else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn "
                + "for foreach function global if implements include include_once instanceof interface isset list "
                + "namespace new or print private protected public require require_once return static switch throw "
                + "trait try unset use var while yield true false null");
        CodeLang rb = add(new CodeLang("Ruby", "rb rake gemspec"));
        rb.lineC = new String[]{"#"};
        rb.capsType = true;
        rb.kw("alias and begin break case class def defined? do else elsif end ensure false for if in module next nil "
                + "not or redo rescue retry return self super then true undef unless until when while yield require "
                + "attr_accessor attr_reader attr_writer puts");
        CodeLang lua = add(new CodeLang("Lua", "lua"));
        lua.lineC = new String[]{"--"};
        lua.kw("and break do else elseif end false for function goto if in local nil not or repeat return then true "
                + "until while");
        CodeLang dart = add(new CodeLang("Dart", "dart"));
        dart.lineC = new String[]{"//"};
        dart.blockStart = "/*";
        dart.blockEnd = "*/";
        dart.fmt = FMT_BRACE;
        dart.atAnno = true;
        dart.capsType = true;
        dart.kw("abstract as assert async await break case catch class const continue default do else enum extends "
                + "extension factory false final finally for get if implements import in interface is late library "
                + "mixin new null on operator part required rethrow return set static super switch this throw true try "
                + "typedef var void while with yield");
        CodeLang scala = add(new CodeLang("Scala", "scala sc"));
        scala.lineC = new String[]{"//"};
        scala.blockStart = "/*";
        scala.blockEnd = "*/";
        scala.fmt = FMT_BRACE;
        scala.capsType = true;
        scala.atAnno = true;
        scala.kw("abstract case catch class def do else extends false final finally for forSome if implicit import "
                + "lazy match new null object override package private protected return sealed super this throw trait "
                + "try true type val var while with yield given using enum");
        CodeLang groovy = add(new CodeLang("Groovy / Gradle", "gradle groovy"));
        groovy.lineC = new String[]{"//"};
        groovy.blockStart = "/*";
        groovy.blockEnd = "*/";
        groovy.fmt = FMT_BRACE;
        groovy.capsType = true;
        groovy.kw("as assert break case catch class const continue def default do else enum extends false finally for "
                + "goto if implements import in instanceof interface new null package return super switch this throw "
                + "throws trait true try while plugins dependencies repositories android apply implementation api "
                + "compileOnly testImplementation");
        CodeLang sh = add(new CodeLang("Shell", "sh bash zsh ksh env"));
        sh.lineC = new String[]{"#"};
        sh.hashCommentWs = true;
        sh.fmt = FMT_SHELL;
        sh.dollarVar = true;
        sh.kw("if then else elif fi for while until do done case esac in function select time return exit break "
                + "continue local export readonly declare unset shift source alias set trap eval exec").ty("echo cd "
                + "printf read test true false pwd ls cat grep sed awk find mkdir rm cp mv chmod chown kill wait "
                + "sleep curl wget tar git sudo");
        CodeLang ps = add(new CodeLang("PowerShell", "ps1 psm1"));
        ps.lineC = new String[]{"#"};
        ps.blockStart = "<#";
        ps.blockEnd = "#>";
        ps.dollarVar = true;
        ps.ci = true;
        ps.fmt = FMT_BRACE;
        ps.kw("begin break catch class continue data do dynamicparam else elseif end exit filter finally for foreach "
                + "from function if in param process return switch throw trap try until using var while");
        CodeLang bat = add(new CodeLang("Batch", "bat cmd"));
        bat.lineC = new String[]{"::", "rem ", "REM "};
        bat.ci = true;
        bat.kw("if else for in do goto call exit set echo off on setlocal endlocal shift pause not exist errorlevel "
                + "defined equ neq lss leq gtr geq");
        CodeLang sql = add(new CodeLang("SQL", "sql"));
        sql.lineC = new String[]{"--"};
        sql.blockStart = "/*";
        sql.blockEnd = "*/";
        sql.ci = true;
        sql.kw("select from where insert into values update set delete create table alter drop index join inner left "
                + "right outer full cross on group by order having limit offset union all distinct as and or not null "
                + "is in like between exists case when then else end primary key foreign references default unique "
                + "check constraint view trigger begin commit rollback with add column if desc asc using returning")
                .ty("int integer bigint smallint varchar char text date time timestamp boolean float double decimal "
                        + "numeric serial blob json uuid");
        CodeLang xml = add(new CodeLang("XML", "xml xsd xsl xslt svg plist rss atom xaml csproj resx kml gpx fxml "
                + "pom config manifest"));
        xml.kind = XML;
        xml.fmt = FMT_XML;
        CodeLang html = add(new CodeLang("HTML", "html htm xhtml vue"));
        html.kind = HTML;
        html.fmt = FMT_HTML;
        CodeLang json = add(new CodeLang("JSON", "json jsonc json5 geojson webmanifest ipynb"));
        json.kind = JSON;
        json.fmt = FMT_JSON;
        json.lineC = new String[]{"//"};
        json.blockStart = "/*";
        json.blockEnd = "*/";
        CodeLang css = add(new CodeLang("CSS / SCSS", "css scss sass less"));
        css.kind = CSS;
        css.fmt = FMT_BRACE;
        css.lineC = new String[]{"//"};
        css.blockStart = "/*";
        css.blockEnd = "*/";
        CodeLang md = add(new CodeLang("Markdown", "md markdown"));
        md.kind = MARKDOWN;
        CodeLang yaml = add(new CodeLang("YAML", "yml yaml"));
        yaml.kind = YAML;
        yaml.lineC = new String[]{"#"};
        yaml.hashCommentWs = true;
        yaml.fmt = FMT_INDENT;
        CodeLang ini = add(new CodeLang("INI / Properties / TOML", "ini properties toml cfg conf cnf desktop"));
        ini.kind = INI;
        ini.lineC = new String[]{"#", ";"};
        CodeLang mk = add(new CodeLang("Makefile / Docker", "mk makefile dockerfile cmake"));
        mk.lineC = new String[]{"#"};
        mk.dollarVar = true;
        mk.kw("if else endif ifdef ifndef include define endef FROM RUN CMD ENTRYPOINT COPY ADD ENV ARG EXPOSE "
                + "WORKDIR VOLUME USER LABEL");
        CodeLang txt = add(new CodeLang("Testo semplice", "txt log text"));
        txt.quotes = "";
    }

    static CodeLang byName(String n) {
        for (CodeLang l : ALL) {
            if (l.name.equals(n)) {
                return l;
            }
        }
        return ALL.get(ALL.size() - 1);
    }

    /** Sceglie il linguaggio dal nome del file e dalla prima riga (shebang, <?xml, parentesi graffa). */
    static CodeLang detect(String fileName, String text) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : n;
        for (CodeLang l : ALL) {
            for (String e : l.exts) {
                if (e.equals(ext) || e.equals(n)) {
                    return l;
                }
            }
        }
        String head = text.length() > 300 ? text.substring(0, 300) : text;
        String t = head.trim();
        if (t.startsWith("#!")) {
            String line = t.contains("\n") ? t.substring(0, t.indexOf('\n')) : t;
            if (line.contains("python")) {
                return byName("Python");
            }
            if (line.contains("node")) {
                return byName("JavaScript");
            }
            if (line.contains("ruby")) {
                return byName("Ruby");
            }
            if (line.contains("php")) {
                return byName("PHP");
            }
            return byName("Shell");
        }
        if (t.startsWith("<?xml")) {
            return byName("XML");
        }
        String low = t.toLowerCase(Locale.ROOT);
        if (low.startsWith("<!doctype html") || low.startsWith("<html")) {
            return byName("HTML");
        }
        if (t.startsWith("<?php")) {
            return byName("PHP");
        }
        if (t.startsWith("{") || t.startsWith("[")) {
            return byName("JSON");
        }
        if (t.startsWith("<")) {
            return byName("XML");
        }
        return byName("Testo semplice");
    }
}
