export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface RequestJsonOptions {
  method?: HttpMethod;
  body?: unknown;
  token?: string | null;
  headers?: Record<string, string>;
}

export class ApiError extends Error {
  status: number;
  details: unknown;

  constructor(status: number, message: string, details: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.details = details;
  }
}

export async function requestJson<T>(url: string, options: RequestJsonOptions = {}): Promise<T> {
  const headers: Record<string, string> = {
    ...(options.body == null ? {} : { 'Content-Type': 'application/json' }),
    ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
    ...options.headers
  };

  const response = await fetch(url, {
    method: options.method ?? (options.body == null ? 'GET' : 'POST'),
    headers,
    body: options.body == null ? undefined : JSON.stringify(options.body)
  });

  const text = await response.text();
  const parsed = text ? safeJson(text) : null;

  if (!response.ok) {
    const message = extractMessage(parsed) ?? `${response.status} ${response.statusText}`.trim();
    throw new ApiError(response.status, message, parsed);
  }

  return parsed as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function extractMessage(value: unknown): string | null {
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    if (typeof record.message === 'string') return record.message;
    if (typeof record.error === 'string') return record.error;
  }
  return null;
}
