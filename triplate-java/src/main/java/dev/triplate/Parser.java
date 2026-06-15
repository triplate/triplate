package dev.triplate;

import dev.triplate.Ast.Branch;
import dev.triplate.Ast.CompiledTemplateData;
import dev.triplate.Ast.Cond;
import dev.triplate.Ast.ExampleSet;
import dev.triplate.Ast.ForNode;
import dev.triplate.Ast.IfNode;
import dev.triplate.Ast.Node;
import dev.triplate.Ast.ParamDecl;
import dev.triplate.Ast.Schema;
import dev.triplate.Ast.TypeExpr;
import dev.triplate.Lexer.Token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns the token stream into the compiled template model. Port of {@code parser.ts}. */
final class Parser {
  private Parser() {}

  static CompiledTemplateData parse(String template) {
    List<Token> tokens = Lexer.tokenize(template);

    Schema schema = null;
    List<ExampleSet> examples = new ArrayList<>();
    List<Token> body = new ArrayList<>();
    for (Token t : tokens) {
      if (t instanceof Lexer.ParamsTok p) {
        if (schema != null) {
          throw new TriplateSyntaxError("duplicate params section in frontmatter", p.line(), p.column());
        }
        Map<String, TypeExpr> byName = new LinkedHashMap<>();
        for (ParamDecl d : p.decls()) {
          if (byName.containsKey(d.name())) {
            throw new TriplateSyntaxError("duplicate parameter: " + d.name(), p.line(), p.column());
          }
          byName.put(d.name(), d.type());
        }
        schema = new Schema(p.decls(), byName);
      } else if (t instanceof Lexer.ExampleTok e) {
        for (ExampleSet existing : examples) {
          if (existing.id().equals(e.id())) {
            throw new TriplateSyntaxError("duplicate example id: " + e.id(), e.line(), e.column());
          }
        }
        examples.add(new ExampleSet(e.id(), e.description(), e.bindings(), e.line(), e.column()));
      } else if (t instanceof Lexer.Txt txt && schema == null) {
        if (!txt.value().trim().isEmpty()) {
          throw new TriplateSyntaxError("content before the --- frontmatter header");
        }
      } else {
        body.add(t);
      }
    }
    if (schema == null) {
      throw new TriplateSyntaxError("missing --- frontmatter header (--- params { … } ---)");
    }

    List<Node> tree = buildTree(body);
    validateScopes(tree, new HashSet<>(schema.byName().keySet()));
    return new CompiledTemplateData(schema, examples, tree);
  }

  private static final class Frame {
    final String kind; // "root", "for", "if"
    List<Node> children;
    ForNode forNode;
    IfNode ifNode;
    boolean inElse;

    Frame(String kind) {
      this.kind = kind;
    }
  }

