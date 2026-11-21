import { defineConfig } from 'vitepress'

// Project website. Placeholder until there is something to publish; see
// docs/decisions/0005-website.md for scope and hosting.
export default defineConfig({
  title: 'Tabula',
  description: 'Home Assistant kiosks for wall-mounted Android tablets.',
  lang: 'en-US',
  cleanUrls: true,
  outDir: '../build/site',
  cacheDir: '../build/vitepress-cache',
  themeConfig: {
    nav: [{ text: 'Home', link: '/' }],
  },
})
