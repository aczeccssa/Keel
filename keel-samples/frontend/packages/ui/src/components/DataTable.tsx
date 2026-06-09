export interface DataTableProps {
  headers: string[];
  rows: Array<Array<string | number>>;
  emptyText?: string;
}

export function DataTable({ headers, rows, emptyText = 'No data yet.' }: DataTableProps) {
  if (rows.length === 0) return <div className="keel-table-empty">{emptyText}</div>;
  return (
    <table className="keel-data-table">
      <thead><tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr></thead>
      <tbody>{rows.map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}</tr>)}</tbody>
    </table>
  );
}
