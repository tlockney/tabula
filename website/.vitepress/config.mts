import { defineConfig } from 'vitepress'

// Project website. Hosting and deploy: docs/decisions/0005-website.md.
// SITE_BASE is "/tabula/" for the GitHub Pages project URL and "/" for a
// custom domain.
const repo = 'https://github.com/tlockney/tabula'

export default defineConfig({
  title: 'Tabula',
  description: 'Home Assistant kiosks for wall-mounted Android tablets.',
  lang: 'en-US',
  base: process.env.SITE_BASE ?? '/',
  cleanUrls: true,
  lastUpdated: true,
  outDir: '../build/site',
  cacheDir: '../build/vitepress-cache',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/how-it-works' },
      { text: 'Status', link: '/status' },
      { text: 'GitHub', link: repo },
    ],
    sidebar: {
      '/guide/': [
        {
          text: 'Guide',
          items: [
            { text: 'How it works', link: '/guide/how-it-works' },
            { text: 'Install', link: '/guide/install' },
            { text: 'Entities and services', link: '/guide/entities' },
            { text: 'Contributing', link: '/guide/contributing' },
          ],
        },
      ],
    },
    socialLinks: [{ icon: 'github', link: repo }],
    editLink: {
      pattern: `${repo}/edit/main/website/:path`,
      text: 'Edit this page on GitHub',
    },
    footer: {
      message: 'Released under the Apache-2.0 license.',
    },
  },
})
