import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function RateLimitsPanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="Rate Limits" load={() => api.rateLimitRules()} columns={['ID', 'Scope', 'Limit']} emptyText="No rate limit rules yet." rows={(data) => ((data as { rules?: Array<{ ruleId?: string; scope?: string; limit?: number }> }).rules ?? []).map((row) => [row.ruleId ?? '—', row.scope ?? '—', row.limit ?? 0])} />;
}
