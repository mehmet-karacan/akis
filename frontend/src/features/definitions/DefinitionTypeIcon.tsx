import {
  BookOpen,
  Braces,
  FileCode2,
  FolderClosed,
  FolderOpen,
  ListOrdered,
  Package,
  Repeat2,
  Route,
  Variable,
  Workflow,
  type LucideIcon,
} from 'lucide-react'
import type { DefinitionType } from './types'
import './definition-icons.css'

const TYPE_ICONS: Record<DefinitionType, LucideIcon> = {
  MAPPING: Workflow,
  REUSABLE_MAPPING: Repeat2,
  PACKAGE: Package,
  PROCEDURE: FileCode2,
  VARIABLE: Variable,
  SEQUENCE: ListOrdered,
  KNOWLEDGE_MODULE: BookOpen,
  LOAD_PLAN: Route,
}

/** Tinted badge with the type's own color; the same mark is used in the tree, catalogs, chips and menus. */
export function DefinitionTypeIcon({ type, size = 15, badge = true }: { type: DefinitionType; size?: number; badge?: boolean }) {
  const Icon = TYPE_ICONS[type] ?? Braces
  const icon = <Icon size={size} aria-hidden="true" data-definition-type={type} />
  return badge ? <span className={`definition-type-icon definition-type-icon--${type.toLowerCase().replaceAll('_', '-')}`} style={{ width: size + 9, height: size + 9 }} aria-hidden="true">{icon}</span> : icon
}

/** Folder mark for the object tree and catalogs; opens with the tree node. */
export function ProjectFolderIcon({ open = false, size = 15 }: { open?: boolean; size?: number }) {
  const Icon = open ? FolderOpen : FolderClosed
  return <span className={`definition-type-icon definition-type-icon--folder${open ? ' is-open' : ''}`} style={{ width: size + 9, height: size + 9 }} aria-hidden="true"><Icon size={size} data-definition-type="FOLDER" /></span>
}
