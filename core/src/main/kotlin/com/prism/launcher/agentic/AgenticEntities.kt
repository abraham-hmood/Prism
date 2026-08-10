package com.prism.launcher.agentic

import androidx.room.*

/**
 * A user-imported agentic tool. Execution is always an HTTP request -- built-in Prism-native
 * tools (list_installed_apps, launch_app) are compile-time constants in [AgenticBuiltinTools],
 * not DB rows, since they aren't something a user imports/edits.
 *
 * [parametersJson] is a raw JSON Schema object (the standard `{"type":"object","properties":{...}}`
 * shape used by OpenAI-style function-calling), stored as text rather than normalized columns
 * since its structure is arbitrary/nested and only ever needs to be read as a whole.
 *
 * [httpUrl]/[httpHeadersJson]/[httpBodyTemplate] may contain `{paramName}` placeholders that get
 * substituted with the model-supplied argument values at call time (see AgenticToolExecutor).
 */
@Entity(tableName = "agentic_tools")
data class AgenticToolEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val parametersJson: String = "{\"type\":\"object\",\"properties\":{}}",
    val httpMethod: String = "GET",
    val httpUrl: String,
    val httpHeadersJson: String = "{}",
    val httpBodyTemplate: String = "",
    val enabled: Boolean = true
)

/**
 * A user-imported "syntax" describing how to do tool-calling with a model that doesn't support
 * native structured tool-calling (mainly local/GGUF models, but also non-standard cloud
 * endpoints). Two parts: how to describe the available tools to the model (prompt injection),
 * and how to recognize a tool call in the model's raw text output.
 *
 * [systemPromptTemplate] must contain a `{{tools}}` placeholder -- replaced with every enabled
 * tool rendered via [toolFormatTemplate] (which itself uses `{{name}}`/`{{description}}`/
 * `{{parameters}}` placeholders), joined together.
 *
 * [callExtractionRegex] must have exactly one capture group, matching a JSON object of the fixed
 * shape `{"name": "...", "arguments": {...}}` -- the wrapper/marker around that JSON is what
 * varies per model convention (XML-ish tags, fenced code blocks, etc.), which is exactly what
 * this regex is for; the inner JSON shape itself is standardized by Prism, not user-defined.
 */
@Entity(tableName = "agentic_syntaxes")
data class AgenticSyntaxEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val systemPromptTemplate: String,
    val toolFormatTemplate: String,
    val callExtractionRegex: String
)

@Dao
interface AgenticDao {

    @Query("SELECT * FROM agentic_tools ORDER BY name ASC")
    suspend fun getAllTools(): List<AgenticToolEntity>

    @Query("SELECT * FROM agentic_tools WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabledTools(): List<AgenticToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTool(tool: AgenticToolEntity)

    @Query("DELETE FROM agentic_tools WHERE id = :id")
    suspend fun deleteTool(id: String)

    @Query("SELECT * FROM agentic_syntaxes ORDER BY name ASC")
    suspend fun getAllSyntaxes(): List<AgenticSyntaxEntity>

    @Query("SELECT * FROM agentic_syntaxes WHERE id = :id LIMIT 1")
    suspend fun getSyntax(id: String): AgenticSyntaxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyntax(syntax: AgenticSyntaxEntity)

    @Query("DELETE FROM agentic_syntaxes WHERE id = :id")
    suspend fun deleteSyntax(id: String)
}
