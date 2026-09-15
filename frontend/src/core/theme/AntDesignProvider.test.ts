import { describe, expect, it } from 'vitest'
import { theme } from 'antd'
import { createAntTheme } from './AntDesignProvider'

function luminance(hex: string) {
  const c = hex.slice(1).match(/../g)!.map(v => parseInt(v, 16) / 255)
    .map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
  return c[0]! * .2126 + c[1]! * .7152 + c[2]! * .0722
}
describe('Ant Design theme contract', () => {
  for (const dark of [false, true]) it(`keeps readable body text and common sizing in ${dark ? 'dark' : 'light'} mode`, () => {
    const tokens = theme.getDesignToken(createAntTheme(dark))
    const bg = luminance(tokens.colorBgContainer)
    for (const color of [tokens.colorText, tokens.colorTextSecondary]) {
      const fg = luminance(color)
      expect((Math.max(bg, fg) + .05) / (Math.min(bg, fg) + .05)).toBeGreaterThanOrEqual(4.5)
    }
    expect(tokens.controlHeight).toBe(34)
    expect(tokens.fontSize).toBe(14)
    expect(tokens.colorBgContainer).not.toBe(tokens.colorBgLayout)
  })
})
