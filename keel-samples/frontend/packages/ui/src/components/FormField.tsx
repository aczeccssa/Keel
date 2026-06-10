import type { InputHTMLAttributes, ReactNode } from 'react';

export interface FormFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  helper?: string;
  error?: string;
  trailing?: ReactNode;
}

export function FormField({ label, helper, error, trailing, id, ...props }: FormFieldProps) {
  const inputId = id ?? props.name ?? label.toLowerCase().replace(/\s+/g, '-');
  const describedBy = error ? `${inputId}-error` : helper ? `${inputId}-helper` : undefined;

  return (
    <label className="keel-field" htmlFor={inputId}>
      <span>{label}</span>
      <span className="keel-field-control">
        <input id={inputId} aria-invalid={Boolean(error)} aria-describedby={describedBy} {...props} />
        {trailing}
      </span>
      {helper ? <small id={`${inputId}-helper`}>{helper}</small> : null}
      {error ? <small id={`${inputId}-error`} className="is-error">{error}</small> : null}
    </label>
  );
}
