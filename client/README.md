# Goalsetter (MERN import)

A Next.js (app router) + TypeScript site, exported from UniversalIDE.

```sh
npm install
npm run dev      # http://localhost:3000
npm run build    # the site, pre-rendered, in dist/
```

`dist/` is a static site: serve it from any web host. Under a sub-path (e.g. a GitHub Pages
project site at /repo/), build with `BASE_PATH=/repo npm run build`.

## Layout

- `app/`: a folder per page, at the same path as on the published site:
  - `/` → `app/page.tsx`
  - `/login` → `app/login/page.tsx`
  - `/register` → `app/register/page.tsx`
- Each `page.tsx` exports the page's `metadata` (title, description, social cards) and its
  structured data, and draws the page; each top-level section is a component in the same file.
  `screens.css` next to it holds the page's tablet and phone styles.
- `app/layout.tsx` and `app/site.css`: what every page shares.
- `components/SiteScripts.tsx` starts the shared page behaviour (`lib/site-scripts.ts`: entrance
  animations, folding menus, sending forms); `components/StayForm.tsx` is a form with nowhere to send.
- `public/`: pictures (`files/`), exact copies of imported pages (`_embeds/`, shown in a
  sandboxed frame), robots.txt, llms.txt and the sitemap.

Links between pages are ordinary links, as on the HTML site.
