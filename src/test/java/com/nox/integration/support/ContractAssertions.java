package com.nox.integration.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * Checks response bodies against the schemas in the features' {@code contracts/openapi.yaml} files
 * (on a name clash the earlier feature wins, e.g. {@code Problem}): required fields,
 * known fields (unless {@code additionalProperties: true}), types, nullability, {@code enum},
 * {@code const}, {@code pattern}, {@code uniqueItems} and nested {@code $ref}s.
 */
public final class ContractAssertions {

    private static final List<Path> CONTRACTS = List.of(
            Path.of("specs/001-keycloak-api-auth/contracts/openapi.yaml"),
            Path.of("specs/002-pam-account-linking/contracts/openapi.yaml"),
            Path.of("specs/005-partner-payments/contracts/openapi.yaml"));
    private static final String REF_PREFIX = "#/components/schemas/";
    private static final Map<String, Object> SCHEMAS = loadSchemas();

    private ContractAssertions() {}

    /** Asserts that {@code body} matches the named schema under {@code components.schemas}. */
    public static void assertMatchesSchema(JsonNode body, String schemaName) {
        assertNode(body, schema(schemaName), schemaName);
    }

    @SuppressWarnings("unchecked")
    private static void assertNode(JsonNode node, Map<String, Object> schema, String path) {
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            assertNode(node, schema(ref.substring(REF_PREFIX.length())), path);
            return;
        }
        assertType(node, schema.get("type"), path);
        if (node.isNull()) {
            return;
        }
        if (schema.containsKey("const")) {
            assertThat(node.asString()).as(path).isEqualTo(String.valueOf(schema.get("const")));
        }
        if (schema.containsKey("enum")) {
            assertThat((List<Object>) schema.get("enum"))
                    .as(path)
                    .map(String::valueOf)
                    .contains(node.asString());
        }
        if (schema.containsKey("pattern")) {
            assertThat(node.asString()).as(path).containsPattern((String) schema.get("pattern"));
        }
        if (node.isObject()) {
            assertObject(node, schema, path);
        }
        if (node.isArray()) {
            assertArray(node, schema, path);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertObject(JsonNode node, Map<String, Object> schema, String path) {
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        assertThat(node.propertyNames()).as("%s required fields", path).containsAll(required);
        if (!Boolean.TRUE.equals(schema.get("additionalProperties"))) {
            assertThat(properties.keySet()).as("%s known fields", path).containsAll(node.propertyNames());
        }
        properties.forEach((name, propertySchema) -> {
            if (node.has(name)) {
                assertNode(node.get(name), (Map<String, Object>) propertySchema, path + "." + name);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void assertArray(JsonNode node, Map<String, Object> schema, String path) {
        if (Boolean.TRUE.equals(schema.get("uniqueItems"))) {
            assertThat(node.valueStream().toList()).as("%s unique items", path).doesNotHaveDuplicates();
        }
        if (schema.get("items") instanceof Map<?, ?> items) {
            for (int i = 0; i < node.size(); i++) {
                assertNode(node.get(i), (Map<String, Object>) items, path + "[" + i + "]");
            }
        }
    }

    private static void assertType(JsonNode node, Object type, String path) {
        List<?> allowed = type instanceof List<?> types ? types : type == null ? List.of() : List.of(type);
        if (allowed.isEmpty()) {
            return;
        }
        Set<String> actual = node.isNull()
                ? Set.of("null")
                : node.isString()
                        ? Set.of("string")
                        : node.isObject()
                                ? Set.of("object")
                                : node.isArray()
                                        ? Set.of("array")
                                        : node.isBoolean()
                                                ? Set.of("boolean")
                                                : node.isIntegralNumber()
                                                        ? Set.of("integer", "number")
                                                        : Set.of("number");
        assertThat(allowed).as("%s type", path).map(String::valueOf).containsAnyElementsOf(actual);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) {
        Object schema = SCHEMAS.get(name);
        assertThat(schema).as("schema %s in %s", name, CONTRACTS).isNotNull();
        return (Map<String, Object>) schema;
    }

    private static Map<String, Object> loadSchemas() {
        Map<String, Object> schemas = new HashMap<>();
        CONTRACTS.forEach(contract -> loadSchemas(contract).forEach(schemas::putIfAbsent));
        return schemas;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadSchemas(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, Object> contract = new Yaml().load(reader);
            return (Map<String, Object>) ((Map<String, Object>) contract.get("components")).get("schemas");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
