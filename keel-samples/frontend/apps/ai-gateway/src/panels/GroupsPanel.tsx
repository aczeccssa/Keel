import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Chip,
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  KeyValue,
  PageHeader,
  SectionHeader
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface Group {
  groupId?: string;
  name?: string;
  enabled?: boolean;
  description?: string;
  exposureMode?: string;
  exposedToClients?: boolean;
  clientVisible?: boolean;
  publiclyExposed?: boolean;
  aliasRoutes?: Array<{ aliasName?: string; routingPolicy?: string; enabled?: boolean }>;
}

interface Channel {
  channelId?: string;
  name?: string;
  protocol?: string;
  baseUrl?: string;
  status?: string;
  enabled?: boolean;
  models?: Array<{ publicModelName?: string; enabled?: boolean }>;
  memberships?: Array<{ groupId?: string; priority?: number; weight?: number; enabled?: boolean }>;
  groupId?: string;
  priority?: number;
  weight?: number;
}

interface Member {
  channel: Channel;
  priority: number;
  weight: number;
  enabled: boolean;
}

function isExposed(g: Group): boolean | 'unknown' {
  if (typeof g.exposedToClients === 'boolean') return g.exposedToClients;
  if (typeof g.clientVisible === 'boolean') return g.clientVisible;
  if (typeof g.publiclyExposed === 'boolean') return g.publiclyExposed;
  return 'unknown';
}

function statusTone(s: string | undefined): 'ok' | 'warn' | 'danger' | 'muted' {
  switch ((s ?? '').toLowerCase()) {
    case 'healthy':
    case 'active':
    case 'ok':
    case 'up':
      return 'ok';
    case 'degraded':
    case 'idle':
      return 'warn';
    case 'down':
    case 'error':
    case 'disabled':
      return 'danger';
    default:
      return 'muted';
  }
}

function membersFor(channels: Channel[], groupId: string | undefined): Member[] {
  if (!groupId) return [];
  const out: Member[] = [];
  for (const c of channels) {
    const list =
      c.memberships && c.memberships.length > 0
        ? c.memberships
        : [{ groupId: c.groupId ?? 'default', priority: c.priority ?? 0, weight: c.weight ?? 100, enabled: c.enabled }];
    for (const m of list) {
      if ((m.groupId ?? 'default') === groupId && m.enabled) {
        out.push({ channel: c, priority: m.priority ?? 0, weight: m.weight ?? 100, enabled: m.enabled });
      }
    }
  }
  return out.sort((a, b) => b.priority - a.priority);
}

