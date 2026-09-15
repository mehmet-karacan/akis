import { Children, isValidElement, type HTMLAttributes, type ReactNode } from 'react'
import { Tabs } from 'antd'

/** Domain tabs retain their state; Ant owns selection visuals and keyboard navigation. */
export function TabBar({ children, className, ...props }: HTMLAttributes<HTMLDivElement>) {
  const nodes = Children.toArray(children)
  const tabs: { key: string; label: ReactNode; disabled?: boolean; selected: boolean; activate?: () => void }[] = []
  const extra: ReactNode[] = []
  for (const node of nodes) {
    if (isValidElement<{ role?: string; children?: ReactNode; disabled?: boolean; id?: string; 'aria-selected'?: boolean; onClick?: () => void }>(node) && node.props.role === 'tab') {
      tabs.push({ key: String(node.key ?? tabs.length), label: <span id={node.props.id}>{node.props.children}</span>, disabled: node.props.disabled, selected: Boolean(node.props['aria-selected']), activate: node.props.onClick })
    } else extra.push(node)
  }
  return <Tabs className={className} aria-label={props['aria-label']} activeKey={tabs.find(tab => tab.selected)?.key}
    items={tabs.map(({ key, label, disabled }) => ({ key, label, disabled }))}
    onChange={key => tabs.find(tab => tab.key === key)?.activate?.()} tabBarExtraContent={extra} />
}
