/** Transform placeholders only in executable SQL, never in literals or comments. */
function mapSql(sql: string, replace: (token: string) => string) {
  return sql.replace(/--[^\n]*|\/\*[\s\S]*?\*\/|q'\[[\s\S]*?\]'|q'\{[\s\S]*?\}'|q'\([\s\S]*?\)'|q'<[\s\S]*?>'|q'([^\w\s])[\s\S]*?\1'|'(?:''|[^'])*'|"(?:""|[^"])*"|\$\{[^}]*\}?|(?<![\w"$#])@[A-Za-z_][A-Za-z0-9_]*|:[A-Za-z_][A-Za-z0-9_]*/gi, token => token.startsWith('${') || token.startsWith(':') || token.startsWith('@') ? replace(token) : token)
}
export function displayProjectSql(sql: string, names: string[]) {
  const known = new Set(names)
  return mapSql(sql, token => token.startsWith(':') && known.has(token.slice(1).toUpperCase()) ? '@' + token.slice(1).toUpperCase() : token)
}
export function compileProjectSql(sql: string, names: string[]) {
  const known = new Set(names)
  const used = new Set<string>()
  const rowBinds = new Set<string>()
  const command = mapSql(sql, token => {
    if (token.startsWith(':')) { rowBinds.add(token.slice(1).toUpperCase()); return token }
    if (token.startsWith('${') && !token.endsWith('}')) throw new Error('Incomplete project variable reference')
    const name = (token.startsWith('@') ? token.slice(1) : token.slice(2, -1)).toUpperCase()
    if (!/^[A-Z_][A-Z0-9_]*$/.test(name) || !known.has(name)) throw new Error(`Unknown project variable: ${name}`)
    used.add(name)
    return ':' + name
  })
  for (const name of used) if (rowBinds.has(name)) throw new Error(`Project variable and row bind have the same name: ${name}`)
  return { command, names: [...used] }
}
