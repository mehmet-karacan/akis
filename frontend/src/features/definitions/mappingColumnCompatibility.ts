const typeFamily = (type?: string) => type?.toUpperCase().match(/CHAR|STRING|CLOB/) ? 'TEXT' : type?.toUpperCase().match(/NUMBER|DECIMAL|INTEGER|FLOAT|DOUBLE/) ? 'NUMBER' : type?.toUpperCase().match(/DATE|TIME/) ? 'TEMPORAL' : type?.toUpperCase()

/** Design-time guidance only. Published snapshots and JDBC values remain the
 * runtime authority; cross-family conversions require an explicit expression.
 */
export const typesCompatible = (source?: string, target?: string) => !source || !target || typeFamily(source) === typeFamily(target)

