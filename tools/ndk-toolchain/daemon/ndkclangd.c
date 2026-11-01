// ndkclangd: a resident clang for the CodeAssist NDK plugin.
//
// Launching clang per request costs a process start (~17 ms on the emulator, several times that on a
// phone) and a reload of the precompiled preamble, on every completion and every error check. This keeps
// each open file's translation unit -- and the preamble libclang precompiles for it -- in memory instead,
// so a request is a reparse of the user's own code against headers that are already loaded.
//
// It is deliberately small and dumb: one thread, one request at a time, no protocol beyond framing. The
// plugin owns every policy (which flags, when to give up, when to fall back to running clang directly).
//
// PROTOCOL (stdin -> stdout, binary-safe):
//
//   request:   <cmd> <id> <line> <col> <nblobs>\n   then nblobs x ( <len>\n <len bytes> )
//              blobs: [0] file path, [1] file text, [2..] compiler arguments (no file name, no -fsyntax-only)
//   response:  <id> <status> <len>\n <len bytes>
//              status: ok | error
//
//   cmd = check     reparse the file, answer its diagnostics in clang's command-line format:
//                   `path:line:col:{l:c-l:c}: error: message [-Wflag]`, notes after their diagnostic
//   cmd = complete  answer candidates at line:col (1-based) as `COMPLETION(<kind>): <typed> : <chunks>`, the
//                   command-line format plus the cursor kind, which the text format never had
//   cmd = tokens    reparse the file (unless this exact text was just parsed) and answer one line per
//                   identifier it could classify: `<byte offset> <byte length> <kind> <modifiers>`, where kind is
//                   the editor's semantic kind (localVariable, parameter, field, function, method, class, enum,
//                   enumConstant, typeParameter, namespace, label, constant, cppMacro) and modifiers is `-` or
//                   letters: D declaration, S static, R read-only, X deprecated
//   cmd = forget    drop the file's translation unit
//   cmd = ping      answer "pong"
//
// The process exits when stdin closes, so it never outlives the IDE that started it.

#define _POSIX_C_SOURCE 200809L
#include <clang-c/Index.h>
#include <errno.h>
#include <libgen.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define MAX_UNITS 4      // translation units kept; each holds an in-memory preamble of ~10 MB for libc++
#define MAX_BLOBS 512
#define MAX_BLOB (64u << 20)

// ---------------------------------------------------------------------------------------------------
// A growable byte buffer for building responses.
// ---------------------------------------------------------------------------------------------------

typedef struct {
    char *data;
    size_t len, cap;
} Buf;

static void buf_reserve(Buf *b, size_t extra) {
    if (b->len + extra + 1 <= b->cap) return;
    size_t cap = b->cap ? b->cap : 4096;
    while (cap < b->len + extra + 1) cap *= 2;
    char *grown = realloc(b->data, cap);
    if (!grown) { fputs("ndkclangd: out of memory\n", stderr); exit(3); }
    b->data = grown;
    b->cap = cap;
}

static void buf_put(Buf *b, const char *s, size_t n) {
    buf_reserve(b, n);
    memcpy(b->data + b->len, s, n);
    b->len += n;
    b->data[b->len] = 0;
}

static void buf_puts(Buf *b, const char *s) { if (s) buf_put(b, s, strlen(s)); }

static void buf_putx(Buf *b, CXString s) {
    buf_puts(b, clang_getCString(s));
    clang_disposeString(s);
}

// ---------------------------------------------------------------------------------------------------
// Translation units, one per file, least recently used first out.
// ---------------------------------------------------------------------------------------------------

typedef struct {
    char *path;
    char *args_key;   // the arguments joined, so a change of flags means a fresh parse
    CXTranslationUnit tu;
    uint64_t used;
    uint64_t text_hash; // what the unit was last parsed from, so an unchanged buffer is not parsed again
    size_t text_len;
} Unit;

// FNV-1a: enough to tell "the buffer this unit already holds" from "an edited one".
static uint64_t hash_text(const char *text, size_t len) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < len; i++) { h ^= (unsigned char) text[i]; h *= 1099511628211ULL; }
    return h;
}

