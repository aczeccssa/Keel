import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function PricingPanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="Pricing" load={() => api.pricing()} columns={['Model', 'Input', 'Output']} emptyText="No pricing rows yet." rows={(data) => ((data as { pricing?: Array<{ model?: string; inputCostPerMTok?: number; outputCostPerMTok?: number }> }).pricing ?? []).map((row) => [row.model ?? '—', row.inputCostPerMTok ?? 0, row.outputCostPerMTok ?? 0])} />;
}
