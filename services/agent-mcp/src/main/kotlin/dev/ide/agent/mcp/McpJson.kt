package dev.ide.agent.mcp

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier
import io.modelcontextprotocol.json.schema.JsonSchemaValidator
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier

/**
 * The JSON mapper and schema validator every MCP server here is built with, constructed directly.
 *
 * The SDK's own `McpJsonDefaults` finds these two through `ServiceLoader.findFirst()`, which Android only has
 * from API 34: below that the lookup throws `NoSuchMethodError`. The SDK falls back to that lookup whenever a
 * server builder is not handed a mapper or a validator, so both are always passed explicitly from here.
 */
object McpJson {
    val mapper: McpJsonMapper by lazy { JacksonMcpJsonMapperSupplier().get() }
    val schemaValidator: JsonSchemaValidator by lazy { JacksonJsonSchemaValidatorSupplier().get() }
}
