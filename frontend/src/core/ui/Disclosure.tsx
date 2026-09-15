import { Children, isValidElement, type DetailsHTMLAttributes, type ReactNode } from 'react'
import { Collapse } from 'antd'

export function Disclosure({ children, className, open }: DetailsHTMLAttributes<HTMLDetailsElement>) {
  const parts = Children.toArray(children)
  const heading = parts.find(child => isValidElement(child) && child.type === 'summary')
  return <Collapse className={className} defaultActiveKey={open ? ['content'] : []} items={[{
    key: 'content', label: isValidElement<{ children: ReactNode }>(heading) ? heading.props.children : '',
    children: parts.filter(child => child !== heading),
  }]} />
}
