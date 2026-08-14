import { readdir, writeFile } from 'node:fs/promises';

const baseUrl = process.env.SITE_URL || 'https://posallinone.vercel.app';
const docsRoot = new URL('../', import.meta.url);
const ignoredDirectories = new Set(['assets', 'images', 'scripts']);

async function discoverRoutes(directory = docsRoot, relativePath = '') {
  const entries = await readdir(directory, { withFileTypes: true });
  const routes = [];
  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (!ignoredDirectories.has(entry.name)) {
        routes.push(...await discoverRoutes(new URL(`${entry.name}/`, directory), `${relativePath}${entry.name}/`));
      }
    } else if (entry.name === 'index.html') {
      routes.push(`/${relativePath}`);
    } else if (entry.name.endsWith('.html')) {
      routes.push(`/${relativePath}${entry.name}`);
    }
  }
  return routes;
}

const routes = (await discoverRoutes()).sort((a, b) => a.localeCompare(b));
const today = new Date().toISOString().slice(0, 10);
const urls = routes.map((route) => `  <url>\n    <loc>${baseUrl}${route}</loc>\n    <lastmod>${today}</lastmod>\n    <changefreq>monthly</changefreq>\n    <priority>${route === '/' ? '1.0' : '0.8'}</priority>\n  </url>`).join('\n');
await writeFile(new URL('../sitemap.xml', import.meta.url), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`);
