// Remove the replaced visual system while retaining domain-editor geometry.
import fs from 'node:fs'
import path from 'node:path'
import postcss from '../frontend/node_modules/postcss/lib/postcss.mjs'
const root = path.resolve('frontend/src')
const appearance = /^(--|color$|background|border|font|letter-spacing|text-shadow|box-shadow|outline|fill$|stroke$|opacity$|filter$|backdrop-filter|scrollbar-color)/
function visit(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name)
    if (entry.isDirectory()) visit(file)
    else if (file.endsWith('.css') && !file.endsWith('ant-design.css')) {
      const tree = postcss.parse(fs.readFileSync(file, 'utf8'))
      tree.walkDecls(decl => { if (appearance.test(decl.prop)) decl.remove() })
      tree.walkRules(rule => {
        // Native control styling no longer belongs to feature screens.
        if (/(^|[\s>+,])(?:input|select|textarea|button|table|thead|tbody|td|th)(?=[\s.:#[>+,]|$)/.test(rule.selector)) rule.remove()
        else if (!rule.nodes.length) rule.remove()
      })
      tree.walkAtRules(rule => { if (rule.name === 'layer') rule.params = 'akis-layout'; if (rule.nodes && !rule.nodes.length) rule.remove() })
      tree.walkComments(comment => comment.remove())
      fs.writeFileSync(file, '/* Domain layout only. Visual tokens and controls are owned by Ant Design. */\n' + tree.toString())
    }
  }
}
visit(root)
