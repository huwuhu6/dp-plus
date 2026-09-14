package com.hmdp.ai.v2.semantic;

/** Interpreter produces a selector; grounding never re-interprets task natural language. */
public sealed interface TaskSelector permits TaskSelector.Earliest, TaskSelector.Active, TaskSelector.MatchContext {
    record Earliest() implements TaskSelector { }
    record Active() implements TaskSelector { }
    record MatchContext(String goalCategory, String city) implements TaskSelector { }
}
