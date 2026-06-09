import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function RedemptionCodesPanel({ api }: { api: AiGatewayApi }) {
  return <DataPanel title="Redemption" load={() => api.redemptionCodes()} columns={['Code', 'Credits', 'Status']} emptyText="No redemption codes yet." rows={(data) => ((data as { codes?: Array<{ code?: string; faceValueCredits?: number; status?: string }> }).codes ?? []).map((row) => [row.code ?? '—', row.faceValueCredits ?? 0, row.status ?? '—'])} />;
}
