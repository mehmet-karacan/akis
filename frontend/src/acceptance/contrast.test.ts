import { describe, expect, it } from 'vitest'

function luminance(hex: string) { const values = hex.match(/[a-f\d]{2}/gi)!.map((part) => Number.parseInt(part, 16) / 255).map((value) => value <= .03928 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4); return .2126 * values[0]! + .7152 * values[1]! + .0722 * values[2]! }
function ratio(foreground: string, background: string) { const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a); return (values[0]! + .05) / (values[1]! + .05) }

describe('theme contrast contracts', () => {
  it.each([
    ['light body', '#0f172a', '#f8fafc', 4.5], ['light muted', '#475569', '#ffffff', 4.5], ['light primary', '#ffffff', '#0f766e', 4.5],
    ['dark body', '#f8fafc', '#0b1220', 4.5], ['dark muted', '#cbd5e1', '#111827', 4.5], ['dark primary', '#071312', '#5eead4', 4.5],
    ['light danger badge', '#991b1b', '#fee2e2', 4.5], ['dark danger badge', '#fca5a5', '#7f1d1d', 4.5],
  ])('%s meets WCAG AA', (_name, foreground, background, minimum) => { expect(ratio(foreground as string, background as string)).toBeGreaterThanOrEqual(minimum as number) })
})
