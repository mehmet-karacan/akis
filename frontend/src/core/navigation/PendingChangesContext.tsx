import { createContext, useContext } from 'react'

export interface PendingChanges {
  save: () => Promise<boolean>
}

export interface PendingChangesContextValue {
  pendingChanges: PendingChanges | null
  setPendingChanges: (value: PendingChanges | null) => void
}

export const PendingChangesContext = createContext<PendingChangesContextValue>({ pendingChanges: null, setPendingChanges: () => undefined })
export const usePendingChanges = () => useContext(PendingChangesContext)
