// The Playground's editor: CodeMirror 6 with Kotlin highlighting, whose completions and diagnostics are answered
// by the Kotlin engine in the worker (the same completion and checks the IDE runs), through the `api` it is given.
import { EditorState } from '@codemirror/state';
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { StreamLanguage, syntaxHighlighting, defaultHighlightStyle, indentOnInput, bracketMatching } from '@codemirror/language';
import { kotlin } from '@codemirror/legacy-modes/mode/clike';
import { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap, startCompletion } from '@codemirror/autocomplete';
import { linter, lintGutter, lintKeymap } from '@codemirror/lint';
import { oneDark } from '@codemirror/theme-one-dark';

// The engine's kinds -> CodeMirror's icon classes.
const KINDS = {
  CLASS: 'class', INTERFACE: 'interface', ENUM: 'enum', ANNOTATION_TYPE: 'class', RECORD: 'class',
  METHOD: 'function', CONSTRUCTOR: 'class', FIELD: 'property', ENUM_CONSTANT: 'constant',
  VARIABLE: 'variable', PARAMETER: 'variable', TYPE_PARAMETER: 'type', PACKAGE: 'namespace',
  KEYWORD: 'keyword', SNIPPET: 'text', WORD: 'text',
};
const SEVERITY = { ERROR: 'error', WARNING: 'warning', INFO: 'info', HINT: 'info' };

/** Accept [item]: the insertion, plus any edits beyond it (an auto-import), as ONE change so undo is one step. */
function applyItem(item) {
  return (view, _completion, from, to) => {
    const changes = [{ from, to, insert: item.insert }];
    let shift = 0; // how much the extra edits ahead of the caret moved it
    for (const [f, t, text] of item.edits) {
      changes.push({ from: f, to: t, insert: text });
      if (t <= from) shift += text.length - (t - f);
    }
    view.dispatch({ changes, selection: { anchor: from + shift + item.insert.length }, userEvent: 'input.complete', scrollIntoView: true });
  };
}

export function createEditor({ parent, doc, api, onRun, onTiming }) {
  const completionSource = async (ctx) => {
    const word = ctx.matchBefore(/[\w$]*/);
    const afterDot = word && word.from > 0 && ctx.state.doc.sliceString(word.from - 1, word.from) === '.';
    // Do not pop a list over whitespace the user did not ask about; a dot or a typed word or Ctrl-Space does.
    if (!ctx.explicit && !afterDot && (!word || word.text.length === 0)) return null;
    const started = performance.now();
    const r = await api.complete(ctx.state.doc.toString(), ctx.pos);
    if (ctx.aborted || !r || !r.items.length) return null;
    onTiming?.(`completion ${Math.round(performance.now() - started)} ms (${r.millis} ms in the engine) · ${r.items.length} items`);
    return {
      from: r.from,
      to: r.to,
      // The engine already filtered and ranked for what is typed; CodeMirror must neither re-filter nor reorder.
      filter: false,
      options: r.items.map((it, i) => ({
        label: it.label,
        detail: it.detail ?? undefined,
        type: KINDS[it.kind] ?? 'text',
        boost: -i,
        apply: applyItem(it),
        // The origin (a package, or the type a member comes from) shown as the row's note.
        info: it.container ? () => { const d = document.createElement('div'); d.textContent = it.container; return d; } : undefined,
      })),
    };
  };

  const diagnostics = linter(async (view) => {
    const text = view.state.doc.toString();
    const found = await api.diagnose(text);
    const length = text.length;
    return found.map((d) => {
      const from = Math.min(d.from, length);
      // An empty range draws nothing; widen it to the character it sits on so the problem is visible.
      const to = Math.min(Math.max(d.to, from + 1), length);
      return { from, to: Math.max(to, from), severity: SEVERITY[d.severity] ?? 'info', message: d.message, source: d.code ?? undefined };
    });
  }, { delay: 400 });

  const dark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? true;
  return new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [
        lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(), drawSelection(),
        history(), indentOnInput(), bracketMatching(), closeBrackets(),
        StreamLanguage.define(kotlin),
        dark ? oneDark : syntaxHighlighting(defaultHighlightStyle),
        autocompletion({ override: [completionSource], activateOnTyping: true, icons: true }),
        diagnostics, lintGutter(),
        keymap.of([
          { key: 'Mod-Enter', run: () => { onRun?.(); return true; } },
          { key: 'Ctrl-Space', run: startCompletion },
          ...closeBracketsKeymap, ...completionKeymap, ...lintKeymap, ...historyKeymap, ...defaultKeymap, indentWithTab,
        ]),
        EditorView.theme({
          '&': { height: '100%', fontSize: '13.5px' },
          '.cm-scroller': { fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", lineHeight: '1.55' },
        }),
      ],
    }),
  });
}
