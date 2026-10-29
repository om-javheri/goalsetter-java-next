'use client';

import type { ComponentProps } from 'react';

/** A form with nowhere to send: submitting it does nothing (a server component can't take the handler). */
export default function StayForm(props: ComponentProps<'form'>) {
  return <form {...props} onSubmit={(e) => e.preventDefault()} />;
}
