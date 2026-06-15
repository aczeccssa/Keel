import type { ButtonHTMLAttributes, PropsWithChildren } from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';
export type ButtonSize = 'sm' | 'md';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
}

export function Button({
  children,
  className = '',
  variant = 'primary',
  size = 'md',
  type = 'button',
  ...props
}: PropsWithChildren<ButtonProps>) {
  const classes = ['keel-button', `keel-button--${variant}`, `keel-button--${size}`];
  if (className) classes.push(className);
  return (
    <button type={type} className={classes.join(' ')} {...props}>
      {children}
    </button>
  );
}
