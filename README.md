# Goalsetter (MERN import)

The site, drawn by Next.js, and its own server in Java (Spring Boot, PostgreSQL), exported from UniversalIDE. Java 21 or
later and Maven.

Build the site first (Node.js 22): `cd client && npm install && npm run build && cd .. && cp -r client/dist site`.

Set `DATABASE_URL` to the PostgreSQL server's address (`postgres://user:password@host:5432/site`, over TLS unless it is on this machine or `?sslmode=disable`); without it the site is served and forms answer that the database isn't set up. Or keep the address in UniversalIDE (Publish → App database) and set `UNIVERSALIDE_APP_KEY` to an app key made there instead: without `DATABASE_URL` the server asks UniversalIDE for the address when it starts (at the address in `server.json`, or `UNIVERSALIDE_URL`). Keep the key in the host’s environment, never in the code.

```sh
mvn package
ADMIN_KEY=choose-a-long-secret java -jar target/site.jar     # http://localhost:8080, from this folder
```

Or `ADMIN_KEY=… mvn spring-boot:run`. The server reads `server.json`, `not-found.html` and `site/` from
the folder it is started in (or `SITE_ROOT`). Or with Docker: `docker build -t site . && docker run -p 8080:8080 -e ADMIN_KEY=… -v site-data:/data site`.

## Layout

- `site/`: the site's pages, styles, pictures and exact copies, served as they are.
- `private/`: the members-only pages, if the site has any, sent to signed-in members only.
- `src/main/java/site/SiteController.java`: the server: the site, `POST /api/forms/{id}`,
  `POST /api/views`, `/api/admin/…`.
- `Rules.java`: the checks on messages and page views; `Store.java` and `PostgresStore.java`: the database;
  `Limiter.java`: the sending limits; `ServerConfig.java`: reads `server.json`.

## Messages and statistics

Forms on the site that send to you post to `/api/forms/<form id>`; the messages are kept in the
database (the newest 2,000). Page views are counted a day per page, with the sites visitors came
from, and so are clicks on the site's buttons and links, how far pages are scrolled and which forms
are used. Visitors and clickers are counted once a day through a hash that changes every day (no
cookies); nothing about the visitors themselves is kept. Read them with the admin key (connect the
server in UniversalIDE, Publish → Form messages, to see them there):

```sh
curl -H "Authorization: Bearer $ADMIN_KEY" http://localhost:8080/api/admin/messages
curl -H "Authorization: Bearer $ADMIN_KEY" "http://localhost:8080/api/admin/stats?days=30"
curl -H "Authorization: Bearer $ADMIN_KEY" "http://localhost:8080/api/admin/counts?days=30"
curl -X DELETE -H "Authorization: Bearer $ADMIN_KEY" http://localhost:8080/api/admin/messages/<id>
```

| Setting | |
|---|---|
| `PORT` | where it listens |
| `DATA_DIR` | the folder for the database (`site.sqlite`) |
| `ADMIN_KEY` | lets you read messages and statistics; without it those addresses answer 404 |
| `TRUST_PROXY=1` | behind a proxy: a visitor's address (for the sending limits only) is the first in `X-Forwarded-For` |
| `FORM_WEBHOOK_URL` | each message is also posted there as JSON (a Slack or Discord incoming webhook, Zapier, Make, n8n…): `event`, `form`, `page`, `fields`, `createdAt`, and `text`/`content` to read |

`server.json` holds the site's forms, pages and rules; `site/` is the site itself. Both are
written by UniversalIDE: export again to update them.

## Records

Collections the site keeps in its database are written by its members: signed in, each adds
records and changes or deletes their own, at `/api/members/records/<collection>[/<id>]` (the
pages do this for them). Values are checked by the collection's fields (`server.json`
`records`); a collection holds 2,000 records. Read every record, or remove one, with the admin key:

```sh
curl -H "Authorization: Bearer $ADMIN_KEY" http://localhost:8080/api/admin/records/<collection>
curl -X DELETE -H "Authorization: Bearer $ADMIN_KEY" http://localhost:8080/api/admin/records/<collection>/<id>
```

## The frontend on Vercel

`client/` also deploys to Vercel on its own, with the server running elsewhere: import the
repository in Vercel with **Root Directory** `client` (`client/vercel.json` sets the build) and set
`BACKEND_URL` to the server's address (e.g. `https://my-site.onrender.com`, no trailing slash).
Vercel serves the pages and passes `/api/*` and the members-only pages to the server, so the site
has one address: no CORS, and members stay signed in across both. On the server set
`TRUST_PROXY=1` so visitors' own addresses (for the sending limits) come from `X-Forwarded-For`.