static CXIndex g_index;
static Unit g_units[MAX_UNITS];
static uint64_t g_clock;

static void unit_drop(Unit *u) {
    if (u->tu) clang_disposeTranslationUnit(u->tu);
    free(u->path);
    free(u->args_key);
    memset(u, 0, sizeof *u);
}

static Unit *unit_find(const char *path) {
    for (int i = 0; i < MAX_UNITS; i++)
        if (g_units[i].path && strcmp(g_units[i].path, path) == 0) return &g_units[i];
    return NULL;
}

static Unit *unit_slot(void) {
    Unit *oldest = &g_units[0];
    for (int i = 0; i < MAX_UNITS; i++) {
        if (!g_units[i].path) return &g_units[i];
        if (g_units[i].used < oldest->used) oldest = &g_units[i];
    }
    unit_drop(oldest);
    return oldest;
}

static char *join_args(const char *const *args, int argc) {
    Buf b = {0};
    buf_puts(&b, "");
    for (int i = 0; i < argc; i++) { buf_puts(&b, args[i]); buf_put(&b, "\n", 1); }
    return b.data;
}

// Quote-includes and relative -I flags resolve against the working directory, as they would for a compile
// run from the file's own directory.
static void enter_dir_of(const char *path) {
    char *copy = strdup(path);
    if (!copy) return;
    if (chdir(dirname(copy)) != 0) { /* keep the previous directory */ }
    free(copy);
}

// The unit for `path`, parsed if there is none. With `refresh`, an existing one is reparsed against `text`;
// without it (completion) it is used as is, because clang_codeCompleteAt reparses the main file from the
// unsaved text itself, and reparsing first would parse it twice. NULL with *err set when clang could not.
static Unit *unit_for(const char *path, const char *text, size_t text_len,
                      const char *const *args, int argc, int refresh, const char **err) {
    struct CXUnsavedFile unsaved = { path, text, (unsigned long) text_len };
    char *key = join_args(args, argc);
    enter_dir_of(path);

    Unit *u = unit_find(path);
    if (u && strcmp(u->args_key, key) != 0) { unit_drop(u); u = NULL; }

    uint64_t hash = hash_text(text, text_len);
    if (u && !refresh) { free(key); u->used = ++g_clock; return u; }
    // The editor asks for errors and for semantic tokens on the same buffer one after the other; the second
    // request finds the unit already parsed from exactly this text and answers from it.
    if (u && u->text_hash == hash && u->text_len == text_len) { free(key); u->used = ++g_clock; return u; }
    if (u) {
        int rc = clang_reparseTranslationUnit(u->tu, 1, &unsaved, clang_defaultReparseOptions(u->tu));
        if (rc == 0) {
            free(key);
            u->used = ++g_clock;
            u->text_hash = hash;
            u->text_len = text_len;
            return u;
        }
        // A failed reparse leaves the unit unusable (libclang's own contract); start over from a parse.
        unit_drop(u);
        u = NULL;
    }

    unsigned options = clang_defaultEditingTranslationUnitOptions()
        | CXTranslationUnit_PrecompiledPreamble
        | CXTranslationUnit_CreatePreambleOnFirstParse
        | CXTranslationUnit_CacheCompletionResults
        | CXTranslationUnit_KeepGoing
        | CXTranslationUnit_IncludeBriefCommentsInCodeCompletion
        // Macro definitions and expansions as cursors, which semantic tokens colour; without it a macro
        // is invisible to clang_annotateTokens.
        | CXTranslationUnit_DetailedPreprocessingRecord;
    CXTranslationUnit tu = NULL;
    enum CXErrorCode code = clang_parseTranslationUnit2(g_index, path, args, argc, &unsaved, 1, options, &tu);
    if (code != CXError_Success || !tu) {
        free(key);
        *err = code == CXError_Crashed ? "clang crashed parsing the file" : "clang could not parse the file";
        return NULL;
    }
    u = unit_slot();
    u->path = strdup(path);
    u->args_key = key;
    u->tu = tu;
    u->used = ++g_clock;
    u->text_hash = hash;
    u->text_len = text_len;
    return u;
}

