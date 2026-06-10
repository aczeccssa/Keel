import { useState, type FormEvent } from 'react';
import { Button, Card, ErrorBanner, FormField, Toolbar } from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

type Protocol = 'chat' | 'responses' | 'messages';

function formatResponse(value: unknown) {
  return JSON.stringify(value, null, 2);
}

export function PlaygroundPanel({ api }: { api: AiGatewayApi }) {
  const [protocol, setProtocol] = useState<Protocol>('chat');
  const [response, setResponse] = useState<string>('Run a relay request to preview the response envelope.');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const form = new FormData(event.currentTarget);
    const model = String(form.get('model') || 'gpt-4o-mini');
    const prompt = String(form.get('prompt') || 'Summarize Keel AI Relay in one sentence.');

    try {
      const result = protocol === 'chat'
        ? await api.chatCompletions({ model, messages: [{ role: 'user', content: prompt }] })
        : protocol === 'responses'
          ? await api.responses({ model, input: prompt })
          : await api.messages({ model, max_tokens: 256, messages: [{ role: 'user', content: prompt }] });
      setResponse(formatResponse(result));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to send relay request');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="keel-panel">
      <Toolbar title="Playground" detail="Send safe relay probes through chat, responses, or messages envelopes." />
      <Card>
        {error ? <ErrorBanner message={error} /> : null}
        <form className="keel-playground-form" onSubmit={submit}>
          <label className="keel-field" htmlFor="playground-protocol">
            <span>Protocol</span>
            <span className="keel-field-control">
              <select id="playground-protocol" value={protocol} onChange={(event) => setProtocol(event.target.value as Protocol)}>
                <option value="chat">Chat Completions</option>
                <option value="responses">Responses</option>
                <option value="messages">Anthropic Messages</option>
              </select>
            </span>
          </label>
          <FormField label="Model" name="model" placeholder="gpt-4o-mini" />
          <label className="keel-field" htmlFor="playground-prompt">
            <span>Prompt</span>
            <span className="keel-field-control">
              <textarea id="playground-prompt" name="prompt" rows={6} defaultValue="Summarize Keel AI Relay in one sentence." />
            </span>
          </label>
          <Button type="submit" disabled={busy}>{busy ? 'Sending...' : 'Send'}</Button>
        </form>
      </Card>
      <Card>
        <pre className="keel-response-preview">{response}</pre>
      </Card>
    </section>
  );
}
