import { TriplateSyntaxError } from './errors.js';
import type {
  CompiledTemplateData,
  Cond,
  ExampleSet,
  ForNode,
  IfNode,
  Node,
  RefPath,
  Schema,
  TypeExpr,
} from './ast.js';
import { tokenize, type Token } from './lexer.js';

export function parse(template: string): CompiledTemplateData {
  const tokens = tokenize(template);

  let schema: Schema | undefined;
  const examples: ExampleSet[] = [];
  const body: Token[] = [];
  for (const t of tokens) {
    if (t.kind === 'params') {
      if (schema) throw new TriplateSyntaxError('duplicate params section in frontmatter', t.line, t.column);
      const byName: Record<string, TypeExpr> = {};
      for (const d of t.decls) {
        if (byName[d.name]) throw new TriplateSyntaxError(`duplicate parameter: ${d.name}`, t.line, t.column);
        byName[d.name] = d.type;
      }
      schema = { params: t.decls, byName };
    } else if (t.kind === 'examples') {
      if (examples.some((e) => e.id === t.id)) {
        throw new TriplateSyntaxError(`duplicate example id: ${t.id}`, t.line, t.column);
      }
      examples.push({ id: t.id, description: t.description, bindings: t.bindings });
    } else if (t.kind === 'text' && schema === undefined) {
      if (t.value.trim() !== '') throw new TriplateSyntaxError('content before the --- frontmatter header');
    } else {
      body.push(t);
    }
  }
  if (!schema) throw new TriplateSyntaxError('missing --- frontmatter header (--- params { … } ---)');

  const tree = buildTree(body);
  validateScopes(tree, new Set(Object.keys(schema.byName)));
  return { schema, examples, body: tree };
}

type Frame =
  | { kind: 'root'; children: Node[] }
  | { kind: 'for'; node: ForNode }
  | { kind: 'if'; node: IfNode; inElse: boolean };

function buildTree(tokens: Token[]): Node[] {
  const root: Node[] = [];
  const stack: Frame[] = [{ kind: 'root', children: root }];
  const current = (): Node[] => {
    const f = stack[stack.length - 1];
    if (f.kind === 'root') return f.children;
    if (f.kind === 'for') return f.node.body;
    return f.inElse ? f.node.elseBody! : f.node.branches[f.node.branches.length - 1].body;
  };
  const append = (n: Node) => current().push(n);

  for (const t of tokens) {
    switch (t.kind) {
      case 'text':
        append({ type: 'text', value: t.value });
        break;
      case 'value':
        append({ type: 'value', path: t.path, line: t.line, column: t.column });
        break;
      case 'interp':
        append({ type: 'interp', parts: t.parts, lang: t.lang, datatype: t.datatype, line: t.line, column: t.column });
        break;
      case 'iri':
        append({ type: 'iri', parts: t.parts, line: t.line, column: t.column });
        break;
      case 'for': {
        const node: ForNode = {
          type: 'for', item: t.item, source: t.source, join: t.join, joinExact: t.joinExact,
          body: [], line: t.line, column: t.column,
        };
        append(node);
        stack.push({ kind: 'for', node });
        break;
      }
      case 'endfor':
        if (stack[stack.length - 1].kind !== 'for') {
          throw new TriplateSyntaxError('%endfor without a matching %for', t.line, t.column);
        }
        stack.pop();
        break;
      case 'if': {
        const node: IfNode = { type: 'if', branches: [{ cond: t.cond, body: [] }], line: t.line, column: t.column };
        append(node);
        stack.push({ kind: 'if', node, inElse: false });
        break;
      }
      case 'elif': {
        const f = stack[stack.length - 1];
        if (f.kind !== 'if' || f.inElse) throw new TriplateSyntaxError('%elif without a matching %if', t.line, t.column);
        f.node.branches.push({ cond: t.cond, body: [] });
        break;
      }
      case 'else': {
        const f = stack[stack.length - 1];
        if (f.kind !== 'if' || f.inElse) throw new TriplateSyntaxError('%else without a matching %if', t.line, t.column);
        f.inElse = true;
        f.node.elseBody = [];
        break;
      }
      case 'endif':
        if (stack[stack.length - 1].kind !== 'if') {
          throw new TriplateSyntaxError('%endif without a matching %if', t.line, t.column);
        }
        stack.pop();
        break;
    }
  }
  if (stack.length > 1) {
    const f = stack[stack.length - 1];
    const open = f.kind === 'for' ? f.node : f.kind === 'if' ? f.node : undefined;
    throw new TriplateSyntaxError('unclosed block directive', open?.line, open?.column);
  }
  return root;
}

/** Compile-time check: every reference resolves to a declared param or an in-scope loop variable. */
function validateScopes(nodes: Node[], bound: Set<string>): void {
  const checkHead = (path: RefPath, line: number, column: number) => {
    if (!bound.has(path[0])) {
      throw new TriplateSyntaxError(`undeclared variable: ${path.join('.')}`, line, column);
    }
  };
  const checkCond = (c: Cond) => checkHead(c.path, c.line, c.column);

  for (const node of nodes) {
    switch (node.type) {
      case 'value':
        checkHead(node.path, node.line, node.column);
        break;
      case 'interp':
        if (node.lang && 'path' in node.lang) checkHead(node.lang.path, node.line, node.column);
        for (const p of node.parts) if ('path' in p) checkHead(p.path, p.line, p.column);
        break;
      case 'iri':
        for (const p of node.parts) if ('path' in p) checkHead(p.path, p.line, p.column);
        break;
      case 'for': {
        checkHead(node.source, node.line, node.column);
        const inner = new Set(bound);
        inner.add(node.item);
        validateScopes(node.body, inner);
        break;
      }
      case 'if':
        for (const b of node.branches) {
          checkCond(b.cond);
          validateScopes(b.body, bound);
        }
        if (node.elseBody) validateScopes(node.elseBody, bound);
        break;
    }
  }
}
