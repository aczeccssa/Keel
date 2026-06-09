import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function UsersPanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="Users" load={() => api.users()} columns={['Email', 'Role', 'Status']} emptyText="No users yet." rows={(data) => ((data as { users?: Array<{ email?: string; role?: string; status?: string }> }).users ?? []).map((row) => [row.email ?? '—', row.role ?? '—', row.status ?? '—'])} />;
}
