import type { Pos } from './serializers.js';

export * from './serializers.js';

/**
 * Registry for custom datatypes (extensibility hook). A registered type is
 * usable as %name:<typename>; the serializer must validate the value and
 * return the exact SPARQL text to emit (it is fully responsible for safety).
 */
export type CustomSerializer = (value: unknown, pos: Pos) => string;

const customTypes = new Map<string, CustomSerializer>();

export function registerType(name: string, serializer: CustomSerializer): void {
  customTypes.set(name.toLowerCase(), serializer);
}

export function getCustomType(name: string): CustomSerializer | undefined {
  return customTypes.get(name.toLowerCase());
}

export function hasCustomType(name: string): boolean {
  return customTypes.has(name.toLowerCase());
}
