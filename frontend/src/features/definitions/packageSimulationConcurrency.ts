/** Keep network fan-out bounded without changing the order of the resulting report. */
export async function mapPackageStepsBounded<T, R>(
  items: readonly T[], concurrency: number, task: (item: T) => Promise<R>,
): Promise<R[]> {
  if (!Number.isInteger(concurrency) || concurrency < 1) throw new RangeError('concurrency must be positive')
  const results: R[] = new Array(items.length)
  let next = 0
  await Promise.all(Array.from({ length: Math.min(items.length, concurrency) }, async () => {
    while (next < items.length) {
      const index = next++
      results[index] = await task(items[index]!)
    }
  }))
  return results
}
