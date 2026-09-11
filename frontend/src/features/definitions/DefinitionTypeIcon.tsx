import {
  BookOpen,
  Braces,
  FileCode2,
  ListOrdered,
  Package,
  Repeat2,
  Route,
  SquareFunction,
  Variable,
  Workflow,
  type LucideIcon,
} from 'lucide-react'
import type { DefinitionType } from './types'

const TYPE_ICONS: Record<DefinitionType, LucideIcon> = {
  MAPPING: Workflow,
  REUSABLE_MAPPING: Repeat2,
  PACKAGE: Package,
  PROCEDURE: FileCode2,
  VARIABLE: Variable,
  SEQUENCE: ListOrdered,
  USER_FUNCTION: SquareFunction,
  KNOWLEDGE_MODULE: BookOpen,
  LOAD_PLAN: Route,
}

export function DefinitionTypeIcon({ type, size = 15 }: { type: DefinitionType; size?: number }) {
  const Icon = TYPE_ICONS[type] ?? Braces
  return <Icon size={size} aria-hidden="true" data-definition-type={type} />
}
