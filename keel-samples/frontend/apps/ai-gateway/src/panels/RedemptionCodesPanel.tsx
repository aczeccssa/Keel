import { useEffect, useState } from 'react';
import {
  Button,
  Chip,
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  PageHeader
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface RedemptionCode {
  code?: string;
  faceValueCredits?: number;
  status?: string;
  redeemedBy?: string;
  redeemedAt?: string;
}

function statusTone(s: string | undefined): 'ok' | 'warn' | 'danger' | 'muted' {
  switch ((s ?? '').toLowerCase()) {
    case 'active':
    case 'unused':
      return 'ok';
    case 'pending':
    case 'partially':
      return 'warn';
    case 'expired':
    case 'revoked':
      return 'danger';
    default:
      return 'muted';
  }
}

export function RedemptionCodesPanel({ api }: { api: AiGatewayApi }) {
  const [rows, setRows] = useState<RedemptionCode[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.redemptionCodes()
      .then((data) => {
        if (cancelled) return;
        setRows(((data as { codes?: RedemptionCode[] }).codes ?? []) as RedemptionCode[]);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load redemption codes');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const columns: DataTableColumn<RedemptionCode>[] = [
    { key: 'code', header: 'Code', mono: true },
    {
      key: 'faceValueCredits',
      header: 'Face value',
      align: 'right',
      mono: true,
      render: (r) => (r.faceValueCredits ?? 0).toLocaleString()
    },
    {
      key: 'status',
      header: 'Status',
      render: (r) => <Chip tone={statusTone(r.status)}>{r.status ?? '—'}</Chip>
    },
    { key: 'redeemedBy', header: 'Redeemed by' },
    {
      key: 'redeemedAt',
      header: 'Redeemed at',
      render: (r) => (r.redeemedAt ? r.redeemedAt.replace('T', ' ').slice(0, 19) : '—')
    }
  ];

  return (
    <>
      <PageHeader
        title="Redemption"
        description="Credit top-up codes."
        actions={
          <Button variant="primary" size="sm" disabled title="Backend not yet exposed">
            + Generate codes
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No redemption codes"
          detail="Generate codes to give customers a way to top up their credits."
          icon="card_giftcard"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => r.code ?? `row-${i}`}
          maxHeight="calc(100vh - 220px)"
          actionsColumn={() => (
            <Button variant="danger" size="sm" disabled title="Backend not yet exposed">
              Revoke
            </Button>
          )}
        />
      )}
    </>
  );
}