  private static List<Node> buildTree(List<Token> tokens) {
    List<Node> root = new ArrayList<>();
    Deque<Frame> stack = new ArrayDeque<>();
    Frame rootFrame = new Frame("root");
    rootFrame.children = root;
    stack.push(rootFrame);

    for (Token t : tokens) {
      if (t instanceof Lexer.Txt txt) {
        current(stack).add(new Ast.TextNode(txt.value()));
      } else if (t instanceof Lexer.Val v) {
        current(stack).add(new Ast.ValueNode(v.path(), v.spread(), v.join(), v.joinExact(), v.line(), v.column()));
      } else if (t instanceof Lexer.Interp i) {
        current(stack).add(new Ast.InterpNode(i.parts(), i.lang(), i.datatype(), i.line(), i.column()));
      } else if (t instanceof Lexer.Iri ir) {
        current(stack).add(new Ast.IriNode(ir.parts(), ir.line(), ir.column()));
      } else if (t instanceof Lexer.ForTok f) {
        ForNode node = new ForNode(f.item(), f.source(), f.join(), f.joinExact(), f.line(), f.column());
        current(stack).add(node);
        Frame frame = new Frame("for");
        frame.forNode = node;
        stack.push(frame);
      } else if (t instanceof Lexer.EndForTok ef) {
        if (!stack.peek().kind.equals("for")) {
          throw new TriplateSyntaxError("%endfor without a matching %for", ef.line(), ef.column());
        }
        stack.pop();
      } else if (t instanceof Lexer.IfTok ift) {
        IfNode node = new IfNode(ift.line(), ift.column());
        node.branches.add(new Branch(ift.cond()));
        current(stack).add(node);
        Frame frame = new Frame("if");
        frame.ifNode = node;
        stack.push(frame);
      } else if (t instanceof Lexer.ElifTok el) {
        Frame f = stack.peek();
        if (!f.kind.equals("if") || f.inElse) {
          throw new TriplateSyntaxError("%elif without a matching %if", el.line(), el.column());
        }
        f.ifNode.branches.add(new Branch(el.cond()));
      } else if (t instanceof Lexer.ElseTok el) {
        Frame f = stack.peek();
        if (!f.kind.equals("if") || f.inElse) {
          throw new TriplateSyntaxError("%else without a matching %if", el.line(), el.column());
        }
        f.inElse = true;
        f.ifNode.elseBody = new ArrayList<>();
      } else if (t instanceof Lexer.EndIfTok ei) {
        if (!stack.peek().kind.equals("if")) {
          throw new TriplateSyntaxError("%endif without a matching %if", ei.line(), ei.column());
        }
        stack.pop();
      }
    }
    if (stack.size() > 1) {
      Frame f = stack.peek();
      Integer line = f.kind.equals("for") ? f.forNode.line : f.kind.equals("if") ? f.ifNode.line : null;
      Integer col = f.kind.equals("for") ? f.forNode.column : f.kind.equals("if") ? f.ifNode.column : null;
      throw new TriplateSyntaxError("unclosed block directive", line, col);
    }
    return root;
  }

  private static List<Node> current(Deque<Frame> stack) {
    Frame f = stack.peek();
    if (f.kind.equals("root")) return f.children;
    if (f.kind.equals("for")) return f.forNode.body;
    return f.inElse ? f.ifNode.elseBody : f.ifNode.branches.get(f.ifNode.branches.size() - 1).body;
  }

  /** Compile-time check: every reference resolves to a declared param or an in-scope loop variable. */
  private static void validateScopes(List<Node> nodes, Set<String> bound) {
    for (Node node : nodes) {
      if (node instanceof Ast.ValueNode v) {
        checkHead(v.path(), v.line(), v.column(), bound);
      } else if (node instanceof Ast.InterpNode i) {
        if (i.lang() instanceof Ast.PathLang pl) checkHead(pl.path(), i.line(), i.column(), bound);
        for (Ast.Part p : i.parts()) {
          if (p instanceof Ast.HolePart h) checkHead(h.path(), h.line(), h.column(), bound);
        }
      } else if (node instanceof Ast.IriNode ir) {
        for (Ast.Part p : ir.parts()) {
          if (p instanceof Ast.HolePart h) checkHead(h.path(), h.line(), h.column(), bound);
        }
      } else if (node instanceof ForNode f) {
        checkHead(f.source, f.line, f.column, bound);
        Set<String> inner = new HashSet<>(bound);
        inner.add(f.item);
        validateScopes(f.body, inner);
      } else if (node instanceof IfNode iff) {
        for (Branch b : iff.branches) {
          checkCond(b.cond, bound);
          validateScopes(b.body, bound);
        }
        if (iff.elseBody != null) validateScopes(iff.elseBody, bound);
      }
    }
  }

  private static void checkHead(List<String> path, int line, int column, Set<String> bound) {
    if (!bound.contains(path.get(0))) {
      throw new TriplateSyntaxError("undeclared variable: " + String.join(".", path), line, column);
    }
  }

  private static void checkCond(Cond c, Set<String> bound) {
    checkHead(c.path(), c.line(), c.column(), bound);
  }
}
