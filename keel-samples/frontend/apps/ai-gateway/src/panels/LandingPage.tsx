import { Button, Hero, KeelLogo } from '@keel/sample-ui';

export function LandingPage({ onSignIn, onRegister }: { onSignIn: () => void; onRegister: () => void }) {
  return (
    <div className="ai-landing">
      <header className="keel-landing-nav">
        <KeelLogo label="Keel AI Relay" />
        <Button type="button" variant="ghost" onClick={onSignIn}>Sign in</Button>
      </header>
      <Hero
        eyebrow="AI Gateway Operations"
        title="Keel AI Relay"
        copy="Operate model routing, provider channels, usage, keys, pricing, customer credits, and risk controls from one precise console."
        primaryAction={<Button type="button" onClick={onSignIn}>Open console</Button>}
        secondaryAction={<Button type="button" variant="secondary" onClick={onRegister}>Create account</Button>}
        preview={
          <div className="keel-product-preview" aria-label="AI Relay product preview">
            <span>Channels</span><strong>6 healthy providers</strong>
            <span>Usage</span><strong>47 routed calls</strong>
            <span>Risk</span><strong>3 active rules</strong>
          </div>
        }
        proof={<span>Provider routing, budgets, and customer portal operations stay in sync.</span>}
      />
      <section className="keel-next-band"><strong>Route</strong><span>Configure channels, groups, pools, and failover from one console.</span></section>
    </div>
  );
}
