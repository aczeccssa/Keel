import type { ReactNode } from 'react';

export interface ToolbarProps {
  children: ReactNode;
  align?: 'left' | 'right';
}

export function Toolbar({ children, align = 'left' }: ToolbarProps) {
  return (
    <div className="keel-toolbar" data-align={align}>
      {children}
    </div>
  );
}
