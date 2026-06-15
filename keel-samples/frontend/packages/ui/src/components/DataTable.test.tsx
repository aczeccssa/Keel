import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DataTable, type DataTableColumn } from './DataTable';

interface Row {
  name: string;
}

describe('DataTable', () => {
  it('wraps action content in a dedicated actions container', () => {
    const columns: DataTableColumn<Row>[] = [{ key: 'name', header: 'Name' }];

    render(
      <DataTable
        columns={columns}
        rows={[{ name: 'Alpha' }]}
        actionsColumn={() => <button type="button">Edit</button>}
      />
    );

    expect(screen.getByRole('button', { name: 'Edit' }).closest('.keel-table-actions')).not.toBeNull();
  });
});
