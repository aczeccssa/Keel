import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function KeysPanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="API Keys" load={() => api.keys()} columns={['Name', 'Owner', 'Prefix']} emptyText="No API keys yet." rows={(data) => ((data as { keys?: Array<{ name?: string; owner?: string; prefix?: string }> }).keys ?? []).map((row) => [row.name ?? '—', row.owner ?? '—', row.prefix ?? '—'])} />;
}
