import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function GroupsPanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="Groups" load={() => api.groups()} columns={['ID', 'Name', 'Aliases']} emptyText="No groups yet." rows={(data) => ((data as { groups?: Array<{ groupId?: string; name?: string; aliases?: string[] }> }).groups ?? []).map((row) => [row.groupId ?? '—', row.name ?? '—', (row.aliases ?? []).join(', ')])} />;
}
