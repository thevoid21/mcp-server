package net.portswigger.mcp.schema

/**
 * Describes one input parameter of an MCP tool. The text is sent to clients as the
 * `description` of that parameter in the tool's JSON input schema, so the model can
 * use the tool correctly without reading external docs.
 *
 * Apply it to a `val` in a tool's input class. Interface properties (for example
 * `Paginated.count`) can carry the annotation once and are inherited by every
 * implementing tool.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ParamDoc(val text: String)
