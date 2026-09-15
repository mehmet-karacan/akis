// One-time mechanical JSX migration. Run from the repository root.
import fs from 'node:fs'
import path from 'node:path'
import ts from '../frontend/node_modules/typescript/lib/typescript.js'
const root = path.resolve('frontend/src')
function visitFiles(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name)
    if (entry.isDirectory()) visitFiles(file)
    else if (file.endsWith('.tsx') && !/\.(test|spec)\.tsx$/.test(file)) migrate(file)
  }
}
function migrate(file) {
  if (!file.includes(`${path.sep}features${path.sep}`) && !file.includes(`${path.sep}app${path.sep}`)) return
  const source = fs.readFileSync(file, 'utf8')
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  const edits = []
  const replace = (node, text) => edits.push({ start: node.getStart(ast), end: node.end, text })
  let buttons = false, inputs = false, selects = false, checks = false, radios = false, tables = false, tabs = false, disclosures = false, suggestions = false
  let suggestionSource
  function findSuggestions(node) {
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText(ast) === 'datalist') {
      const expr = node.children.find(ts.isJsxExpression)?.expression
      if (expr && ts.isCallExpression(expr) && ts.isPropertyAccessExpression(expr.expression)) {
        suggestionSource = expr.expression.expression.getText(ast)
        replace(node, '')
      }
    }
    ts.forEachChild(node, findSuggestions)
  }
  findSuggestions(ast)
  function walk(node) {
    if (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) {
      const tag = node.tagName.getText(ast)
      const attributes = node.attributes.properties
      const attr = (name) => attributes.find(a => ts.isJsxAttribute(a) && a.name.getText(ast) === name)
      if (tag === 'AntActionButton' && attr('tone')?.initializer?.text === 'ghost' && attr('type')?.initializer?.text === 'submit') {
        replace(attr('tone'), 'tone="primary"')
      }
      if (tag === 'AntInput' && attr('list') && suggestionSource) {
        replace(node.tagName, `SuggestionInput suggestions={${suggestionSource}}`)
        replace(attr('list'), '')
        suggestions = true
      }
      if (tag === 'div' && attr('role')?.initializer?.text === 'tablist') {
        replace(node.tagName, 'TabBar')
        if (ts.isJsxOpeningElement(node)) replace(node.parent.closingElement.tagName, 'TabBar')
        tabs = true
      }
      if (tag === 'details') {
        replace(node.tagName, 'Disclosure')
        if (ts.isJsxOpeningElement(node)) replace(node.parent.closingElement.tagName, 'Disclosure')
        disclosures = true
      }
      if (tag === 'table') {
        replace(node.tagName, 'DataGrid')
        if (ts.isJsxOpeningElement(node)) replace(node.parent.closingElement.tagName, 'DataGrid')
        tables = true
      }
      if (tag === 'select') {
        replace(node.tagName, 'FormSelect')
        if (ts.isJsxOpeningElement(node)) replace(node.parent.closingElement.tagName, 'FormSelect')
        selects = true
      }
      if (tag === 'input' && ts.isStringLiteral(attr('type')?.initializer)) {
        const kind = attr('type').initializer.text
        if (kind === 'checkbox' || kind === 'radio') {
          replace(node.tagName, kind === 'checkbox' ? 'AntCheckbox' : 'AntRadio')
          replace(attr('type'), '')
          if (kind === 'checkbox') checks = true
          else radios = true
        }
      }
      if (tag === 'AntActionButton' && !attr('type')) replace(node.tagName, 'AntActionButton type="submit"')
      if (tag === 'button') {
        const cls = attr('className')
        if (cls?.initializer && ts.isStringLiteral(cls.initializer)) {
          const classes = cls.initializer.text.split(/\s+/)
          if (classes.some(c => ['button', 'definition-button', 'bundle-button'].includes(c))) {
            const tones = ['primary', 'secondary', 'danger', 'ghost']
            const tone = tones.find(t => classes.some(c => c === t || c === `definition-button--${t}` || c === `bundle-button-${t}`)) ?? 'secondary'
            const remaining = classes.filter(c => !['button', 'definition-button', 'bundle-button', 'definition-button--quiet'].includes(c) && !tones.some(t => c === t || c === `definition-button--${t}` || c === `bundle-button-${t}`)).join(' ')
            replace(node.tagName, 'AntActionButton')
            replace(cls, `tone="${tone}"${attr('type') ? '' : ' type="submit"'}${remaining ? ` className="${remaining}"` : ''}`)
            if (ts.isJsxOpeningElement(node)) replace(node.parent.closingElement.tagName, 'AntActionButton')
            buttons = true
          }
        }
        if (!edits.some(edit => edit.start === node.tagName.getStart(ast))) {
          replace(node.tagName, `AntActionButton${attr('type') ? '' : ' type="submit"'} tone="ghost"`)
          if (ts.isJsxOpeningElement(node)) replace(node.parent.closingElement.tagName, 'AntActionButton')
          buttons = true
        }
      }
      if ((tag === 'input' || tag === 'textarea') && !attr('ref') && !attributes.some(ts.isJsxSpreadAttribute)) {
        const type = attr('type')?.initializer
        const supported = !type || (ts.isStringLiteral(type) && ['text', 'password', 'search', 'email', 'url', 'tel', 'number', 'date', 'datetime-local'].includes(type.text))
        if (supported) {
          const name = tag === 'textarea' ? 'AntInput.TextArea' : 'AntInput'
          replace(node.tagName, name)
          if (ts.isJsxOpeningElement(node)) replace(node.parent.closingElement.tagName, name)
          inputs = true
        }
      }
    }
    ts.forEachChild(node, walk)
  }
  walk(ast)
  if (!edits.length) return
  let result = source
  for (const edit of edits.sort((a,b) => b.start-a.start)) result = result.slice(0, edit.start)+edit.text+result.slice(edit.end)
  const relative = path.relative(path.dirname(file), path.join(root, 'core/ui/Button')).replaceAll('\\', '/')
  if (buttons && !source.includes('Button as AntActionButton')) result = `import { Button as AntActionButton } from '${relative.startsWith('.') ? relative : './'+relative}'\n` + result
  if (inputs && !source.includes('Input as AntInput')) result = "import { Input as AntInput } from 'antd'\n" + result
  if (selects) {
    const selectPath = path.relative(path.dirname(file), path.join(root, 'core/ui/Select')).replaceAll('\\', '/')
    result = `import { Select as FormSelect } from '${selectPath.startsWith('.') ? selectPath : './'+selectPath}'\n` + result
  }
  if (checks) result = "import { Checkbox as AntCheckbox } from 'antd'\n" + result
  if (radios) result = "import { Radio as AntRadio } from 'antd'\n" + result
  if (tables) {
    const tablePath = path.relative(path.dirname(file), path.join(root, 'core/ui/DataGrid')).replaceAll('\\', '/')
    result = `import { DataGrid } from '${tablePath.startsWith('.') ? tablePath : './'+tablePath}'\n` + result
  }
  for (const [used, name] of [[tabs, 'TabBar'], [disclosures, 'Disclosure'], [suggestions, 'SuggestionInput']]) {
    if (used) {
      const modulePath = path.relative(path.dirname(file), path.join(root, 'core/ui', name)).replaceAll('\\', '/')
      result = `import { ${name} } from '${modulePath.startsWith('.') ? modulePath : './'+modulePath}'\n` + result
    }
  }
  if (suggestions && !result.includes('<AntInput')) result = result.replace(/import \{ Input as AntInput \} from 'antd'\r?\n/, '')
  fs.writeFileSync(file, result)
  console.log(path.relative(root, file))
}
visitFiles(root)
