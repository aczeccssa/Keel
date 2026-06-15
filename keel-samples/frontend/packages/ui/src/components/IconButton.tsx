import type { ButtonHTMLAttributes, ReactNode } from 'react';

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  ariaLabel: string;
  size?: 'sm' | 'md';
  variant?: 'default' | 'danger' | 'ghost';
  children: ReactNode;
}

export function IconButton({
  ariaLabel,
  size = 'md',
  variant = 'default',
  className = '',
  children,
  type = 'button',
  ...rest
}: IconButtonProps) {
  const classes = ['keel-icon-button'];
  if (size === 'sm') classes.push('keel-icon-button--sm');
  if (variant === 'danger') classes.push('keel-icon-button--danger');
  if (variant === 'ghost') classes.push('keel-icon-button--ghost');
  if (className) classes.push(className);
  return (
    <button type={type} aria-label={ariaLabel} className={classes.join(' ')} {...rest}>
      {children}
    </button>
  );
}
