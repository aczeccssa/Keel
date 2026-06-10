import type { ReactNode } from 'react';

export interface DataTableProps {
  headers: string[];
  rows: Array<Array<ReactNode>>;
  emptyText?: string;
  caption?: string;
  density?: 'compact' | 'normal';
  rowActions?: (rowIndex: number) => ReactNode;
}

export function DataTable({ headers, rows, emptyText = 'No data yet.', caption, density = 'normal', rowActions }: DataTableProps) {
  if (rows.length === 0) return <div className="keel-table-empty">{emptyText}</div>;
  const visibleHeaders = rowActions ? [...headers, 'Actions'] : headers;
  return (
    <div className="keel-table-wrap">
      <table className={`keel-data-table keel-data-table-${density}`}>
        {caption ? <caption>{caption}</caption> : null}
        <thead><tr>{visibleHeaders.map((header) => <th key={header}>{header}</th>)}</tr></thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}
              {rowActions ? <td className="keel-row-actions">{rowActions(rowIndex)}</td> : null}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
