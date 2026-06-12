package dev.triplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the shared conformance fixtures from {@code triplate.dev/spec/conformance/}.
 * The TypeScript, Python and Java implementations must produce byte-identical
 * output for every fixture and raise the named error for every must-throw case.
 */
class ConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static File fixturesDir() {
    // Surefire runs with the module directory as the working directory.
    File dir = new File("../triplate.dev/spec/conformance");
    if (!dir.isDirectory()) {
      throw new IllegalStateException("conformance fixtures not found at " + dir.getAbsolutePath());
    }
    return dir;
  }

  @TestFactory
  List<DynamicTest> conformance() throws IOException {
    List<DynamicTest> tests = new ArrayList<>();
    File[] files = fixturesDir().listFiles((d, name) -> name.endsWith(".json"));
    if (files == null) return tests;
    java.util.Arrays.sort(files);
    for (File file : files) {
      List<Map<String, Object>> cases =
          MAPPER.readValue(file, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      for (Map<String, Object> c : cases) {
        String label = file.getName() + ": " + c.get("name");
        tests.add(DynamicTest.dynamicTest(label, () -> runCase(c)));
      }
    }
    return tests;
  }

  @SuppressWarnings("unchecked")
  private static void runCase(Map<String, Object> c) {
    String template = (String) c.get("template");
    Map<String, Object> context =
        c.get("context") == null ? Map.of() : (Map<String, Object>) c.get("context");
    if (c.containsKey("error")) {
      try {
        Triplate.render(template, context);
        fail("expected " + c.get("error") + " to be thrown");
      } catch (TriplateError e) {
        assertEquals(c.get("error"), e.getClass().getSimpleName());
      }
    } else {
      assertEquals(c.get("expected"), Triplate.render(template, context));
    }
  }
}
