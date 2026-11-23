# website

Project website, built with VitePress (ADR 0005).

```sh
make site       # build into build/site
make site-dev   # live reload
```

Pages are written for the site and link to `docs/` on GitHub for detail. The site is subject to the public framing rule in AGENTS.md. `.github/workflows/site.yml` publishes to GitHub Pages on manual dispatch.
