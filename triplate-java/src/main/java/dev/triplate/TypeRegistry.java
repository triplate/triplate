package dev.triplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for custom datatypes (the extensibility hook). A registered type is
 * usable as {@code ${ name }} where {@code name} is declared with the custom
 * type; the serializer must validate the value and return the exact text to
 * emit (it is fully responsible for injection safety).
 */
public final class TypeRegistry {
  private TypeRegistry() {}

  /** A serializer for a custom datatype. */
  @FunctionalInterface
  public interface CustomSerializer {
    String serialize(Object value, Integer line, Integer column);
  }

  private static final Map<String, CustomSerializer> CUSTOM = new ConcurrentHashMap<>();

  public static void registerType(String name, CustomSerializer serializer) {
    CUSTOM.put(name.toLowerCase(), serializer);
  }

  static CustomSerializer get(String name) {
    return CUSTOM.get(name.toLowerCase());
  }

  static boolean has(String name) {
    return CUSTOM.containsKey(name.toLowerCase());
  }
}