// ---------------------------------------------------------------------------------------------------
// Answers.
// ---------------------------------------------------------------------------------------------------

static const unsigned DIAG_FORMAT = CXDiagnostic_DisplaySourceLocation | CXDiagnostic_DisplayColumn
    | CXDiagnostic_DisplaySourceRanges | CXDiagnostic_DisplayOption;

static void put_diagnostic(Buf *out, CXDiagnostic d) {
    if (clang_getDiagnosticSeverity(d) == CXDiagnostic_Ignored) return;
    buf_putx(out, clang_formatDiagnostic(d, DIAG_FORMAT));
    buf_put(out, "\n", 1);
    // Notes ("declared here", "candidate not viable") are children here, where the command line prints them
    // as following lines. Printed the same way, so the plugin folds them into the diagnostic above.
    CXDiagnosticSet children = clang_getChildDiagnostics(d);
    unsigned n = clang_getNumDiagnosticsInSet(children);
    for (unsigned i = 0; i < n; i++) {
        CXDiagnostic child = clang_getDiagnosticInSet(children, i);
        put_diagnostic(out, child);
        clang_disposeDiagnostic(child);
    }
}

static void answer_check(Buf *out, Unit *u) {
    unsigned n = clang_getNumDiagnostics(u->tu);
    for (unsigned i = 0; i < n; i++) {
        CXDiagnostic d = clang_getDiagnostic(u->tu, i);
        put_diagnostic(out, d);
        clang_disposeDiagnostic(d);
    }
}

// The command line's chunk notation, so one parser reads both: [#result#] <#placeholder#> {#optional#}.
static void put_chunks(Buf *out, CXCompletionString cs, Buf *typed) {
    unsigned n = clang_getNumCompletionChunks(cs);
    for (unsigned i = 0; i < n; i++) {
        enum CXCompletionChunkKind kind = clang_getCompletionChunkKind(cs, i);
        if (kind == CXCompletionChunk_Optional) {
            buf_puts(out, "{#");
            put_chunks(out, clang_getCompletionChunkCompletionString(cs, i), NULL);
            buf_puts(out, "#}");
            continue;
        }
        CXString text = clang_getCompletionChunkText(cs, i);
        const char *s = clang_getCString(text);
        switch (kind) {
            case CXCompletionChunk_TypedText:
                if (typed && typed->len == 0) buf_puts(typed, s);
                buf_puts(out, s);
                break;
            case CXCompletionChunk_ResultType:
            case CXCompletionChunk_Informative:
                buf_puts(out, "[#"); buf_puts(out, s); buf_puts(out, "#]");
                break;
            case CXCompletionChunk_Placeholder:
            case CXCompletionChunk_CurrentParameter:
                buf_puts(out, "<#"); buf_puts(out, s); buf_puts(out, "#>");
                break;
            default:
                buf_puts(out, s);
        }
        clang_disposeString(text);
    }
}

static void answer_complete(Buf *out, Unit *u, const char *path, const char *text, size_t text_len,
                            unsigned line, unsigned col) {
    struct CXUnsavedFile unsaved = { path, text, (unsigned long) text_len };
    unsigned options = clang_defaultCodeCompleteOptions() | CXCodeComplete_IncludeMacros
        | CXCodeComplete_IncludeBriefComments;
    CXCodeCompleteResults *results = clang_codeCompleteAt(u->tu, path, line, col, &unsaved, 1, options);
    if (!results) return;
    clang_sortCodeCompletionResults(results->Results, results->NumResults);

    Buf typed = {0}, chunks = {0};
    for (unsigned i = 0; i < results->NumResults; i++) {
        CXCompletionResult *r = &results->Results[i];
        if (clang_getCompletionAvailability(r->CompletionString) == CXAvailability_NotAvailable) continue;
        typed.len = 0; chunks.len = 0;
        buf_puts(&typed, ""); buf_puts(&chunks, "");
        put_chunks(&chunks, r->CompletionString, &typed);
        if (typed.len == 0) continue;

        // The cursor kind's spelling with spaces taken out ("macro definition" -> "macrodefinition"), so it
        // stays one token on the line.
        CXString kind = clang_getCursorKindSpelling(r->CursorKind);
        buf_puts(out, "COMPLETION(");
        for (const char *k = clang_getCString(kind); k && *k; k++) if (*k != ' ') buf_put(out, k, 1);
        clang_disposeString(kind);
        buf_puts(out, "): ");
        buf_put(out, typed.data, typed.len);
        buf_puts(out, " : ");
        buf_put(out, chunks.data, chunks.len);
        buf_put(out, "\n", 1);
    }
    free(typed.data);
    free(chunks.data);
    clang_disposeCodeCompleteResults(results);
}

