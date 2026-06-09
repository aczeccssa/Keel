import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function CustomersPanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="Customers" load={() => api.customers()} columns={['Email', 'Credits', 'Status']} emptyText="No customers yet." rows={(data) => ((data as { customers?: Array<{ email?: string; credits?: number; status?: string }> }).customers ?? []).map((row) => [row.email ?? '—', row.credits ?? 0, row.status ?? '—'])} />;
}
