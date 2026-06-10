import { Button, Hero, KeelLogo } from '@keel/sample-ui';

export function LandingPage({ onSignIn, onRegister }: { onSignIn: () => void; onRegister: () => void }) {
  return (
    <div className="customer-landing">
      <header className="keel-landing-nav">
        <KeelLogo label="Keel Customer Portal" />
        <Button type="button" variant="ghost" onClick={onSignIn}>Sign in</Button>
      </header>
      <Hero
        eyebrow="Customer Self-Service"
        title="Keel Customer Portal"
        copy="Create API keys, track credits, review usage, and compare model pricing without waiting for an operator."
        primaryAction={<Button type="button" onClick={onRegister}>Create account</Button>}
        secondaryAction={<Button type="button" variant="secondary" onClick={onSignIn}>Sign in</Button>}
        preview={
          <div className="keel-product-preview" aria-label="Customer Portal product preview">
            <span>Credits</span><strong>1,000 available</strong>
            <span>Keys</span><strong>2 active keys</strong>
            <span>Pricing</span><strong>12 model rates</strong>
          </div>
        }
        proof={<span>Customers manage keys, credits, and usage without leaving the portal.</span>}
      />
      <section className="keel-next-band"><strong>Manage</strong><span>Redeem credits and create scoped keys in a focused portal.</span></section>
    </div>
  );
}