// ---------------------------------------------------------------------------------------------------
// Semantic tokens.
// ---------------------------------------------------------------------------------------------------

static int is_function_like(enum CXCursorKind k) {
    return k == CXCursor_FunctionDecl || k == CXCursor_CXXMethod || k == CXCursor_Constructor
        || k == CXCursor_Destructor || k == CXCursor_ConversionFunction || k == CXCursor_FunctionTemplate
        || k == CXCursor_LambdaExpr || k == CXCursor_ObjCInstanceMethodDecl || k == CXCursor_ObjCClassMethodDecl;
}

static int is_record(enum CXCursorKind k) {
    return k == CXCursor_StructDecl || k == CXCursor_ClassDecl || k == CXCursor_UnionDecl
        || k == CXCursor_ClassTemplate || k == CXCursor_ClassTemplatePartialSpecialization;
}

// The editor's kind for what [decl] declares, or NULL to leave the token to the lexical layer. Static and
// read-only are added to [mods] where the declaration says so.
static const char *kind_of(CXCursor decl, char *mods, int *nmods) {
    enum CXCursorKind k = clang_getCursorKind(decl);
    switch (k) {
        case CXCursor_VarDecl: {
            enum CXCursorKind parent = clang_getCursorKind(clang_getCursorSemanticParent(decl));
            if (is_function_like(parent)) return "localVariable";
            int is_const = clang_isConstQualifiedType(clang_getCursorType(decl));
            if (is_const) { mods[(*nmods)++] = 'R'; return "constant"; }
            mods[(*nmods)++] = 'S';
            return "field"; // a global or a static data member: storage outside any one call
        }
        case CXCursor_ParmDecl: return "parameter";
        case CXCursor_NonTypeTemplateParameter: return "parameter";
        case CXCursor_FieldDecl: return "field";
        case CXCursor_EnumConstantDecl: return "enumConstant";
        case CXCursor_FunctionDecl: return "function";
        case CXCursor_FunctionTemplate:
            return is_record(clang_getCursorKind(clang_getCursorSemanticParent(decl))) ? "method" : "function";
        case CXCursor_CXXMethod:
        case CXCursor_ConversionFunction:
            if (clang_CXXMethod_isStatic(decl)) mods[(*nmods)++] = 'S';
            return "method";
        // A constructor's name is its class's: it reads as the type, as it does at a use like `Point(1, 2)`.
        case CXCursor_Constructor:
        case CXCursor_Destructor:
        case CXCursor_StructDecl:
        case CXCursor_ClassDecl:
        case CXCursor_UnionDecl:
        case CXCursor_ClassTemplate:
        case CXCursor_ClassTemplatePartialSpecialization:
        case CXCursor_TypedefDecl:
        case CXCursor_TypeAliasDecl:
        case CXCursor_TypeAliasTemplateDecl:
            return "class";
        case CXCursor_EnumDecl: return "enum";
        case CXCursor_TemplateTypeParameter:
        case CXCursor_TemplateTemplateParameter:
            return "typeParameter";
        case CXCursor_Namespace:
        case CXCursor_NamespaceAlias:
            return "namespace";
        case CXCursor_LabelStmt: return "label";
        default: return NULL;
    }
}

static unsigned offset_of(CXSourceLocation loc) {
    unsigned off = 0;
    clang_getSpellingLocation(loc, NULL, NULL, NULL, &off);
    return off;
}

