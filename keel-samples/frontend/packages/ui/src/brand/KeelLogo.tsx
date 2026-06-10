import logoUrl from '../assets/keel-logo.svg';

export interface KeelLogoProps {
  label?: string;
  className?: string;
}

export function KeelLogo({ label = 'Keel', className }: KeelLogoProps) {
  return (
    <span className={className ?? 'keel-logo'} aria-label={label}>
      <img src={logoUrl} alt="" aria-hidden="true" />
      <span>{label}</span>
    </span>
  );
}
