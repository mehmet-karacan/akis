import { useCallback, useEffect, useState } from 'react'

export function useRemoteData<T>(loader: () => Promise<T>, dependencies: readonly unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setData(await loader())
    } catch (nextError) {
      setError(nextError)
    } finally {
      setLoading(false)
    }
  }, dependencies)

  useEffect(() => {
    void reload()
  }, [reload])

  return { data, error, loading, reload, setData }
}