export function GroupsPanel({ api }: { api: AiGatewayApi }) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.groups(), api.channels()])
      .then(([g, c]) => {
        if (cancelled) return;
        setGroups(((g as { groups?: Group[] }).groups ?? []) as Group[]);
        setChannels(((c as { channels?: Channel[] }).channels ?? []) as Channel[]);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load groups');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const memberColumns: DataTableColumn<Member>[] = useMemo(
    () => [
      { key: 'name', header: 'Channel', render: (m) => m.channel.name ?? '—' },
      { key: 'protocol', header: 'Protocol', render: (m) => m.channel.protocol ?? '—' },
      {
        key: 'priority',
        header: 'Priority',
        width: '72px',
        align: 'right',
        mono: true,
        render: (m) => m.priority.toString()
      },
      {
        key: 'weight',
        header: 'Weight',
        width: '72px',
        align: 'right',
        mono: true,
        render: (m) => m.weight.toString()
      },
      {
        key: 'models',
        header: 'Models',
        render: (m) => {
          const names = (m.channel.models ?? [])
            .filter((x) => x.enabled)
            .map((x) => x.publicModelName)
            .filter((n): n is string => !!n);
          if (names.length === 0) return <span className="keel-muted">—</span>;
          return (
            <div className="keel-chip-cluster">
              {names.map((n, i) => (
                <Chip key={i} tone="neutral">
                  {n}
                </Chip>
              ))}
            </div>
          );
        }
      },
      {
        key: 'status',
        header: 'Status',
        width: '110px',
        render: (m) => (
          <Chip tone={statusTone(m.channel.enabled === false ? 'disabled' : m.channel.status)}>
            {m.channel.enabled === false ? 'disabled' : m.channel.status ?? 'healthy'}
          </Chip>
        )
      }
    ],
    []
  );

  return (
    <>
      <PageHeader
        title="Groups"
        description="Routing pools with their assigned channels, priority, and weight."
        actions={
          <Button variant="primary" size="sm" disabled title="Backend not yet exposed">
            + New group
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} /> : null}
      <section className="keel-section">
        <SectionHeader
          title="Routing groups"
          description={`${groups.length} group${groups.length === 1 ? '' : 's'} / ${channels.length} channel${channels.length === 1 ? '' : 's'}`}
        />
        {groups.length === 0 ? (
          <EmptyState
            title="No groups yet"
            detail="Create a group to bundle channels, set aliases, and expose them to your customers."
            icon="groups"
          />
        ) : (
          <div className="keel-card-list">
            {groups.map((g, i) => {
              const exposed = isExposed(g);
              const aliases = (g.aliasRoutes ?? []).filter((alias) => alias.enabled !== false);
              const members = membersFor(channels, g.groupId);
              return (
                <article key={g.groupId ?? g.name ?? i} className="keel-list-card">
                  <div className="keel-list-card__header">
                    <h3 className="keel-list-card__title">{g.name ?? '—'}</h3>
                    <Chip tone={exposed === true ? 'ok' : 'muted'}>
                      {exposed === true ? 'Exposed' : exposed === false ? 'Internal' : 'Unknown'}
                    </Chip>
                  </div>
                  <div className="keel-list-card__body">
                    <KeyValue label="ID">
                      <span className="keel-mono">{g.groupId ?? '—'}</span>
                    </KeyValue>
                    <KeyValue label="Aliases">
                      {aliases.length === 0 ? (
                        <span className="keel-muted">—</span>
                      ) : (
                        <div className="keel-chip-cluster">
                          {aliases.map((a, ai) => (
                            <Chip key={ai} tone="accent">
                              {`${a.aliasName ?? '—'} · ${a.routingPolicy ?? 'ORDERED_FAILOVER'}`}
                            </Chip>
                          ))}
                        </div>
                      )}
                    </KeyValue>
                    <KeyValue label="Exposure">
                      <span className="keel-mono">{g.exposureMode ?? 'ALL_MODELS'}</span>
                    </KeyValue>
                    <KeyValue label="Exposed to clients">
                      <Chip tone={exposed === true ? 'ok' : 'muted'}>
                        {exposed === true ? 'Yes' : exposed === false ? 'No' : 'Unknown'}
                      </Chip>
                    </KeyValue>
                    <KeyValue label="Channels">
                      <span className="keel-mono">{members.length}</span>
                    </KeyValue>
                  </div>
                  <div className="keel-list-card__subsection">
                    <span className="keel-list-card__subsection-label">Channels in this group</span>
                    {members.length === 0 ? (
                      <div className="keel-muted" style={{ fontSize: 12, padding: '12px 0' }}>
                        No channels assigned.
                      </div>
                    ) : (
                      <DataTable
                        columns={memberColumns}
                        rows={members}
                        getRowKey={(m) => m.channel.channelId ?? m.channel.name ?? ''}
                        actionsColumn={() => (
                          <>
                            <Button
                              variant="secondary"
                              size="sm"
                              disabled
                              title="Backend not yet exposed"
                            >
                              Save
                            </Button>
                            <Button
                              variant="danger"
                              size="sm"
                              disabled
                              title="Backend not yet exposed"
                            >
                              Detach
                            </Button>
                          </>
                        )}
                      />
                    )}
                  </div>
                  <div className="keel-list-card__actions">
                    <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                      Edit
                    </Button>
                    <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                      Manage aliases
                    </Button>
                    <span style={{ flex: 1 }} />
                    <Button variant="danger" size="sm" disabled title="Backend not yet exposed">
                      Delete
                    </Button>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>
    </>
  );
}
