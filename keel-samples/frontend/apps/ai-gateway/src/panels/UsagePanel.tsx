import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function UsagePanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="Usage" load={() => api.usageRecords()} columns={['ID', 'Model', 'Cost']} emptyText="No usage records yet." rows={(data) => ((data as { records?: Array<{ requestId?: string; model?: string; totalCostUsd?: number }> }).records ?? []).map((row) => [row.requestId ?? '—', row.model ?? '—', row.totalCostUsd ?? 0])} />;
}
