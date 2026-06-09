import { Card, ChartPlaceholder, StatGrid } from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

export function DashboardPanel({ api }: { api: AiGatewayApi }) {
  return <Card><h1>Dashboard</h1><StatGrid items={[{ label: 'Requests', value: '—', hint: 'loaded from usage API' }]} /><ChartPlaceholder label="Request Volume" /></Card>;
}
