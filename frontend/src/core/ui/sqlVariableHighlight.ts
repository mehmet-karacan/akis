import { Decoration, ViewPlugin, type EditorView, type ViewUpdate } from '@codemirror/view'

export function projectVariableRanges(sql: string) {
  const tokens = /--[^\n]*|\/\*[\s\S]*?\*\/|q'\[[\s\S]*?\]'|q'\{[\s\S]*?\}'|q'\([\s\S]*?\)'|q'<[\s\S]*?>'|q'([^\w\s])[\s\S]*?\1'|'(?:''|[^'])*'|"(?:""|[^"])*"|(?<![\w"$#])@[A-Za-z_][A-Za-z0-9_]*/gi
  return [...sql.matchAll(tokens)].filter(match => match[0].startsWith('@')).map(match => ({ from: match.index, to: match.index + match[0].length }))
}
function marks(view: EditorView) {
  const mark = Decoration.mark({ class: 'cm-project-variable' })
  return Decoration.set(projectVariableRanges(view.state.doc.toString()).map(range => mark.range(range.from, range.to)))
}
export const projectVariableHighlight = ViewPlugin.fromClass(class {
  decorations
  constructor(view: EditorView) { this.decorations = marks(view) }
  update(update: ViewUpdate) { if (update.docChanged) this.decorations = marks(update.view) }
}, { decorations: value => value.decorations })
