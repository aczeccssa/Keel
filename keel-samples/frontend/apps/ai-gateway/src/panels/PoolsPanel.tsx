import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function PoolsPanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="Pools" load={() => api.pools()} columns={['Chain', 'Level', 'Status']} emptyText="No pool data yet." rows={(data) => ((data as { chains?: Array<{ chainId?: string; levels?: unknown[] }> }).chains ?? []).map((row) => [row.chainId ?? '—', String(row.levels?.length ?? 0), 'active'])} />;
}
