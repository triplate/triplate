// Verifies the Triplate injection grammar by tokenizing snippets with the
// same engine VS Code and Shiki use (vscode-textmate + vscode-oniguruma),
// injecting it over a minimal `source.sparql` base grammar.
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vscodeTextmate from 'vscode-textmate';
import oniguruma from 'vscode-oniguruma';

const { Registry, parseRawGrammar } = vscodeTextmate;
const { loadWASM, createOnigScanner, createOnigString } = oniguruma;

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

const wasmBin = readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
await loadWASM(wasmBin);
const onigLib = Promise.resolve({
  createOnigScanner: (patterns) => createOnigScanner(patterns),
  createOnigString: (s) => createOnigString(s),
});

const files = {
  'source.sparql': join(here, 'sparql.stub.tmLanguage.json'),
  'triplate.injection': join(here, '..', 'triplate.injection.tmLanguage.json'),
};

const registry = new Registry({
  onigLib,
  loadGrammar: async (scopeName) => {
    const file = files[scopeName];
    if (!file) return null;
    return parseRawGrammar(readFileSync(file, 'utf8'), file);
  },
  getInjections: (scopeName) =>
    scopeName === 'source.sparql' ? ['triplate.injection'] : [],
});

const grammar = await registry.loadGrammar('source.sparql');

/** Tokenize one line and return [{ text, scopes }]. */
function tokenizeLine(line, ruleStack = null) {
  const result = grammar.tokenizeLine(line, ruleStack);
  return {
    tokens: result.tokens.map((t) => ({ text: line.slice(t.startIndex, t.endIndex), scopes: t.scopes })),
    ruleStack: result.ruleStack,
  };
}

/** Tokenize a multi-line block, threading rule state across lines (for `---` frontmatter). */
function tokenizeBlock(text) {
  let stack = null;
  const all = [];
  for (const line of text.split('\n')) {
    const { tokens, ruleStack } = tokenizeLine(line, stack);
    all.push(...tokens);
    stack = ruleStack;
  }
  return all;
}

let failures = 0;
function check(description, lineOrTokens, predicate) {
  const tokens = Array.isArray(lineOrTokens) ? lineOrTokens : tokenizeLine(lineOrTokens).tokens;
  let ok;
  try {
    ok = predicate(tokens);
  } catch (err) {
    ok = false;
    description += ` (threw: ${err.message})`;
  }
  if (!ok) {
    failures++;
    console.error(`✗ ${description}`);
    for (const t of tokens) {
      console.error(`    ${JSON.stringify(t.text)}  ${t.scopes.join(' ')}`);
    }
  } else {
    console.log(`✓ ${description}`);
  }
}

/** Does any token whose text === `text` carry `scope`? */
const has = (tokens, text, scope) =>
  tokens.some((t) => t.text === text && t.scopes.includes(scope));
/** Do any tokens carry a Triplate scope at all? */
const anyTriplate = (tokens) =>
  tokens.some((t) => t.scopes.some((s) => s.endsWith('.triplate')));

// --- frontmatter (multi-line, rule state threaded) ------------------------
const fm = tokenizeBlock(
  '---\n' +
  'params {\n' +
  '  service: iri\n' +
  '  classes: iri[] min 1\n' +
  '}\n' +
  'example demo "Demo" {\n' +
  '  service: <http://x>\n' +
  '}\n' +
  '---',
);
check('frontmatter: params keyword', fm, (t) => has(t, 'params', 'keyword.control.triplate'));
check('frontmatter: example keyword', fm, (t) => has(t, 'example', 'keyword.control.triplate'));
check('frontmatter: type', fm, (t) => has(t, 'iri', 'support.type.triplate'));
check('frontmatter: min keyword', fm, (t) => has(t, 'min', 'keyword.other.triplate'));
check('frontmatter: param name is a variable', fm, (t) => has(t, 'service', 'variable.other.triplate'));
check('frontmatter: example description', fm, (t) => has(t, '"Demo"', 'string.quoted.double.triplate'));
check('frontmatter: example IRI value', fm, (t) => has(t, '<http://x>', 'markup.underline.link.triplate'));

// --- values ---------------------------------------------------------------
check('value: punctuation + path', '?s a ${cls}', (t) =>
  has(t, '$', 'punctuation.definition.template.triplate') &&
  has(t, 'cls', 'variable.other.triplate'),
);
check('value: dotted path', '${p.id}', (t) => has(t, 'p.id', 'variable.other.triplate'));
check('bare $var (SPARQL variable) is NOT a value', 'SELECT ?x WHERE { $s ?p ?o }', (t) =>
  !anyTriplate(t),
);

// --- interpolated string & IRI template ------------------------------------
check('interp string scope + hole', '$"Hello ${name}"', (t) =>
  t.some((x) => x.scopes.includes('string.quoted.double.interpolated.triplate')) &&
  has(t, 'name', 'variable.other.triplate'),
);
check('interp string static lang tag', '$"x"@en', (t) =>
  has(t, 'en', 'constant.language.tag.triplate'),
);
check('IRI template scope + hole', '$<http://ex.org/${id}>', (t) =>
  t.some((x) => x.scopes.includes('string.other.iri.triplate')) &&
  has(t, 'id', 'variable.other.triplate'),
);

// --- directives ------------------------------------------------------------
check('for: keywords and source', '{% for c in classes join "UNION" %}', (t) =>
  has(t, 'for', 'keyword.control.triplate') &&
  has(t, 'in', 'keyword.other.triplate') &&
  has(t, 'join', 'keyword.other.triplate') &&
  has(t, 'classes', 'variable.other.triplate'),
);
check('explicit join modifier', '{% for c in xs join ", " explicit %}', (t) =>
  has(t, 'explicit', 'keyword.other.triplate'),
);
check('endfor keyword', '{% endfor %}', (t) => has(t, 'endfor', 'keyword.control.triplate'));
check('if + not', '{% if not flag %}', (t) =>
  has(t, 'if', 'keyword.control.triplate') &&
  has(t, 'not', 'keyword.other.triplate') &&
  has(t, 'flag', 'variable.other.triplate'),
);

// --- inert regions ---------------------------------------------------------
check('${ } inside a plain SPARQL string is NOT highlighted', '"text ${x} here"', (t) =>
  !anyTriplate(t),
);
check('percent-encoding inside an IRI is NOT a value', '<http://x/caf%C3%A9>', (t) =>
  !anyTriplate(t),
);
check('directives in # comments are NOT highlighted', '# ${x} and {% for c in xs %}', (t) =>
  !anyTriplate(t),
);

console.log(`\n${failures === 0 ? 'PASS' : 'FAIL'}: ${failures} failing check(s)`);
process.exit(failures === 0 ? 0 : 1);
