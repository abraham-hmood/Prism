package com.prism.launcher.videocraft

/**
 * GBNF grammars that keep a language model inside a format VideoCraft can parse.
 *
 * ## Why a grammar instead of a stern prompt
 *
 * "Reply with JSON only" is a request, not a constraint: a small quantised model answers it most
 * of the time and then, on the scene where it decides to add a friendly sentence first, produces
 * something no parser accepts. A GBNF grammar is enforced by the sampler -- tokens that would make
 * the reply invalid have their logits masked to negative infinity before a token is chosen -- so
 * the object is syntactically valid by construction, not by hope.
 *
 * ## What it does and does not guarantee
 *
 * The grammar below guarantees the shape of JSON: balanced braces, quoted keys, properly escaped
 * strings, numbers that are numbers. It does NOT guarantee the schema -- `{"scenes":[]}` is
 * grammatical -- so [VideoCraftJson] still validates keys and ranges and the planner still asks
 * once for a repair. That split is deliberate: a per-schema grammar would have to be regenerated
 * for every prompt and would make one bad field cost the whole call, while a generic grammar plus
 * schema validation gets the format guarantee that actually matters and keeps the fallback useful.
 *
 * ## Why the same grammar is not sent to cloud endpoints
 *
 * An OpenAI-compatible endpoint has no GBNF. The equivalent there is `response_format:
 * {"type":"json_object"}` (and Ollama's `format: "json"`), which [VideoCraftText] requests when a
 * grammar is present. The parser is identical either way, so a provider that ignores the flag
 * degrades to exactly the prompt-only behaviour.
 */
object VideoCraftGrammar {

    /** A JSON object at the root, which is what every planner call asks for. */
    val JSON_OBJECT: String = """
        root   ::= object
        object ::= "{" ws ( pair ( ws "," ws pair )* )? ws "}"
        pair   ::= string ws ":" ws value
        array  ::= "[" ws ( value ( ws "," ws value )* )? ws "]"
        value  ::= object | array | string | number | "true" | "false" | "null"
        string ::= "\"" ( [^"\\\x00-\x1F\x7F] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) )* "\""
        number ::= ("-"? ([0-9] | [1-9] [0-9]*)) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
        ws     ::= [ \t\n\r]*
    """.trimIndent()

    /**
     * True when [grammar] is one this object supplies.
     *
     * Used by the settings dispatcher to decide whether a request is a planner call or ordinary
     * prose: only the former may be sent with a JSON format flag. A remote provider that rejects
     * `response_format` is a real failure mode, so the flag is not sent speculatively.
     */
    fun isStructured(grammar: String?): Boolean = !grammar.isNullOrBlank()
}
