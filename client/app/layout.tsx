import type { ReactNode } from 'react';
import './site.css';

/** Every page: the site's language and its shared styles. */
export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