static void answer_tokens(Buf *out, Unit *u, const char *path) {
    CXFile main_file = clang_getFile(u->tu, path);
    if (!main_file) return;
    CXSourceRange whole = clang_getCursorExtent(clang_getTranslationUnitCursor(u->tu));
    CXToken *tokens = NULL;
    unsigned n = 0;
    clang_tokenize(u->tu, whole, &tokens, &n);
    if (!n) return;
    CXCursor *cursors = malloc(sizeof(CXCursor) * n);
    if (!cursors) { clang_disposeTokens(u->tu, tokens, n); return; }
    clang_annotateTokens(u->tu, tokens, n, cursors);

    char line[128];
    for (unsigned i = 0; i < n; i++) {
        if (clang_getTokenKind(tokens[i]) != CXToken_Identifier) continue;
        CXSourceLocation at = clang_getTokenLocation(u->tu, tokens[i]);
        CXFile file = NULL;
        unsigned offset = 0;
        clang_getSpellingLocation(at, &file, NULL, NULL, &offset);
        if (!file || !clang_File_isEqual(file, main_file)) continue;

        CXCursor c = cursors[i];
        enum CXCursorKind k = clang_getCursorKind(c);
        char mods[8];
        int nmods = 0;
        const char *kind = NULL;

        // Inside a macro's arguments (`SQUARE(p.x)`) every token is annotated to the expansion; only the
        // macro's own name is the macro. The arguments are ordinary code, so ask for the cursor at the token.
        if (k == CXCursor_MacroExpansion && offset_of(clang_getCursorLocation(c)) != offset) {
            c = clang_getCursor(u->tu, at);
            k = clang_getCursorKind(c);
            if (k == CXCursor_MacroExpansion) continue;
        }

        if (k == CXCursor_MacroDefinition) {
            // The whole `#define` line is annotated to the definition; only the macro's name is it. Its
            // parameters and body are left to the lexer.
            if (offset_of(clang_getCursorLocation(c)) != offset) continue;
            kind = "cppMacro";
            mods[nmods++] = 'D';
        } else if (k == CXCursor_MacroExpansion) {
            kind = "cppMacro";
        } else if (k == CXCursor_LabelStmt) {
            kind = "label";
            mods[nmods++] = 'D';
        } else if (k == CXCursor_LabelRef) {
            kind = "label";
        } else if (clang_isDeclaration(k)) {
            // A declaration's cursor covers its whole extent; only the token at its NAME is the declaring
            // occurrence. Any other identifier annotated to it (rare: something clang had no finer cursor
            // for) is left to the lexer rather than coloured as the declaration.
            if (offset_of(clang_getCursorLocation(c)) != offset) continue;
            kind = kind_of(c, mods, &nmods);
            mods[nmods++] = 'D';
            if (clang_getCursorAvailability(c) == CXAvailability_Deprecated) mods[nmods++] = 'X';
        } else {
            CXCursor target = clang_getCursorReferenced(c);
            if (k == CXCursor_OverloadedDeclRef && clang_getNumOverloadedDecls(c) > 0)
                target = clang_getOverloadedDecl(c, 0);
            if (clang_Cursor_isNull(target)) continue;
            kind = kind_of(target, mods, &nmods);
            if (clang_getCursorAvailability(target) == CXAvailability_Deprecated) mods[nmods++] = 'X';
        }
        if (!kind) continue;

        CXString spelling = clang_getTokenSpelling(u->tu, tokens[i]);
        size_t len = strlen(clang_getCString(spelling));
        clang_disposeString(spelling);
        mods[nmods] = 0;
        int written = snprintf(line, sizeof line, "%u %zu %s %s\n", offset, len, kind, nmods ? mods : "-");
        if (written > 0) buf_put(out, line, (size_t) written);
    }
    free(cursors);
    clang_disposeTokens(u->tu, tokens, n);
}

// ---------------------------------------------------------------------------------------------------
// Framing.
// ---------------------------------------------------------------------------------------------------

static int read_line(char *buf, size_t cap) {
    if (!fgets(buf, (int) cap, stdin)) return 0;
    size_t n = strlen(buf);
    if (n == 0 || buf[n - 1] != '\n') return 0; // a header longer than any we send: the stream is broken
    buf[n - 1] = 0;
    return 1;
}

