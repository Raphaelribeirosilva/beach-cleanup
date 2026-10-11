"""Check local references and API route coverage without external dependencies.

Run from any directory: python tools/validate_openapi.py
This is a structural/coverage check, not a full OpenAPI schema validator.
"""

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs/openapi.json"
METHODS = {"get", "post", "put", "delete", "patch", "head", "options", "trace"}
PUBLIC = {
    ("post", "/auth/login"),
    ("post", "/users"),
    ("get", "/cleanupEvent"),
    ("get", "/cleanupEvent/{id}"),
    ("get", "/cleanupEvent/search"),
}


def check(condition, message):
    if not condition:
        raise ValueError(message)


def validate(spec):
    check(spec["openapi"] == "3.0.3", "Unexpected OpenAPI version")
    check(spec["info"].get("title") and spec["info"].get("version"), "Missing API info")
    check(spec["security"] == [{"bearerAuth": []}], "Missing default JWT requirement")
    check(spec["components"]["securitySchemes"]["bearerAuth"]["scheme"] == "bearer",
          "Unexpected authentication scheme")

    def walk(node):
        if isinstance(node, dict):
            if "$ref" in node:
                reference = node["$ref"]
                check(reference.startswith("#/"), f"Nonlocal reference: {reference}")
                target = spec
                for part in reference[2:].split("/"):
                    key = part.replace("~1", "/").replace("~0", "~")
                    check(isinstance(target, dict) and key in target,
                          f"Unresolved reference: {reference}")
                    target = target[key]
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(spec)
    documented = set()
    operation_ids = set()
    for path, item in spec["paths"].items():
        check(path.startswith("/"), f"Invalid path: {path}")
        for method, operation in item.items():
            if method not in METHODS:
                continue
            route = (method, path)
            documented.add(route)
            operation_id = operation.get("operationId")
            check(operation_id and operation_id not in operation_ids,
                  f"Missing/duplicate operationId: {route}")
            operation_ids.add(operation_id)
            check(operation.get("summary"), f"Missing summary: {route}")
            check(operation.get("responses"), f"Missing responses: {route}")
            check((operation.get("security", spec["security"]) == []) == (route in PUBLIC),
                  f"Unexpected public/authenticated access: {route}")
            parameters = item.get("parameters", []) + operation.get("parameters", [])
            keys = [(parameter["in"], parameter["name"]) for parameter in parameters]
            check(len(keys) == len(set(keys)), f"Duplicate parameters: {route}")
            path_parameters = {parameter["name"] for parameter in parameters
                               if parameter["in"] == "path"}
            check(path_parameters == set(re.findall(r"\{([^}]+)\}", path)),
                  f"Path parameters do not match template: {route}")
            check(all(parameter.get("required") for parameter in parameters
                      if parameter["in"] == "path"), f"Optional path parameter: {route}")
            for code, response in operation["responses"].items():
                check(re.fullmatch(r"[1-5][0-9]{2}|default", code), f"Invalid status: {route} {code}")
                check("$ref" in response or "description" in response,
                      f"Missing response description: {route} {code}")
                check(code != "204" or "content" not in response,
                      f"204 must have no body: {route}")

    implemented = set()
    resources = ROOT / "src/main/java/com/raphael/beachcleanup/resources"
    for source_path in resources.glob("*.java"):
        source = source_path.read_text(encoding="utf-8")
        base = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\)', source)
        base_path = base.group(1) if base else ""
        for mapping in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\b(?:\s*\(([^)]*)\))?', source):
            argument = mapping.group(2) or ""
            suffix = re.search(r'"([^"]*)"', argument)
            implemented.add((mapping.group(1).lower(), base_path + (suffix.group(1) if suffix else "")))
    check(implemented == documented,
          f"Route coverage mismatch. Missing: {sorted(implemented - documented)}; "
          f"extra: {sorted(documented - implemented)}")
    return len(spec["paths"]), len(documented)


if __name__ == "__main__":
    try:
        paths, operations = validate(json.loads(SPEC_PATH.read_text(encoding="utf-8")))
    except (ValueError, KeyError, OSError) as error:
        raise SystemExit(f"OpenAPI check failed: {error}") from error
    print(f"OpenAPI structure and route coverage OK: {paths} paths, {operations} operations.")
