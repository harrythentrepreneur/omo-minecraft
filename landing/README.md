# Omo Mission Control — landing page

Static, zero-build landing page (one `index.html` + `images/`). Deploys to Vercel as-is.

## Deploy (Vercel CLI)

```bash
cd landing
vercel          # first run links/creates the project → preview URL
vercel --prod   # promote to production
```

That's it — no build step, no framework. `vercel.json` handles clean URLs,
long-cache headers on `/images/*`, and basic security headers.

## Deploy (Vercel Git integration)

If you connect the GitHub repo instead of using the CLI:

> **IMPORTANT — set Root Directory = `landing`** in
> Project → Settings → General → Root Directory.
>
> The repo root contains `runtime/` (a Node project) and `plugin/` (Java).
> If Vercel builds from the repo root it will mis-detect the framework and fail.
> Pointing it at `landing/` makes it a plain static deploy.

- Framework Preset: **Other** (static)
- Build Command: *(none)*
- Output Directory: *(leave default — it serves `landing/` directly)*

## Before you go live

Search-and-replace the placeholder domain `https://omo.computer` with your real
domain in these spots (all in `index.html`, plus `robots.txt`):

- `<link rel="canonical">`
- `og:url`, `og:image`
- `twitter:image`
- `robots.txt` → `Sitemap:`

`og:image` / `og:url` **must be absolute** (full `https://…`) for social previews
to render. Until the domain is set, use your `*.vercel.app` URL.

## Files

```
index.html      the page (Classic / Minecraft style)
images/         8 optimized JPEGs (~2.1 MB total)
vercel.json     clean URLs + cache + security headers
robots.txt      allow-all + sitemap pointer
```
