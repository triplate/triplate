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
  // GitHub Pages project site for now. Once the triplate.dev custom domain is
  // wired up: site: 'https://triplate.dev' and remove `base`.
  site: 'https://triplate.github.io',
  base: '/triplate',
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
      social: {
        github: 'https://github.com/triplate/triplate',
      },
      sidebar: [
        {
          label: 'Getting started',
          items: [
            { label: 'Introduction', slug: 'index' },
            { label: 'Installation', slug: 'installation' },
          ],
        },
        {
          label: 'Language',
          items: [
            { label: 'Header', slug: 'language/substitutions' },
            { label: 'Loops & Conditionals', slug: 'language/loops' },
            { label: 'Variables', slug: 'language/strings' },
            { label: 'Examples', slug: 'language/comments' },
          ],
        },
        {
          label: 'Reference',
          items: [
            { label: 'Specification', slug: 'specification' },
            { label: 'API (TypeScript, Python & Java)', slug: 'reference/api' },
            { label: 'Security Model', slug: 'reference/security' },
          ],
        },
      ],
    }),
  ],
});
