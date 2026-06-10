import type { ReactNode } from 'react';

export function Toolbar({ title, detail, actions }: { title: string; detail?: string; actions?: ReactNode }) {
  return (
    <div className="keel-toolbar">
      <div>
        <h2>{title}</h2>
        {detail ? <p>{detail}</p> : null}
      </div>
      {actions ? <div className="keel-toolbar-actions">{actions}</div> : null}
    </div>
  );
}
