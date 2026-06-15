import type { ReactNode } from 'react';

export interface DataTableColumn<Row = unknown> {
  key: string;
  header: string;
  render?: (row: Row, index: number) => ReactNode;
  align?: 'left' | 'right' | 'center';
  width?: string;
  mono?: boolean;
}

export interface DataTableProps<Row = unknown> {
  columns: DataTableColumn<Row>[];
  rows: Row[];
  emptyText?: string;
  getRowKey?: (row: Row, i: number) => string;
  maxHeight?: string;
  actionsColumn?: (row: Row) => ReactNode;
  actionsHeader?: string;
}

export function DataTable<Row = unknown>({
  columns,
  rows,
  emptyText = 'No data yet.',
  getRowKey,
  maxHeight,
  actionsColumn,
  actionsHeader = 'Actions'
}: DataTableProps<Row>) {
  if (rows.length === 0) {
    return <div className="keel-table-empty">{emptyText}</div>;
  }

  const table = (
    <table className="keel-data-table">
      <thead>
        <tr>
          {columns.map((col) => (
            <th
              key={col.key}
              className={[
                col.align ? `is-${col.align}` : '',
                col.mono ? 'is-mono' : ''
              ]
                .filter(Boolean)
                .join(' ')}
              style={col.width ? { width: col.width } : undefined}
            >
              {col.header}
            </th>
          ))}
          {actionsColumn ? <th className="is-actions">{actionsHeader}</th> : null}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, rowIndex) => (
          <tr key={getRowKey ? getRowKey(row, rowIndex) : rowIndex}>
            {columns.map((col) => (
              <td
                key={col.key}
                className={[
                  col.align ? `is-${col.align}` : '',
                  col.mono ? 'is-mono' : ''
                ]
                  .filter(Boolean)
                  .join(' ')}
              >
                {col.render ? col.render(row, rowIndex) : ((row as Record<string, unknown>)[col.key] as ReactNode)}
              </td>
            ))}
            {actionsColumn ? (
              <td className="is-actions">
                <div className="keel-table-actions">{actionsColumn(row)}</div>
              </td>
            ) : null}
          </tr>
        ))}
      </tbody>
    </table>
  );

  if (maxHeight) {
    return (
      <div className="keel-table-scroll" style={{ maxHeight }}>
        {table}
      </div>
    );
  }
  return <div className="keel-table-scroll">{table}</div>;
}
