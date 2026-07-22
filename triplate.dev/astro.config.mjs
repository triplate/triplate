import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig, fontProviders } from 'astro/config';
import starlight from '@astrojs/starlight';

// The Triplate TextMate injection grammar (see syntax/). Loaded here so
// Shiki layers Triplate's %-directives onto every `sparql` code block.
const triplateInjection = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('./syntax/triplate.injection.tmLanguage.json', import.meta.url),
    ),
    'utf8',
  ),
);

export default defineConfig({
  // Served from the triplate.dev custom domain, so the site lives at the root
  // and needs no `base` prefix.
  site: 'https://triplate.dev',
  // The old cookbook page was split into the use-case example pages.
  redirects: {
    '/language/comments': '/examples/query',
  },
  fonts: [{
    provider: fontProviders.fontsource(),
    name: "Noto Sans",
    cssVariable: "--font-noto-sans",
  }],
  integrations: [
    starlight({
      title: 'triplate',
      description:
        'A templating engine for RDF query & data languages with a typed parameter header, injection-safe values, loops and conditionals.',
      components: {
        // Render the home-page title as a dictionary entry.
        PageTitle: './src/components/PageTitle.astro',
      },
      customCss: [
        // Relative path to your custom CSS file
        './src/styles/custom.css',
      ],
      expressiveCode: {
        shiki: {
          // Shiki keys injection off `injectTo`; the file's `injectionSelector`
          // is what VS Code / Sublime use.
          langs: [
            {
              ...triplateInjection,
              name: 'triplate-injection',
              injectTo: ['source.sparql'],
            },
          ],
        },
      },
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/triplate/triplate' },
      ],
      sidebar: [
        {
          label: 'Getting started',
          items: [
            { label: 'Introduction', slug: 'index' },
            { label: 'Installation', slug: 'installation' },
            { label: 'Specification', slug: 'specification' },
          ],
        },
        {
          label: 'Language',
          items: [
            { label: 'Frontmatter', slug: 'language/frontmatter' },
            { label: 'Loops & Conditionals', slug: 'language/loops' },
            { label: 'Minting', slug: 'language/minting' },
          ],
        },
        {
          label: 'Examples',
          items: [
            { label: 'SPARQL Query', slug: 'examples/query' },
            { label: 'RDF Data', slug: 'examples/data' },
          ],
        },
        {
          label: 'API',
          items: [
            { label: 'Overview', slug: 'api' },
            { label: 'TypeScript', slug: 'api/typescript' },
            { label: 'Python', slug: 'api/python' },
            { label: 'Java', slug: 'api/java' },
          ],
        },
      ],
    }),
  ],
});
