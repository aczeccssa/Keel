import type { ButtonHTMLAttributes, PropsWithChildren } from 'react';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
}

export function Button({ children, className = '', variant = 'primary', size = 'md', ...props }: PropsWithChildren<ButtonProps>) {
  return <button className={`keel-button keel-button-${variant} keel-button-${size} ${className}`.trim()} {...props}>{children}</button>;
}
