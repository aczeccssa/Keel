import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function ProvidersPanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="Channels" load={() => api.channels()} columns={['Name', 'Protocol', 'Base URL']} emptyText="No channels yet." rows={(data) => ((data as { channels?: Array<{ name?: string; protocol?: string; baseUrl?: string }> }).channels ?? []).map((row) => [row.name ?? '—', row.protocol ?? '—', row.baseUrl ?? '—'])} />;
}