static char *read_blob(size_t *len_out) {
    char header[64];
    if (!read_line(header, sizeof header)) return NULL;
    char *end;
    unsigned long len = strtoul(header, &end, 10);
    if (*end || len > MAX_BLOB) return NULL;
    char *data = malloc(len + 1);
    if (!data) return NULL;
    if (len && fread(data, 1, len, stdin) != len) { free(data); return NULL; }
    data[len] = 0;
    *len_out = len;
    return data;
}

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void respond(const char *id, const char *status, const char *payload, size_t len) {
    fprintf(stdout, "%s %s %zu\n", id, status, len);
    if (len) fwrite(payload, 1, len, stdout);
    fflush(stdout);
}

int main(void) {
    // In-memory preambles: no temporary files to write, read back and clean up, and nothing left behind in
    // storage the plugin would have to manage. The cost is memory, bounded by MAX_UNITS.
    CXIndexOptions opts;
    memset(&opts, 0, sizeof opts);
    opts.Size = sizeof opts;
    opts.StorePreamblesInMemory = 1;
    g_index = clang_createIndexWithOptions(&opts);
    if (!g_index) g_index = clang_createIndex(0, 0);
    if (!g_index) { fputs("ndkclangd: could not create an index\n", stderr); return 2; }

    // NDKCLANGD_TIMING=1 logs each request's time to stderr: what a slow popup spent inside clang, as opposed
    // to on the way there and back.
    const int timing = getenv("NDKCLANGD_TIMING") != NULL;

    char header[256];
    char *blobs[MAX_BLOBS];
    size_t lens[MAX_BLOBS];

    while (read_line(header, sizeof header)) {
        char cmd[16], id[32];
        unsigned line = 0, col = 0;
        int nblobs = 0;
        if (sscanf(header, "%15s %31s %u %u %d", cmd, id, &line, &col, &nblobs) != 5
            || nblobs < 0 || nblobs > MAX_BLOBS) {
            fputs("ndkclangd: malformed request header\n", stderr);
            return 4;
        }
        int ok = 1;
        for (int i = 0; i < nblobs; i++) {
            blobs[i] = read_blob(&lens[i]);
            if (!blobs[i]) { nblobs = i; ok = 0; break; }
        }
        if (!ok) {
            for (int i = 0; i < nblobs; i++) free(blobs[i]);
            fputs("ndkclangd: malformed request body\n", stderr);
            return 4;
        }

        long started = timing ? now_ms() : 0;
        Buf out = {0};
        buf_puts(&out, "");
        const char *status = "ok";

        if (strcmp(cmd, "ping") == 0) {
            buf_puts(&out, "pong");
        } else if (strcmp(cmd, "forget") == 0 && nblobs >= 1) {
            Unit *u = unit_find(blobs[0]);
            if (u) unit_drop(u);
        } else if ((strcmp(cmd, "check") == 0 || strcmp(cmd, "complete") == 0 || strcmp(cmd, "tokens") == 0)
                   && nblobs >= 2) {
            const char *err = NULL;
            int is_check = strcmp(cmd, "check") == 0;
            int is_tokens = strcmp(cmd, "tokens") == 0;
            Unit *u = unit_for(blobs[0], blobs[1], lens[1], (const char *const *) &blobs[2], nblobs - 2,
                               is_check || is_tokens, &err);
            if (!u) {
                status = "error";
                buf_puts(&out, err);
            } else if (is_check) {
                answer_check(&out, u);
            } else if (is_tokens) {
                answer_tokens(&out, u, blobs[0]);
            } else {
                answer_complete(&out, u, blobs[0], blobs[1], lens[1], line, col);
            }
        } else {
            status = "error";
            buf_puts(&out, "unknown request");
        }

        respond(id, status, out.data, out.len);
        if (timing) {
            fprintf(stderr, "ndkclangd: %s %s %ld ms\n", cmd, nblobs ? blobs[0] : "", now_ms() - started);
            fflush(stderr);
        }
        free(out.data);
        for (int i = 0; i < nblobs; i++) free(blobs[i]);
    }

    for (int i = 0; i < MAX_UNITS; i++) if (g_units[i].path) unit_drop(&g_units[i]);
    clang_disposeIndex(g_index);
    return 0;
}
